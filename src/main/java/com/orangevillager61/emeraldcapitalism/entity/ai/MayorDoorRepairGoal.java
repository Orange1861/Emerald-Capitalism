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
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import javax.annotation.Nullable;
import java.util.EnumSet;

/** Makes a mayor collect wood from the bank and rebuild one missing village door during the day. */
public final class MayorDoorRepairGoal extends Goal {

    /** Matches the priority used by ordinary farmland repair work. */
    public static final int GOAL_PRIORITY = 4;
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
    @Nullable
    private String finishReason;

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
            return reject(level, "sleeping");
        }
        if (villager.isBaby()) {
            return reject(level, "baby villager");
        }
        if (villager.isTrading()) {
            return reject(level, "currently trading");
        }
        if (VillagerBreedingSessions.shouldYieldCustomWork(villager)) {
            return reject(level, "breeding session owns villager movement");
        }
        if (villager.getVillagerData().getProfession() != ECAPVillagerProfessions.MAYOR.get()) {
            return reject(level, "profession is " + villager.getVillagerData().getProfession());
        }
        if (level.getGameTime() < nextDoorLookupTick) {
            return reject(level, "door lookup cooldown until gameTime " + nextDoorLookupTick);
        }

        WorkContext resolved = resolveContext(level);
        if (resolved == null) {
            nextDoorLookupTick = level.getGameTime() + EMPTY_QUEUE_RETRY_TICKS;
            return reject(level, "village/bank context could not be resolved");
        }

        BlockPos nearest = findMissingDoor(level, resolved.village());
        if (nearest == null) {
            nextDoorLookupTick = level.getGameTime() + EMPTY_QUEUE_RETRY_TICKS;
            return reject(level, "no acceptable unclaimed missing door within " + MAX_RANGE
                    + " blocks; missingCount=" + resolved.village().getMissingDoorRegistry().size());
        }
        int bankPlanks = resolved.bank().getTotalPlankCount();
        boolean canStorePlanks = canStore(Items.OAK_PLANKS, PLANKS_PER_DOOR);
        boolean canStoreDoor = canStore(Items.OAK_DOOR, 1);
        if (bankPlanks < PLANKS_PER_DOOR || !canStorePlanks || !canStoreDoor) {
            nextDoorLookupTick = level.getGameTime() + EMPTY_QUEUE_RETRY_TICKS;
            return reject(level, "resources unavailable: bankPlanks=" + bankPlanks
                    + ", canStorePlanks=" + canStorePlanks + ", canStoreDoor=" + canStoreDoor);
        }

        village = resolved.village();
        bank = resolved.bank();
        targetPos = nearest;
        logEligibility(level, "eligible; selected target=" + targetPos + ", bank="
                + bank.getBlockPos() + ", village=" + village.getVillageId());
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (villager.isSleeping()) {
            return stopBecause("villager went to sleep");
        }
        if (failed) {
            return stopBecause("goal marked failed");
        }
        if (finished) {
            return stopBecause("goal marked finished");
        }
        if (targetPos == null || navigationTarget == null || village == null
                || bank == null || stage == null) {
            return stopBecause("runtime context became incomplete: target=" + targetPos
                    + ", navigationTarget=" + navigationTarget + ", village=" + village
                    + ", bank=" + bank + ", stage=" + stage);
        }
        if (villager.isTrading()) {
            return stopBecause("villager started trading");
        }
        if (VillagerBreedingSessions.shouldYieldCustomWork(villager)) {
            return stopBecause("breeding session took movement ownership");
        }
        if (villager.getVillagerData().getProfession() != ECAPVillagerProfessions.MAYOR.get()) {
            return stopBecause("profession changed to " + villager.getVillagerData().getProfession());
        }
        if (!village.isDoorRepairEnabled()) {
            return stopBecause("door repair disabled for village " + village.getVillageId());
        }
        if (!village.getMissingDoorRegistry().contains(targetPos)) {
            return stopBecause("target is no longer in missing-door registry: " + targetPos);
        }
        if (!village.getClaimedDoorPositions().contains(targetPos)) {
            return stopBecause("target claim was lost: " + targetPos);
        }
        if (bank.isRemoved()) {
            return stopBecause("bank block entity was removed: " + bank.getBlockPos());
        }
        return true;
    }

    @Override
    public void start() {
        failed = false;
        finished = false;
        carryingRepairDoor = false;
        finishReason = null;
        stage = Stage.BANK;
        navigationWatchdog.reset();
        EmeraldCapitalism.LOGGER.info(
                "[ECAP][MayorRepair] START attempt mayor={} pos={} target={} village={} bank={}",
                villager.getUUID(), villager.blockPosition(), targetPos,
                village == null ? null : village.getVillageId(),
                bank == null ? null : bank.getBlockPos());

        if (!(villager.level() instanceof ServerLevel level)) {
            failed = true;
            EmeraldCapitalism.LOGGER.warn("[ECAP][MayorRepair] START failed: mayor is not in a ServerLevel");
            return;
        }
        if (village == null || bank == null || targetPos == null) {
            failed = true;
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP][MayorRepair] START failed: missing selected context village={} bank={} target={}",
                    village, bank, targetPos);
            return;
        }
        if (!village.claimDoorPosition(targetPos)) {
            failed = true;
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP][MayorRepair] START failed: could not claim target={} missing={} claimed={}",
                    targetPos, village.getMissingDoorRegistry().contains(targetPos),
                    village.getClaimedDoorPositions().contains(targetPos));
            return;
        }
        EmeraldCapitalism.LOGGER.debug("[ECAP][MayorRepair] CLAIMED target={} village={}",
                targetPos, village.getVillageId());

        WorkContext context = new WorkContext(village, bank,
                BankBlock.getDepositApproachPos(bank.getBlockState(), bank.getBlockPos()));
        navigationTarget = findNavigationTarget(context.bankApproach());
        if (navigationTarget == null) {
            village.unclaimDoorPosition(targetPos);
            failed = true;
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP][MayorRepair] START failed: no reachable bank approach desired={} target={}",
                    context.bankApproach(), targetPos);
            return;
        }

        // Villager social behaviors run in the Brain independently of this goal's
        // GoalSelector flags. Clear an interaction that was selected before the
        // repair goal started so it cannot keep the mayor facing another villager.
        clearSocialInteractionMemories();
        setWalkTarget();
        EmeraldCapitalism.LOGGER.info(
                "[ECAP][MayorRepair] STARTED mayor={} stage={} bankApproach={} navigationTarget={} doorTarget={}",
                villager.getUUID(), stage, context.bankApproach(), navigationTarget, targetPos);
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
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP][MayorRepair] TICK failed: incomplete context mayor={} target={} navigationTarget={} village={} bank={} stage={}",
                    villager.getUUID(), targetPos, navigationTarget, village, bank, stage);
            return;
        }

        logProgress(level);
        clearSocialInteractionMemories();
        BlockPos lookTarget = stage == Stage.BANK ? bank.getBlockPos() : targetPos;
        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(lookTarget));

        if (stage == Stage.BANK && BankBlock.isAtDepositApproach(
                bank.getBlockState(), bank.getBlockPos(), villager.position())) {
            EmeraldCapitalism.LOGGER.info("[ECAP][MayorRepair] ARRIVED at bank approach={} bank={} mayor={}",
                    navigationTarget, bank.getBlockPos(), villager.getUUID());
            if (!receiveAndCraftDoor(level)) {
                finish(level, "bank withdrawal/crafting failed");
                return;
            }

            stage = Stage.DOOR;
            EmeraldCapitalism.LOGGER.info("[ECAP][MayorRepair] CRAFTED door; switching to door target={} mayor={}",
                    targetPos, villager.getUUID());
            navigationTarget = findNavigationTarget(targetPos);
            if (navigationTarget == null) {
                finish(level, "no reachable navigation target near missing door");
                return;
            }
            navigationWatchdog.reset();
            setWalkTarget();
            return;
        }

        if (stage == Stage.DOOR && (isAt(navigationTarget) || isAt(targetPos))) {
            EmeraldCapitalism.LOGGER.info("[ECAP][MayorRepair] ARRIVED at door target={} navigationTarget={} mayor={}",
                    targetPos, navigationTarget, villager.getUUID());
            if (placeDoor(level)) {
                if (village.markDoorRepaired(targetPos)) {
                    VillageRegistryData.get(level).setDirty();
                }
                finish(level, "door placement succeeded");
            } else {
                finish(level, "door placement failed");
            }
            return;
        }

        if (navigationWatchdog.isStuck(villager, navigationTarget)) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP][MayorRepair] NAVIGATION watchdog fired mayor={} stage={} currentPos={} target={} distanceSq={} navigationDone={}",
                    villager.getUUID(), stage, villager.blockPosition(), navigationTarget,
                    distanceToCenterSq(navigationTarget), villager.getNavigation().isDone());
            finish(level, "navigation watchdog reported no movement");
            return;
        }
        setWalkTarget();
    }

    @Override
    public void stop() {
        EmeraldCapitalism.LOGGER.info(
                "[ECAP][MayorRepair] STOP mayor={} pos={} stage={} target={} navigationTarget={} failed={} finished={} reason={} carryingDoor={}",
                villager.getUUID(), villager.blockPosition(), stage, targetPos, navigationTarget,
                failed, finished, finishReason, carryingRepairDoor);
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
        finishReason = null;
        nextProgressDiagnosticTick = 0L;
        lastLoggedStage = null;
        lastLoggedNavigationTarget = null;
    }

    @Nullable
    private WorkContext resolveContext(ServerLevel level) {
        BankBlockEntity resolvedBank = BankEmployeeLookup.findVillageBank(level, villager);
        if (resolvedBank == null) {
            logEligibility(level, "context unresolved: no loaded bank found for mayor");
            return null;
        }
        if (resolvedBank.getVillageId() == null) {
            logEligibility(level, "context unresolved: bank " + resolvedBank.getBlockPos()
                    + " has no village ID");
            return null;
        }

        VillageRecord resolvedVillage = VillageRegistryData.get(level).getVillages()
                .get(resolvedBank.getVillageId());
        if (resolvedVillage == null) {
            logEligibility(level, "context unresolved: no village record for bank village ID "
                    + resolvedBank.getVillageId());
            return null;
        }
        if (!resolvedVillage.isDoorRepairEnabled()) {
            logEligibility(level, "context unresolved: door repair disabled for village "
                    + resolvedVillage.getVillageId());
            return null;
        }
        boolean member = resolvedVillage.hasMember(villager.getUUID());
        boolean insideBounds = resolvedVillage.getBoundingBox()
                .contains(villager.getX(), villager.getY(), villager.getZ());
        if (!member && !insideBounds) {
            logEligibility(level, "context unresolved: mayor is not a village member and is outside bounds; "
                    + "mayorPos=" + villager.blockPosition() + ", bounds="
                    + resolvedVillage.getBoundingBox());
            return null;
        }

        BlockPos bankApproach = BankBlock.getDepositApproachPos(
                resolvedBank.getBlockState(), resolvedBank.getBlockPos());
        logEligibility(level, "context resolved: village=" + resolvedVillage.getVillageId()
                + ", bank=" + resolvedBank.getBlockPos() + ", bankApproach=" + bankApproach
                + ", member=" + member + ", insideBounds=" + insideBounds);
        return new WorkContext(resolvedVillage, resolvedBank, bankApproach);
    }

    @Nullable
    private BlockPos findMissingDoor(ServerLevel level, VillageRecord record) {
        int claimed = 0;
        int outOfRange = 0;
        int invalidWorldPosition = 0;
        double maxRangeSq = MAX_RANGE * MAX_RANGE;
        for (BlockPos pos : record.getMissingDoorRegistry()) {
            if (record.getClaimedDoorPositions().contains(pos)) {
                claimed++;
            } else if (villager.blockPosition().distSqr(pos) > maxRangeSq) {
                outOfRange++;
            } else if (!level.isLoaded(pos)
                    || !level.isLoaded(pos.above())
                    || !level.getBlockState(pos).canBeReplaced()
                    || !level.getBlockState(pos.above()).canBeReplaced()) {
                invalidWorldPosition++;
            }
        }
        BlockPos selected = record.getNearestUnclaimedMissingDoor(villager.blockPosition(), MAX_RANGE,
                pos -> level.isLoaded(pos)
                        && level.isLoaded(pos.above())
                        && level.getBlockState(pos).canBeReplaced()
                        && level.getBlockState(pos.above()).canBeReplaced());
        EmeraldCapitalism.LOGGER.debug(
                "[ECAP][MayorRepair] DOOR lookup mayor={} village={} missing={} claimed={} outOfRange={} invalidWorldPosition={} selected={}",
                villager.getUUID(), record.getVillageId(), record.getMissingDoorRegistry().size(), claimed,
                outOfRange, invalidWorldPosition, selected);
        return selected;
    }

    private boolean receiveAndCraftDoor(ServerLevel level) {
        if (bank == null) {
            EmeraldCapitalism.LOGGER.warn("[ECAP][MayorRepair] CRAFT failed: bank context is null mayor={}",
                    villager.getUUID());
            return false;
        }

        EmeraldCapitalism.LOGGER.debug("[ECAP][MayorRepair] CRAFT withdrawing {} planks from bank={} mayor={}",
                PLANKS_PER_DOOR, bank.getBlockPos(), villager.getUUID());
        ItemStack planks = bank.withdrawExactPlanks(level, PLANKS_PER_DOOR);
        if (planks.isEmpty()) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP][MayorRepair] CRAFT failed: bank returned no planks bank={} remainingPlanks={} mayor={}",
                    bank.getBlockPos(), bank.getTotalPlankCount(), villager.getUUID());
            return false;
        }

        int withdrawnCount = planks.getCount();
        EmeraldCapitalism.LOGGER.debug("[ECAP][MayorRepair] CRAFT withdrew count={} bank={} mayor={}",
                withdrawnCount, bank.getBlockPos(), villager.getUUID());
        ItemStack remainder = villager.getInventory().addItem(planks);
        if (!remainder.isEmpty()) {
            int acceptedCount = withdrawnCount - remainder.getCount();
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP][MayorRepair] CRAFT failed: mayor inventory rejected {} of {} withdrawn planks; accepted={}",
                    remainder.getCount(), withdrawnCount, acceptedCount);
            removeItem(Items.OAK_PLANKS, acceptedCount);
            returnPlanksToBankOrDrop(level, new ItemStack(Items.OAK_PLANKS, withdrawnCount));
            return false;
        }

        int removedPlanks = removeItem(Items.OAK_PLANKS, PLANKS_PER_DOOR);
        if (removedPlanks != PLANKS_PER_DOOR) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP][MayorRepair] CRAFT failed: expected to consume {} planks but removed {} mayor={}",
                    PLANKS_PER_DOOR, removedPlanks, villager.getUUID());
            returnPlanksToBankOrDrop(level,
                    new ItemStack(Items.OAK_PLANKS, PLANKS_PER_DOOR - removedPlanks));
            return false;
        }

        ItemStack doorRemainder = villager.getInventory().addItem(new ItemStack(Items.OAK_DOOR));
        if (!doorRemainder.isEmpty()) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP][MayorRepair] CRAFT failed: mayor inventory rejected oak door remainder={}",
                    doorRemainder.getCount());
            removeItem(Items.OAK_DOOR, 1 - doorRemainder.getCount());
            returnPlanksToBankOrDrop(level, new ItemStack(Items.OAK_PLANKS, PLANKS_PER_DOOR));
            return false;
        }

        carryingRepairDoor = true;
        EmeraldCapitalism.LOGGER.info("[ECAP][MayorRepair] CRAFT succeeded: mayor={} carrying oak door from bank={}",
                villager.getUUID(), bank.getBlockPos());
        return true;
    }

    private boolean placeDoor(ServerLevel level) {
        if (village == null || targetPos == null) {
            EmeraldCapitalism.LOGGER.warn("[ECAP][MayorRepair] PLACE failed: village or target context is null");
            return false;
        }
        if (!village.isDoorRepairEnabled()) {
            EmeraldCapitalism.LOGGER.warn("[ECAP][MayorRepair] PLACE failed: door repair disabled village={}",
                    village.getVillageId());
            return false;
        }
        if (!village.getMissingDoorRegistry().contains(targetPos)) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP][MayorRepair] PLACE failed: target={} is no longer missing village={} missingCount={}",
                    targetPos, village.getVillageId(), village.getMissingDoorRegistry().size());
            return false;
        }
        int doorCount = villager.getInventory().countItem(Items.OAK_DOOR);
        if (doorCount < 1) {
            EmeraldCapitalism.LOGGER.warn("[ECAP][MayorRepair] PLACE failed: mayor has no oak door inventoryCount={}",
                    doorCount);
            return false;
        }
        VillageRecord.DoorPlacement placement = village.getDoorPlacement(targetPos);
        if (placement == null) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP][MayorRepair] PLACE failed: target={} has no cached door placement village={}",
                    targetPos, village.getVillageId());
            return false;
        }

        BlockPos pos = targetPos;
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, placement.facing())
                .setValue(DoorBlock.HINGE, placement.hinge());
        BlockState currentLower = level.getBlockState(pos);
        BlockState currentUpper = level.getBlockState(pos.above());
        boolean lowerReplaceable = currentLower.canBeReplaced();
        boolean upperReplaceable = currentUpper.canBeReplaced();
        boolean survives = lower.canSurvive(level, pos);
        EmeraldCapitalism.LOGGER.debug(
                "[ECAP][MayorRepair] PLACE checking target={} lowerState={} upperState={} lowerReplaceable={} upperReplaceable={} survives={} facing={} mayor={}",
                pos, currentLower, currentUpper, lowerReplaceable, upperReplaceable, survives,
                placement.facing(), villager.getUUID());
        if (!lowerReplaceable || !upperReplaceable || !survives) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP][MayorRepair] PLACE failed validation target={} lowerReplaceable={} upperReplaceable={} survives={}",
                    pos, lowerReplaceable, upperReplaceable, survives);
            return false;
        }

        level.setBlock(pos, lower, Block.UPDATE_ALL);
        level.setBlock(pos.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        BlockState placedLower = level.getBlockState(pos);
        BlockState placedUpper = level.getBlockState(pos.above());
        if (!VillageRecord.isDoorBase(placedLower)
                || !placedUpper.is(Blocks.OAK_DOOR)
                || placedLower.getValue(DoorBlock.FACING) != placement.facing()
                || placedLower.getValue(DoorBlock.HINGE) != placement.hinge()) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP][MayorRepair] PLACE failed post-write validation target={} lowerState={} upperState={}",
                    pos, placedLower, placedUpper);
            rollbackDoorPlacement(level, pos);
            return false;
        }
        if (removeItem(Items.OAK_DOOR, 1) != 1) {
            EmeraldCapitalism.LOGGER.warn("[ECAP][MayorRepair] PLACE failed: could not remove consumed oak door target={}",
                    pos);
            rollbackDoorPlacement(level, pos);
            return false;
        }
        carryingRepairDoor = false;
        EmeraldCapitalism.LOGGER.info("[ECAP][MayorRepair] PLACE succeeded target={} lowerState={} upperState={} mayor={}",
                pos, level.getBlockState(pos), level.getBlockState(pos.above()), villager.getUUID());
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
        } else {
            EmeraldCapitalism.LOGGER.warn("[ECAP][MayorRepair] WALK_TARGET not set: navigation target is null mayor={}",
                    villager.getUUID());
        }
    }

    private void clearSocialInteractionMemories() {
        boolean hadInteractionTarget = villager.getBrain().hasMemoryValue(MemoryModuleType.INTERACTION_TARGET);
        boolean hadBreedTarget = villager.getBrain().hasMemoryValue(MemoryModuleType.BREED_TARGET);
        if (hadInteractionTarget || hadBreedTarget) {
            EmeraldCapitalism.LOGGER.debug(
                    "[ECAP][MayorRepair] CLEAR social Brain memories mayor={} interactionTarget={} breedTarget={}",
                    villager.getUUID(), hadInteractionTarget, hadBreedTarget);
        }
        villager.getBrain().eraseMemory(MemoryModuleType.INTERACTION_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.BREED_TARGET);
    }

    @Nullable
    private BlockPos findNavigationTarget(BlockPos desired) {
        if (isAt(desired)) {
            EmeraldCapitalism.LOGGER.debug("[ECAP][MayorRepair] PATH target already reached desired={} mayor={}",
                    desired, villager.getUUID());
            return desired;
        }
        BlockPos reachable = VillagerNavigationTargets.findReachableTarget(villager, desired, NAVIGATION_RADIUS);
        EmeraldCapitalism.LOGGER.debug(
                "[ECAP][MayorRepair] PATH search mayor={} from={} desired={} radius={} result={}",
                villager.getUUID(), villager.blockPosition(), desired, NAVIGATION_RADIUS, reachable);
        return reachable;
    }

    private boolean reject(ServerLevel level, String reason) {
        logEligibility(level, "rejected: " + reason);
        return false;
    }

    private void logEligibility(ServerLevel level, String reason) {
        long gameTime = level.getGameTime();
        if (reason.equals(lastEligibilityDiagnostic) && gameTime < nextEligibilityDiagnosticTick) {
            return;
        }
        lastEligibilityDiagnostic = reason;
        nextEligibilityDiagnosticTick = gameTime + ELIGIBILITY_LOG_INTERVAL_TICKS;
        EmeraldCapitalism.LOGGER.debug(
                "[ECAP][MayorRepair] CHECK mayor={} profession={} gameTime={} pos={} reason={} sleeping={} jobSiteMemory={} interactionTarget={} breedTarget={} walkTarget={}",
                villager.getUUID(), villager.getVillagerData().getProfession(), gameTime,
                villager.blockPosition(), reason, villager.isSleeping(),
                villager.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE),
                villager.getBrain().hasMemoryValue(MemoryModuleType.INTERACTION_TARGET),
                villager.getBrain().hasMemoryValue(MemoryModuleType.BREED_TARGET),
                villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET));
    }

    private boolean stopBecause(String reason) {
        EmeraldCapitalism.LOGGER.debug(
                "[ECAP][MayorRepair] CONTINUE=false mayor={} stage={} target={} navigationTarget={} reason={}",
                villager.getUUID(), stage, targetPos, navigationTarget, reason);
        return false;
    }

    private void logProgress(ServerLevel level) {
        long gameTime = level.getGameTime();
        boolean targetChanged = lastLoggedNavigationTarget == null
                ? navigationTarget != null
                : !lastLoggedNavigationTarget.equals(navigationTarget);
        if (stage == lastLoggedStage && !targetChanged && gameTime < nextProgressDiagnosticTick) {
            return;
        }
        lastLoggedStage = stage;
        lastLoggedNavigationTarget = navigationTarget == null ? null : navigationTarget.immutable();
        nextProgressDiagnosticTick = gameTime + PROGRESS_LOG_INTERVAL_TICKS;
        EmeraldCapitalism.LOGGER.debug(
                "[ECAP][MayorRepair] TICK mayor={} gameTime={} stage={} pos={} exactPos={} target={} navigationTarget={} distanceSq={} navigationDone={} walkTarget={} interactionTarget={} breedTarget={} carryingDoor={}",
                villager.getUUID(), gameTime, stage, villager.blockPosition(), villager.position(), targetPos,
                navigationTarget, navigationTarget == null ? null : distanceToCenterSq(navigationTarget),
                villager.getNavigation().isDone(),
                villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
                villager.getBrain().hasMemoryValue(MemoryModuleType.INTERACTION_TARGET),
                villager.getBrain().hasMemoryValue(MemoryModuleType.BREED_TARGET), carryingRepairDoor);
    }

    private double distanceToCenterSq(BlockPos pos) {
        return villager.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
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

    private void refundCarriedDoor(ServerLevel level) {
        if (!carryingRepairDoor || bank == null) {
            return;
        }
        carryingRepairDoor = false;
        if (removeItem(Items.OAK_DOOR, 1) != 1) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP][MayorRepair] REFUND failed: carrying flag was set but mayor had no oak door mayor={}",
                    villager.getUUID());
            return;
        }
        EmeraldCapitalism.LOGGER.info("[ECAP][MayorRepair] REFUND returning six planks mayor={} bank={}",
                villager.getUUID(), bank.getBlockPos());
        returnPlanksToBankOrDrop(level, new ItemStack(Items.OAK_PLANKS, PLANKS_PER_DOOR));
    }

    private void returnPlanksToBankOrDrop(ServerLevel level, ItemStack planks) {
        if (bank != null && !planks.isEmpty()
                && !bank.isRemoved()
                && bank.storeItemInLinkedChests(level, planks)) {
            EmeraldCapitalism.LOGGER.debug("[ECAP][MayorRepair] REFUND stored planks={} bank={} mayor={}",
                    planks.getCount(), bank.getBlockPos(), villager.getUUID());
            return;
        }
        if (!planks.isEmpty()) {
            EmeraldCapitalism.LOGGER.warn("[ECAP][MayorRepair] REFUND dropped planks={} bankUnavailable={} mayor={}",
                    planks.getCount(), bank == null || bank.isRemoved(), villager.getUUID());
            com.orangevillager61.emeraldcapitalism.util.EntityDropUtils.spawn(villager, level, planks, 0.0F);
        }
    }

    private void finish(ServerLevel level, String reason) {
        finishReason = reason;
        EmeraldCapitalism.LOGGER.info(
                "[ECAP][MayorRepair] FINISH mayor={} stage={} target={} reason={} missingDoorStillQueued={} carryingDoor={}",
                villager.getUUID(), stage, targetPos, reason,
                village != null && targetPos != null && village.getMissingDoorRegistry().contains(targetPos),
                carryingRepairDoor);
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
