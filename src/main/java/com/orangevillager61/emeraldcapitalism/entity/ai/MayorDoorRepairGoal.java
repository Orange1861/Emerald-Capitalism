package com.orangevillager61.emeraldcapitalism.entity.ai;

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
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.Comparator;
import java.util.EnumSet;

/** Sends a Mayor to the bank to convert free plank reserves into missing doors after sleeping. */
public final class MayorDoorRepairGoal extends Goal {

    private static final String SLEPT_SINCE_JOB_SITE_VISIT_KEY =
            "emeraldcapitalism_mayor_repair_slept_since_job_site_visit";
    private static final String JOB_SITE_VISIT_CONSUMED_KEY =
            "emeraldcapitalism_mayor_repair_job_site_visit_consumed";
    private static final int PLANKS_PER_DOOR = 6;
    private static final float SPEED = 0.5F;
    private static final double ARRIVAL_DIST_SQ = 4.0D;
    private static final int ATTEMPT_TICKS = 100;
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_DOOR_SEARCH_CANDIDATES = 256;

    private final Villager villager;
    private WorkContext context;
    private Stage stage;
    private BlockPos navigationTarget;
    private int attemptTicks;
    private int attempts;
    private boolean finished;
    private boolean carryingRepairDoor;

    public MayorDoorRepairGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
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
                || hasConsumedJobSiteVisit()) {
            return false;
        }

        BlockPos jobSite = findJobSite(level);
        if (jobSite == null || !isAt(jobSite)) {
            return false;
        }

        WorkContext resolved = resolveContext(level);
        if (resolved == null || findMissingDoor(level, resolved.village()) == null) {
            return false;
        }

        return resolved.bank().getTotalPlankCount() >= PLANKS_PER_DOOR
                && canStore(Items.OAK_PLANKS, PLANKS_PER_DOOR)
                && canStore(Items.OAK_DOOR, 1);
    }

    @Override
    public void start() {
        attemptTicks = 0;
        attempts = 0;
        finished = false;
        stage = Stage.BANK;

        if (!(villager.level() instanceof ServerLevel level)) {
            finish(null);
            return;
        }
        if (context == null) {
            context = resolveContext(level);
        }
        if (context == null || !beginNextDoor(level)) {
            finish(level);
            return;
        }
        markJobSiteVisitConsumed();
        if (!moveTo(context.bankApproach())) {
            finish(level);
        }
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
        return !finished
                && context != null
                && stage != null
                && attempts < MAX_ATTEMPTS
                && !villager.isTrading()
                && !VillagerBreedingSessions.shouldYieldCustomWork(villager)
                && villager.getVillagerData().getProfession() == ECAPVillagerProfessions.MAYOR.get()
                && context.village().isDoorRepairEnabled()
                && context.doorTarget() != null
                && context.village().getMissingDoorRegistry().contains(context.doorTarget())
                && !context.bank().isRemoved();
    }

    @Override
    public void tick() {
        if (!(villager.level() instanceof ServerLevel level)) {
            finished = true;
            return;
        }
        if (context == null || stage == null) {
            finish(level);
            return;
        }

        BlockPos destination = stage == Stage.BANK ? context.bankApproach() : navigationTarget;
        if (destination != null && (isAt(destination)
                || stage == Stage.DOOR && isAt(context.doorTarget()))) {
            if (stage == Stage.BANK) {
                if (!receiveAndConvertDoor(level)) {
                    finish(level);
                    return;
                }
                stage = Stage.DOOR;
                navigationTarget = VillagerNavigationTargets.findReachableTarget(
                        villager, context.doorTarget(), 2);
                if (navigationTarget == null) {
                    navigationTarget = context.doorTarget();
                }
                if (!moveTo(navigationTarget)) {
                    // Keep the target so a later retry can still place the door if
                    // the pathfinder briefly cannot produce a route.
                    attemptTicks = 0;
                }
            } else if (placeDoor(level)) {
                if (context.village().markDoorRepaired(context.doorTarget())) {
                    VillageRegistryData.get(level).setDirty();
                }
                if (!beginNextDoor(level)) {
                    finish(level);
                } else {
                    stage = Stage.BANK;
                    if (!moveTo(context.bankApproach())) {
                        finish(level);
                    }
                }
            } else {
                finish(level);
            }
            return;
        }

        attemptTicks++;
        if (attemptTicks < ATTEMPT_TICKS) {
            return;
        }
        attemptTicks = 0;
        attempts++;
        if (attempts >= MAX_ATTEMPTS || destination == null || !moveTo(destination)) {
            finish(level);
        }
    }

    @Override
    public void stop() {
        if (villager.level() instanceof ServerLevel level) {
            refundCarriedDoor(level);
        }
        villager.getNavigation().stop();
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        context = null;
        stage = null;
        navigationTarget = null;
    }

    private WorkContext resolveContext(ServerLevel level) {
        VillageRegistryData registry = VillageRegistryData.get(level);
        BankBlockEntity bank = BankEmployeeLookup.findVillageBank(level, villager);
        if (bank == null || bank.getVillageId() == null) {
            return null;
        }
        VillageRecord village = registry.getVillages().get(bank.getVillageId());
        if (village == null || !village.isDoorRepairEnabled()
                || (!village.hasMember(villager.getUUID())
                && !village.getBoundingBox().contains(villager.getX(), villager.getY(), villager.getZ()))) {
            return null;
        }
        BlockPos bankApproach = BankBlock.getDepositApproachPos(bank.getBlockState(), bank.getBlockPos());
        return new WorkContext(village, bank, bankApproach, null);
    }

    private boolean beginNextDoor(ServerLevel level) {
        if (context == null || !context.village().isDoorRepairEnabled()) {
            return false;
        }
        BlockPos target = findMissingDoor(level, context.village());
        if (target == null || context.bank().getTotalPlankCount() < PLANKS_PER_DOOR
                || !canStore(Items.OAK_PLANKS, PLANKS_PER_DOOR)
                || !canStore(Items.OAK_DOOR, 1)) {
            return false;
        }
        context = new WorkContext(context.village(), context.bank(), context.bankApproach(), target);
        attempts = 0;
        attemptTicks = 0;
        return true;
    }

    private BlockPos findMissingDoor(ServerLevel level, VillageRecord village) {
        return village.getMissingDoorRegistry().stream()
                .limit(MAX_DOOR_SEARCH_CANDIDATES)
                .filter(pos -> level.isLoaded(pos) && level.isLoaded(pos.above()))
                .filter(pos -> level.getBlockState(pos).canBeReplaced()
                        && level.getBlockState(pos.above()).canBeReplaced())
                .min(Comparator.comparingDouble(pos -> villager.distanceToSqr(
                        pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)))
                .orElse(null);
    }

    private boolean receiveAndConvertDoor(ServerLevel level) {
        ItemStack planks = context.bank().withdrawExactPlanks(level, PLANKS_PER_DOOR);
        if (planks.isEmpty()) {
            return false;
        }

        int withdrawnCount = planks.getCount();
        ItemStack remainder = villager.getInventory().addItem(planks);
        if (!remainder.isEmpty()) {
            removeItem(Items.OAK_PLANKS, withdrawnCount - remainder.getCount());
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
        BlockPos pos = context.doorTarget();
        if (!context.village().isDoorRepairEnabled()
                || !context.village().getMissingDoorRegistry().contains(pos)
                || villager.getInventory().countItem(Items.OAK_DOOR) < 1) {
            return false;
        }
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

    private boolean moveTo(BlockPos target) {
        navigationTarget = target;
        if (!villager.getNavigation().moveTo(target.getX() + 0.5D, target.getY() + 0.5D,
                target.getZ() + 0.5D, SPEED)) {
            return false;
        }
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(target, SPEED, 1));
        return true;
    }

    private boolean isAt(BlockPos pos) {
        return villager.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D,
                pos.getZ() + 0.5D) <= ARRIVAL_DIST_SQ;
    }

    private boolean canStore(net.minecraft.world.item.Item item, int amount) {
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

    private int removeItem(net.minecraft.world.item.Item item, int amount) {
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

    private BlockPos findJobSite(ServerLevel level) {
        return villager.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .filter(globalPos -> globalPos.dimension().equals(level.dimension()))
                .map(GlobalPos::pos)
                .filter(level::isLoaded)
                .filter(pos -> level.getPoiManager().getType(pos).isPresent())
                .orElse(null);
    }

    private void refundCarriedDoor(ServerLevel level) {
        if (!carryingRepairDoor) {
            return;
        }
        carryingRepairDoor = false;
        if (removeItem(Items.OAK_DOOR, 1) != 1) {
            return;
        }
        returnPlanksToBankOrDrop(level, new ItemStack(Items.OAK_PLANKS, PLANKS_PER_DOOR));
    }

    private void returnPlanksToBankOrDrop(ServerLevel level, ItemStack planks) {
        if (!planks.isEmpty()
                && !context.bank().isRemoved()
                && context.bank().storeItemInLinkedChests(level, planks)) {
            return;
        }
        if (!planks.isEmpty()) {
            villager.spawnAtLocation(planks, 0.0F);
        }
    }

    private void finish(ServerLevel level) {
        if (level != null) {
            refundCarriedDoor(level);
        }
        finished = true;
    }

    private enum Stage {
        BANK,
        DOOR
    }

    private record WorkContext(VillageRecord village, BankBlockEntity bank,
                               BlockPos bankApproach, BlockPos doorTarget) {
    }
}
