package com.orangevillager61.emeraldcapitalism.behavior;

import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.util.BankEmployeeLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.npc.Villager;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * Keeps a banker on the side opposite the bank's deposit face while working, and places a
 * prepared replacement chest once the banker stands there.
 *
 * <p>Caller: {@link VillagerGoalPackageIntegration#addWorkBehaviors}, which adds one instance to
 * the banker's WORK package. The Brain is the only scheduler: this behavior starts only while
 * {@code WALK_TARGET} is absent, publishes the route to the work side through
 * {@link BrainMovementIntent}, and lets vanilla {@code MoveToTargetSink} walk it. It never
 * creates a path.
 *
 * <p>Work-slot rule: for a banker the composition removes vanilla's job-site strolling
 * ({@code RunOne} of {@code StrollAroundPoi}/{@code StrollToPoi}), which overwrites
 * {@code WALK_TARGET} regardless of priority and would pull the banker off the work side. Any
 * other writer (panic, boats, wanted items, trading) simply holds the walk target while it
 * runs; this behavior waits and walks back afterwards.
 */
public final class BankerWorkSideBehavior extends Behavior<Villager> {

    /** Ahead of vanilla's WORK-package {@code SetWalkTargetFromBlockMemory} (priority 2). */
    public static final int PRIORITY = 1;

    private static final float SPEED = 0.5F;
    private static final int CLOSE_ENOUGH = 0;
    private static final int RUN_TICKS = 1_200;
    private static final long FAILURE_RETRY_TICKS = 100L;
    private static final long REVALIDATE_INTERVAL_TICKS = 20L;
    private static final long REPAIR_INTERVAL_TICKS = 20L;
    /** The longest walk to the work side before the journey counts as failed. */
    private static final long JOURNEY_TIMEOUT_TICKS = 400L;

    private final BrainMovementIntent intent = new BrainMovementIntent(
            "emeraldcapitalism:banker_work_side", false, JOURNEY_TIMEOUT_TICKS);
    @Nullable
    private BankBlockEntity bank;
    private long nextStartTick = Long.MIN_VALUE;
    private long nextValidationTick = Long.MIN_VALUE;
    private long nextRepairTick = Long.MIN_VALUE;
    private boolean failed;

    public BankerWorkSideBehavior() {
        super(Map.of(MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT), RUN_TICKS, RUN_TICKS);
    }

    @Override
    protected boolean checkExtraStartConditions(@Nonnull ServerLevel level, @Nonnull Villager villager) {
        long gameTime = level.getGameTime();
        if (gameTime < nextStartTick || !isEligible(villager)) {
            return false;
        }
        BankBlockEntity found = BankEmployeeLookup.findContainingVillageEmployeeBank(level, villager);
        if (found == null || !found.isBankIndependent()) {
            nextStartTick = gameTime + FAILURE_RETRY_TICKS;
            return false;
        }
        bank = found;
        return true;
    }

    @Override
    protected void start(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        failed = false;
        nextValidationTick = gameTime + REVALIDATE_INTERVAL_TICKS;
        nextRepairTick = Long.MIN_VALUE;
    }

    @Override
    protected boolean canStillUse(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        if (failed || bank == null || !isEligible(villager)) {
            return false;
        }
        if (gameTime >= nextValidationTick) {
            nextValidationTick = gameTime + REVALIDATE_INTERVAL_TICKS;
            if (bank.isRemoved() || !bank.isBankIndependent() || !bank.isEmployee(villager.getUUID())) {
                return false;
            }
        }
        // Another behavior replaced the target this behavior published: yield to it.
        return intent.observe(villager, gameTime) != BrainMovementIntent.Status.REPLACED;
    }

    @Override
    protected void tick(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        BankBlockEntity currentBank = bank;
        if (currentBank == null) {
            return;
        }
        if (isAtWorkPosition(currentBank, villager)) {
            intent.release(villager);
            if (gameTime >= nextRepairTick) {
                currentBank.replaceMissingEmeraldChest(level);
                nextRepairTick = gameTime + REPAIR_INTERVAL_TICKS;
            }
            return;
        }
        if (villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
                && !intent.ownsWalkTarget(villager)) {
            // Someone else is moving the villager; walk back once that journey ends.
            return;
        }
        switch (intent.observe(villager, gameTime)) {
            case MOVING -> {
            }
            case UNREACHABLE, TIMED_OUT -> {
                failed = true;
                nextStartTick = gameTime + FAILURE_RETRY_TICKS;
                intent.release(villager);
            }
            default -> intent.walkTo(villager, workPos(currentBank), SPEED, CLOSE_ENOUGH, gameTime);
        }
    }

    @Override
    protected void stop(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        intent.release(villager);
        bank = null;
        failed = false;
    }

    private static boolean isEligible(Villager villager) {
        return villager.level() instanceof ServerLevel
                && villager.getVillagerData().getProfession() == ECAPVillagerProfessions.BANKER.get()
                && !villager.isBaby()
                && !villager.isSleeping()
                && !villager.isTrading();
    }

    private static BlockPos workPos(BankBlockEntity bank) {
        return BankBlock.getBankerWorkPos(bank.getBlockState(), bank.getBlockPos());
    }

    private static boolean isAtWorkPosition(BankBlockEntity bank, Villager villager) {
        return BankBlock.isAtBankerWorkPos(bank.getBlockState(), bank.getBlockPos(), villager.position());
    }
}
