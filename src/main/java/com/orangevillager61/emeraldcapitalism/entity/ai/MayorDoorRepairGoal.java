package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.util.BankEmployeeLookup;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import javax.annotation.Nullable;
import java.util.EnumSet;

/** Makes a mayor collect wood from the bank and rebuild one missing village door. */
public final class MayorDoorRepairGoal extends Goal {

    private static final String SLEPT_SINCE_JOB_SITE_VISIT_KEY =
            "emeraldcapitalism_mayor_repair_slept_since_job_site_visit";
    private static final String JOB_SITE_VISIT_CONSUMED_KEY =
            "emeraldcapitalism_mayor_repair_job_site_visit_consumed";
    private static final int PLANKS_PER_DOOR = 6;
    private static final double MAX_RANGE = 32.0D;
    private static final double ARRIVAL_DISTANCE_SQ = 2.25D;
    private static final float SPEED_MODIFIER = 0.6F;
    private static final int WALK_TARGET_CLOSE_ENOUGH = 1;
    private static final int EMPTY_QUEUE_RETRY_TICKS = 100;
    private static final int NAVIGATION_RADIUS = 2;
    private static final long ELIGIBILITY_LOG_INTERVAL_TICKS = 40L;
    private static final long PROGRESS_LOG_INTERVAL_TICKS = 20L;

    private final Villager villager;
    @Nullable
    private BlockPos targetPos;
    @Nullable
    private BlockPos navigationTarget;
    @Nullable
    private VillageRecord village;
    @Nullable
    private BankBlockEntity bank;
    @Nullable
    private Stage stage;
    private final VillagerNavigationWatchdog navigationWatchdog = new VillagerNavigationWatchdog();
    private boolean failed;
    private boolean finished;
    private boolean carryingRepairDoor;
    private long nextDoorLookupTick;
    @Nullable
    private String lastEligibilityDiagnostic;
    private long nextEligibilityDiagnosticTick;
    private long nextProgressDiagnosticTick;
    @Nullable
    private Stage lastLoggedStage;
    @Nullable
    private BlockPos lastLoggedNavigationTarget;

    public MayorDoorRepairGoal(Villager villager) {
        this.villager = villager;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        if (villager.isSleeping()) {
            if (!villager.isBaby()
                    && villager.getVillagerData().getProfession() == ECAPVillagerProfessions.MAYOR.get()) {
                observeSleep();
            }
            return false;
        }
        if (villager.isBaby()
                || villager.isTrading()
                || VillagerBreedingSessions.shouldYieldCustomWork(villager)
                || villager.getVillagerData().getProfession() != ECAPVillagerProfessions.MAYOR.get()
                || !hasSleptSinceJobSiteVisit()
                || hasConsumedJobSiteVisit()
                || level.getGameTime() < nextDoorLookupTick) {
            return false;
        }

        BlockPos jobSite = findJobSite(level);
        if (jobSite == null || !isAt(jobSite)) {
            nextDoorLookupTick = level.getGameTime() + EMPTY_QUEUE_RETRY_TICKS;
            return false;
        }

        WorkContext resolved = resolveContext(level);
        if (resolved == null) {
            nextDoorLookupTick = level.getGameTime() + EMPTY_QUEUE_RETRY_TICKS;
            return false;
        }

        BlockPos nearest = findMissingDoor(level, resolved.village());
        if (nearest == null) {
            nextDoorLookupTick = level.getGameTime() + EMPTY_QUEUE_RETRY_TICKS;
            return false;
        }
        if (resolved.bank().getTotalPlankCount() < PLANKS_PER_DOOR
                || !canStore(Items.OAK_PLANKS, PLANKS_PER_DOOR)
                || !canStore(Items.OAK_DOOR, 1)) {
            nextDoorLookupTick = level.getGameTime() + EMPTY_QUEUE_RETRY_TICKS;
            return false;
        }

        village = resolved.village();
        bank = resolved.bank();
        targetPos = nearest;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (villager.isSleeping()) {
            if (!villager.isBaby()
                    && villager.getVillagerData().getProfession() == ECAPVillagerProfessions.MAYOR.get()) {
                observeSleep();
            }
            return false;
        }
        return !failed
                && !finished
                && targetPos != null
                && navigationTarget != null
                && village != null
                && bank != null
                && stage != null
                && !villager.isTrading()
                && !VillagerBreedingSessions.shouldYieldCustomWork(villager)
                && villager.getVillagerData().getProfession() == ECAPVillagerProfessions.MAYOR.get()
                && village.isDoorRepairEnabled()
                && village.getMissingDoorRegistry().contains(targetPos)
                && village.getClaimedDoorPositions().contains(targetPos)
                && !bank.isRemoved();
    }

    @Override
    public void start() {
        failed = false;
        finished = false;
        carryingRepairDoor = false;
        stage = Stage.BANK;
        navigationWatchdog.reset();

        if (!(villager.level() instanceof ServerLevel level)
                || village == null
                || bank == null
                || targetPos == null
                || !village.claimDoorPosition(targetPos)) {
            failed = true;
            return;
        }

        WorkContext context = new WorkContext(village, bank,
                BankBlock.getDepositApproachPos(bank.getBlockState(), bank.getBlockPos()));
        navigationTarget = findNavigationTarget(context.bankApproach());
        if (navigationTarget == null) {
            village.unclaimDoorPosition(targetPos);
            failed = true;
            return;
        }

        // Villager social behaviors run in the Brain independently of this goal's
        // GoalSelector flags. Clear an interaction that was selected before the
        // repair goal started so it cannot keep the mayor facing another villager.
        clearSocialInteractionMemories();
        markJobSiteVisitConsumed();
        setWalkTarget();
    }

    @Override
    public void tick() {
        if (!(villager.level() instanceof ServerLevel level)
                || targetPos == null
                || navigationTarget == null
                || village == null
                || bank == null
                || stage == null) {
            failed = true;
            return;
        }

        clearSocialInteractionMemories();
        BlockPos lookTarget = stage == Stage.BANK ? bank.getBlockPos() : targetPos;
        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(lookTarget));

        if (stage == Stage.BANK && isAt(navigationTarget)) {
            if (!receiveAndCraftDoor(level)) {
                finish(level);
                return;
            }

            stage = Stage.DOOR;
            navigationTarget = findNavigationTarget(targetPos);
            if (navigationTarget == null) {
                finish(level);
                return;
            }
            navigationWatchdog.reset();
            setWalkTarget();
            return;
        }

        if (stage == Stage.DOOR && (isAt(navigationTarget) || isAt(targetPos))) {
            if (placeDoor(level)) {
                if (village.markDoorRepaired(targetPos)) {
                    VillageRegistryData.get(level).setDirty();
                }
                finish(level);
            } else {
                finish(level);
            }
            return;
        }

        if (navigationWatchdog.isStuck(villager, navigationTarget)) {
            finish(level);
            return;
        }
        setWalkTarget();
    }

    @Override
    public void stop() {
        if (villager.level() instanceof ServerLevel level) {
            refundCarriedDoor(level);
        }
        if (village != null && targetPos != null) {
            village.unclaimDoorPosition(targetPos);
        }
        villager.getNavigation().stop();
        clearSocialInteractionMemories();
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        targetPos = null;
        navigationTarget = null;
        village = null;
        bank = null;
        stage = null;
        navigationWatchdog.reset();
        failed = false;
        finished = false;
    }

    @Nullable
    private WorkContext resolveContext(ServerLevel level) {
        BankBlockEntity resolvedBank = BankEmployeeLookup.findVillageBank(level, villager);
        if (resolvedBank == null || resolvedBank.getVillageId() == null) {
            return null;
        }

        VillageRecord resolvedVillage = VillageRegistryData.get(level).getVillages()
                .get(resolvedBank.getVillageId());
        if (resolvedVillage == null
                || !resolvedVillage.isDoorRepairEnabled()
                || (!resolvedVillage.hasMember(villager.getUUID())
                && !resolvedVillage.getBoundingBox().contains(villager.getX(), villager.getY(), villager.getZ()))) {
            return null;
        }

        BlockPos bankApproach = BankBlock.getDepositApproachPos(
                resolvedBank.getBlockState(), resolvedBank.getBlockPos());
        return new WorkContext(resolvedVillage, resolvedBank, bankApproach);
    }

    @Nullable
    private BlockPos findMissingDoor(ServerLevel level, VillageRecord record) {
        return record.getNearestUnclaimedMissingDoor(villager.blockPosition(), MAX_RANGE,
                pos -> level.isLoaded(pos)
                        && level.isLoaded(pos.above())
                        && level.getBlockState(pos).canBeReplaced()
                        && level.getBlockState(pos.above()).canBeReplaced());
    }

    private boolean receiveAndCraftDoor(ServerLevel level) {
        if (bank == null) {
            return false;
        }

        ItemStack planks = bank.withdrawExactPlanks(level, PLANKS_PER_DOOR);
        if (planks.isEmpty()) {
            return false;
        }

        int withdrawnCount = planks.getCount();
        ItemStack remainder = villager.getInventory().addItem(planks);
        if (!remainder.isEmpty()) {
            int acceptedCount = withdrawnCount - remainder.getCount();
            removeItem(Items.OAK_PLANKS, acceptedCount);
            returnPlanksToBankOrDrop(level, new ItemStack(Items.OAK_PLANKS, withdrawnCount));
            return false;
        }

        int removedPlanks = removeItem(Items.OAK_PLANKS, PLANKS_PER_DOOR);
        if (removedPlanks != PLANKS_PER_DOOR) {
            returnPlanksToBankOrDrop(level,
                    new ItemStack(Items.OAK_PLANKS, PLANKS_PER_DOOR - removedPlanks));
            return false;
        }

        ItemStack doorRemainder = villager.getInventory().addItem(new ItemStack(Items.OAK_DOOR));
        if (!doorRemainder.isEmpty()) {
            removeItem(Items.OAK_DOOR, 1 - doorRemainder.getCount());
            returnPlanksToBankOrDrop(level, new ItemStack(Items.OAK_PLANKS, PLANKS_PER_DOOR));
            return false;
        }

        carryingRepairDoor = true;
        return true;
    }

    private boolean placeDoor(ServerLevel level) {
        if (village == null || targetPos == null
                || !village.isDoorRepairEnabled()
                || !village.getMissingDoorRegistry().contains(targetPos)
                || villager.getInventory().countItem(Items.OAK_DOOR) < 1) {
            return false;
        }

        BlockPos pos = targetPos;
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, villager.getDirection())
                .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
        if (!level.getBlockState(pos).canBeReplaced()
                || !level.getBlockState(pos.above()).canBeReplaced()
                || !lower.canSurvive(level, pos)) {
            return false;
        }

        level.setBlock(pos, lower, Block.UPDATE_ALL);
        level.setBlock(pos.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        if (!VillageRecord.isDoorBase(level.getBlockState(pos))
                || !level.getBlockState(pos.above()).is(Blocks.OAK_DOOR)) {
            rollbackDoorPlacement(level, pos);
            return false;
        }
        if (removeItem(Items.OAK_DOOR, 1) != 1) {
            rollbackDoorPlacement(level, pos);
            return false;
        }
        carryingRepairDoor = false;
        return true;
    }

    private void rollbackDoorPlacement(ServerLevel level, BlockPos pos) {
        if (level.getBlockState(pos).is(Blocks.OAK_DOOR)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        if (level.getBlockState(pos.above()).is(Blocks.OAK_DOOR)) {
            level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private void setWalkTarget() {
        if (navigationTarget != null) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(navigationTarget, SPEED_MODIFIER, WALK_TARGET_CLOSE_ENOUGH));
        }
    }

    private void clearSocialInteractionMemories() {
        villager.getBrain().eraseMemory(MemoryModuleType.INTERACTION_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.BREED_TARGET);
    }

    @Nullable
    private BlockPos findNavigationTarget(BlockPos desired) {
        if (isAt(desired)) {
            return desired;
        }
        return VillagerNavigationTargets.findReachableTarget(villager, desired, NAVIGATION_RADIUS);
    }

    private boolean isAt(BlockPos pos) {
        return villager.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D,
                pos.getZ() + 0.5D) <= ARRIVAL_DISTANCE_SQ;
    }

    private boolean canStore(Item item, int amount) {
        int capacity = 0;
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                capacity += item.getDefaultMaxStackSize();
            } else if (stack.is(item)) {
                capacity += stack.getMaxStackSize() - stack.getCount();
            }
            if (capacity >= amount) {
                return true;
            }
        }
        return false;
    }

    private int removeItem(Item item, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < villager.getInventory().getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (!stack.is(item)) {
                continue;
            }
            int removed = Math.min(stack.getCount(), remaining);
            stack.shrink(removed);
            remaining -= removed;
            villager.getInventory().setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
        }
        return amount - remaining;
    }

    private void observeSleep() {
        if (!villager.getPersistentData().getBoolean(SLEPT_SINCE_JOB_SITE_VISIT_KEY)
                || villager.getPersistentData().getBoolean(JOB_SITE_VISIT_CONSUMED_KEY)) {
            villager.getPersistentData().putBoolean(SLEPT_SINCE_JOB_SITE_VISIT_KEY, true);
            villager.getPersistentData().putBoolean(JOB_SITE_VISIT_CONSUMED_KEY, false);
        }
    }

    private boolean hasSleptSinceJobSiteVisit() {
        return villager.getPersistentData().getBoolean(SLEPT_SINCE_JOB_SITE_VISIT_KEY);
    }

    private boolean hasConsumedJobSiteVisit() {
        return villager.getPersistentData().getBoolean(JOB_SITE_VISIT_CONSUMED_KEY);
    }

    private void markJobSiteVisitConsumed() {
        villager.getPersistentData().putBoolean(SLEPT_SINCE_JOB_SITE_VISIT_KEY, false);
        villager.getPersistentData().putBoolean(JOB_SITE_VISIT_CONSUMED_KEY, true);
    }

    @Nullable
    private BlockPos findJobSite(ServerLevel level) {
        return villager.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .filter(globalPos -> globalPos.dimension().equals(level.dimension()))
                .map(GlobalPos::pos)
                .filter(level::isLoaded)
                .filter(pos -> level.getPoiManager().getType(pos).isPresent())
                .orElse(null);
    }

    private void refundCarriedDoor(ServerLevel level) {
        if (!carryingRepairDoor || bank == null) {
            return;
        }
        carryingRepairDoor = false;
        if (removeItem(Items.OAK_DOOR, 1) != 1) {
            return;
        }
        returnPlanksToBankOrDrop(level, new ItemStack(Items.OAK_PLANKS, PLANKS_PER_DOOR));
    }

    private void returnPlanksToBankOrDrop(ServerLevel level, ItemStack planks) {
        if (bank != null && !planks.isEmpty()
                && !bank.isRemoved()
                && bank.storeItemInLinkedChests(level, planks)) {
            return;
        }
        if (!planks.isEmpty()) {
            villager.spawnAtLocation(planks, 0.0F);
        }
    }

    private void finish(ServerLevel level) {
        refundCarriedDoor(level);
        finished = true;
    }

    private enum Stage {
        BANK,
        DOOR
    }

    private record WorkContext(VillageRecord village, BankBlockEntity bank, BlockPos bankApproach) {
    }
}
