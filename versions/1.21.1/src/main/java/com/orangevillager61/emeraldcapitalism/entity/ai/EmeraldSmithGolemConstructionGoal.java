package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldOreProcessorBlockEntity;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import com.orangevillager61.emeraldcapitalism.event.EmeraldGolemEvents;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.registry.ECAPEntityTypes;
import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.EventHooks;

import java.util.EnumSet;
import java.util.List;

/**
 * Builds Emerald Golems, then Emerald Skrimishers, at the construction marker
 * assigned to a Bank.
 * The goal reserves the Bank before walking to the processor so multiple
 * Emeraldsmiths cannot consume the same four blocks and pumpkin concurrently.
 * Construction is performed at the Bank's marker while the Emeraldsmith stays
 * at the processor, because the marker itself may not be reachable by a villager.
 */
public final class EmeraldSmithGolemConstructionGoal extends Goal {

    private static final float SPEED = 0.5F;
    private static final double ARRIVAL_DIST_SQ = 4.0D;
    private static final int ATTEMPT_TICKS = 100;
    private static final int MAX_ATTEMPTS = 3;
    private static final int SUCCESS_COOLDOWN = 20;
    private static final int FAILURE_COOLDOWN = 100;
    private static final int DIAGNOSTIC_INTERVAL_TICKS = 200;
    private static final int BLOCK_PLACEMENT_INTERVAL_TICKS = 30;

    private final Villager villager;

    private WorkContext context;
    private Stage stage;
    private ItemStack carvedPumpkin = ItemStack.EMPTY;
    private ItemStack emeraldBlocks = ItemStack.EMPTY;
    private ItemStack emeraldChest = ItemStack.EMPTY;
    private ItemStack emeraldsToCraft = ItemStack.EMPTY;
    private int attemptTicks;
    private int attempts;
    private boolean finished;
    private long nextActionTick;
    private long nextPlacementTick;
    private int nextPlacementIndex;
    private Formation activeFormation;
    private ConstructionType constructionType;
    private long nextDiagnosticTick;
    private String lastDiagnosticReason = "";
    private String failureReason = "unspecified failure";

    public EmeraldSmithGolemConstructionGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        if (villager.getVillagerData().getProfession() != ECAPVillagerProfessions.EMERALDSMITH.get()) {
            return false;
        }
        if (villager.isSleeping()) {
            logDiagnostic(level, "blocked: sleeping");
            return false;
        }
        if (villager.isTrading()) {
            logDiagnostic(level, "blocked: trading");
            return false;
        }
        if (VillagerBreedingSessions.shouldYieldCustomWork(villager)) {
            logDiagnostic(level, "blocked: breeding session or BREED_TARGET memory");
            return false;
        }
        if (level.getGameTime() < nextActionTick) {
            logDiagnostic(level, "blocked: cooldown until tick " + nextActionTick);
            return false;
        }

        WorkContext resolved = resolveContext(level);
        if (resolved == null) {
            return false;
        }

        BankBlockEntity bank = resolved.bank();
        ConstructionType availableType = selectConstructionType(level, bank);
        if (availableType == ConstructionType.NONE) {
            logDiagnostic(level, "blocked: bank gate; reservation="
                    + bank.getActiveGolemConstructionVillager()
                    + ", marker=" + bank.getGolemConstructionPos()
                    + ", registeredGolems=" + bank.getRegisteredEmeraldGolemCount()
                    + ", capacity=" + bank.getExpectedEmeraldGolemCount()
                    + ", emeralds=" + bank.getTotalEmeraldCount()
                    + ", emeraldBlocks=" + bank.getTotalEmeraldBlockCount()
                    + ", pumpkins=" + bank.getTotalPumpkinCount());
            return false;
        }

        logDiagnostic(level, "eligible: bank=" + bank.getBlockPos()
                + ", processor=" + resolved.processorPos()
                + ", construction=" + resolved.constructionPos()
                + ", type=" + availableType);
        return true;
    }

    @Override
    public void start() {
        attemptTicks = 0;
        attempts = 0;
        finished = false;
        carvedPumpkin = ItemStack.EMPTY;
        emeraldBlocks = ItemStack.EMPTY;
        emeraldChest = ItemStack.EMPTY;
        emeraldsToCraft = ItemStack.EMPTY;
        activeFormation = null;
        constructionType = ConstructionType.NONE;
        nextPlacementIndex = 0;
        nextPlacementTick = 0L;
        failureReason = "unspecified failure";

        if (!(villager.level() instanceof ServerLevel level)) {
            finished = true;
            return;
        }

        context = resolveContext(level);
        if (context != null) {
            constructionType = selectConstructionType(level, context.bank());
        }
        boolean reserved = context != null && (constructionType == ConstructionType.GOLEM
                ? context.bank().beginGolemConstruction(villager.getUUID())
                : constructionType == ConstructionType.SKRIMISHER
                && context.bank().beginSkrimisherConstruction(level, villager.getUUID()));
        if (context == null || !reserved) {
            failureReason = context == null
                    ? "context disappeared before start"
                    : "bank reservation was lost before start";
            EmeraldCapitalism.LOGGER.debug(
                    "[EmeraldsmithGolem] START FAILED villager={} uuid={} reason={}",
                    villager.getName().getString(), villager.getUUID(), failureReason);
            finished = true;
            nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
            return;
        }

        BlockPos navigationTarget = VillagerNavigationTargets.findReachableTarget(villager, context.processorPos(), 2);
        if (navigationTarget == null) {
            failureReason = "processor has no reachable standing position";
            context.bank().endGolemConstruction(villager.getUUID());
            finished = true;
            nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
            return;
        }
        context = new WorkContext(context.bank(), context.processor(), context.processorPos(),
                navigationTarget, context.constructionPos());

        stage = Stage.PROCESSOR;
        EmeraldCapitalism.LOGGER.info(
                "[EmeraldsmithGolem] START villager={} uuid={} bank={} processor={} construction={} type={}",
                villager.getName().getString(), villager.getUUID(), context.bank().getBlockPos(),
                context.processorPos(), context.constructionPos(), constructionType);
        moveToProcessor();
    }

    @Override
    public boolean canContinueToUse() {
        return !finished
                && context != null
                && stage != null
                && attempts < MAX_ATTEMPTS
                && villager.getVillagerData().getProfession() == ECAPVillagerProfessions.EMERALDSMITH.get()
                && !VillagerBreedingSessions.shouldYieldCustomWork(villager);
    }

    @Override
    public void tick() {
        if (!(villager.level() instanceof ServerLevel level) || context == null || stage == null) {
            failureReason = "goal tick lost its server context or stage";
            finished = true;
            return;
        }

        if (stage == Stage.PROCESSOR) {
            if (!isAtProcessor(level)) {
                setProcessorWalkTarget();
                tickNavigation(this::moveToProcessor);
                return;
            }

            if (!convertPumpkin(level)) {
                finishWithFailure(level);
                return;
            }

            stage = Stage.CONSTRUCTION;
            attemptTicks = 0;
            attempts = 0;
            nextPlacementTick = level.getGameTime();
            villager.getNavigation().stop();
            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            EmeraldCapitalism.LOGGER.info(
                    "[EmeraldsmithGolem] STAGE villager={} carved pumpkin at processor={}; constructing at marker={}",
                    villager.getName().getString(), context.processorPos(), context.constructionPos());
            return;
        }

        ConstructionResult constructionResult = advanceConstruction(level);
        if (constructionResult == ConstructionResult.SUCCESS) {
            finished = true;
            nextActionTick = level.getGameTime() + SUCCESS_COOLDOWN;
            EmeraldCapitalism.LOGGER.info(
                    "[EmeraldsmithGolem] SUCCESS villager={} bank={} registeredGolems={} capacity={}",
                    villager.getName().getString(), context.bank().getBlockPos(),
                    context.bank().getRegisteredEmeraldGolemCount(),
                    context.bank().getExpectedEmeraldGolemCount());
        } else if (constructionResult == ConstructionResult.FAILED) {
            finishWithFailure(level);
        }
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        if (villager.level() instanceof ServerLevel level && context != null) {
            if (!finished) {
                EmeraldCapitalism.LOGGER.warn(
                        "[EmeraldsmithGolem] INTERRUPTED villager={} uuid={} stage={} sleeping={} trading={} breeding={}",
                        villager.getName().getString(), villager.getUUID(), stage,
                        villager.isSleeping(), villager.isTrading(),
                        VillagerBreedingSessions.shouldYieldCustomWork(villager));
            }
            returnUnfinishedMaterials(level);
            context.bank().endGolemConstruction(villager.getUUID());
            if (finished && nextActionTick < level.getGameTime()) {
                nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
            }
        }
    }

    private void tickNavigation(Runnable retryNavigation) {
        attemptTicks++;
        if (attemptTicks < ATTEMPT_TICKS) {
            return;
        }

        attemptTicks = 0;
        attempts++;
        if (attempts >= MAX_ATTEMPTS) {
            failureReason = "navigation timeout during " + stage
                    + "; villagerPos=" + villager.blockPosition()
                    + ", target=" + (stage == Stage.PROCESSOR
                    ? context.processorPos() : context.constructionPos());
            if (villager.level() instanceof ServerLevel level) {
                finishWithFailure(level);
            } else {
                finished = true;
            }
            return;
        }
        retryNavigation.run();
    }

    private void finishWithFailure(ServerLevel level) {
        EmeraldCapitalism.LOGGER.warn(
                "[EmeraldsmithGolem] FAILED villager={} uuid={} stage={} reason={}",
                villager.getName().getString(), villager.getUUID(), stage, failureReason);
        finished = true;
        nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
    }

    private boolean convertPumpkin(ServerLevel level) {
        if (context == null) {
            failureReason = "pumpkin conversion lost work context";
            return false;
        }
        if (!isAtProcessor(level)) {
            failureReason = "villager was no longer at the processor during pumpkin conversion";
            return false;
        }
        if (context.bank().getTotalPumpkinCount() < 1) {
            failureReason = "bank reported no pumpkins during conversion";
            return false;
        }

        ItemStack pumpkin = context.bank().withdrawExactItem(level, Items.PUMPKIN, 1);
        if (pumpkin.isEmpty()) {
            failureReason = "bank reported pumpkins but exact pumpkin withdrawal failed";
            return false;
        }

        carvedPumpkin = new ItemStack(Items.CARVED_PUMPKIN);
        level.playSound(null, context.processorPos(), SoundEvents.PUMPKIN_CARVE,
                SoundSource.BLOCKS, 1.0F, 1.0F);
        return true;
    }

    /**
     * Advances construction by at most one placement. The first emerald block
     * is placed immediately after the smith reaches the construction stage;
     * each subsequent block placement is delayed by 30 game ticks.
     */
    private ConstructionResult advanceConstruction(ServerLevel level) {
        if (context == null) {
            failureReason = "construction lost work context";
            return ConstructionResult.FAILED;
        }
        if (carvedPumpkin.isEmpty()) {
            failureReason = "construction started without a carved pumpkin";
            return ConstructionResult.FAILED;
        }

        if (activeFormation == null) {
            BlockPos anchor = context.constructionPos();
            activeFormation = findClearFormation(level, anchor);
            if (activeFormation == null) {
                failureReason = "no clear " + constructionType + " formation at " + anchor
                        + "; below=" + level.getBlockState(anchor.below())
                        + ", anchor=" + level.getBlockState(anchor)
                        + ", center=" + level.getBlockState(anchor.above())
                        + ", east=" + level.getBlockState(anchor.above().east())
                        + ", west=" + level.getBlockState(anchor.above().west())
                        + ", north=" + level.getBlockState(anchor.above().north())
                        + ", south=" + level.getBlockState(anchor.above().south())
                        + ", pumpkin=" + level.getBlockState(anchor.above(2));
                return ConstructionResult.FAILED;
            }

            if (constructionType == ConstructionType.GOLEM
                    ? !withdrawAndCraftEmeraldBlocks(level, 4)
                    : !withdrawSkrimisherMaterials(level)) {
                return ConstructionResult.FAILED;
            }
        }

        if (level.getGameTime() < nextPlacementTick) {
            return ConstructionResult.IN_PROGRESS;
        }

        List<BlockPos> emeraldPositions = activeFormation.emeraldPositions();
        BlockPos pumpkinPos = activeFormation.pumpkinPos();
        if (nextPlacementIndex < emeraldPositions.size()) {
            BlockPos pos = emeraldPositions.get(nextPlacementIndex);
            if (!level.getBlockState(pos).isAir()
                    || !level.setBlock(pos, Blocks.EMERALD_BLOCK.defaultBlockState(), 3)) {
                failureReason = "failed to place emerald block at " + pos;
                return ConstructionResult.FAILED;
            }
            nextPlacementIndex++;
            nextPlacementTick = level.getGameTime() + BLOCK_PLACEMENT_INTERVAL_TICKS;
            return ConstructionResult.IN_PROGRESS;
        }

        if (activeFormation.chestPos() != null
                && nextPlacementIndex == emeraldPositions.size()) {
            BlockPos chestPos = activeFormation.chestPos();
            if (!level.getBlockState(chestPos).isAir()
                    || !level.setBlock(chestPos, ECAPBlocks.EMERALD_CHEST.get().defaultBlockState(), 3)) {
                failureReason = "failed to place emerald chest at " + chestPos;
                return ConstructionResult.FAILED;
            }
            nextPlacementIndex++;
            nextPlacementTick = level.getGameTime() + BLOCK_PLACEMENT_INTERVAL_TICKS;
            return ConstructionResult.IN_PROGRESS;
        }

        int pumpkinIndex = emeraldPositions.size() + (activeFormation.chestPos() == null ? 0 : 1);
        if (nextPlacementIndex == pumpkinIndex) {
            if (!level.getBlockState(pumpkinPos).isAir()
                    || !level.setBlock(pumpkinPos, Blocks.CARVED_PUMPKIN.defaultBlockState(), 3)) {
                failureReason = "failed to place carved pumpkin at " + pumpkinPos;
                return ConstructionResult.FAILED;
            }
            nextPlacementIndex++;
            // Leave the completed structure in the world for a tick so the
            // pumpkin placement is a real final construction step before the
            // formation is consumed to spawn the Emerald Golem.
            nextPlacementTick = level.getGameTime() + 1L;
            return ConstructionResult.IN_PROGRESS;
        }

        if (constructionType == ConstructionType.SKRIMISHER) {
            if (!EmeraldGolemEvents.trySpawnEmeraldSkrimisher(level, pumpkinPos, villager)) {
                failureReason = "emerald chest formation did not summon an Emerald Skrimisher";
                return ConstructionResult.FAILED;
            }
            emeraldBlocks = ItemStack.EMPTY;
            emeraldChest = ItemStack.EMPTY;
            carvedPumpkin = ItemStack.EMPTY;
            activeFormation = null;
            return ConstructionResult.SUCCESS;
        }

        EmeraldGolem golem = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        if (golem == null) {
            failureReason = "emerald golem entity type returned null";
            return ConstructionResult.FAILED;
        }

        clearFormation(level, emeraldPositions, pumpkinPos);
        BlockPos spawnPos = activeFormation.anchor().above();
        golem.moveTo(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D,
                level.getRandom().nextFloat() * 360.0F, 0.0F);
        EventHooks.finalizeMobSpawn(golem, level, level.getCurrentDifficultyAt(spawnPos),
                MobSpawnType.MOB_SUMMONED, null);
        golem.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 5 * 20, 4));
        golem.setPersistenceRequired();
        golem.setBankEmployeePos(context.bank().getBlockPos());

        if (!level.addFreshEntity(golem)) {
            failureReason = "server rejected the emerald golem entity spawn";
            golem.discard();
            return ConstructionResult.FAILED;
        }
        if (!context.bank().registerEmeraldGolemEmployee(golem.getUUID())) {
            failureReason = "bank rejected the new emerald golem UUID registration";
            golem.discard();
            return ConstructionResult.FAILED;
        }
        EmeraldGolemRetreatGoal.start(golem, context.bank().getBlockPos());

        emeraldBlocks = ItemStack.EMPTY;
        carvedPumpkin = ItemStack.EMPTY;
        activeFormation = null;
        return ConstructionResult.SUCCESS;
    }

    private void clearPlacedFormation(ServerLevel level) {
        if (activeFormation == null) {
            return;
        }

        List<BlockPos> emeraldPositions = activeFormation.emeraldPositions();
        int placedEmeraldCount = Math.min(nextPlacementIndex, emeraldPositions.size());
        for (int i = 0; i < placedEmeraldCount; i++) {
            BlockPos pos = emeraldPositions.get(i);
            if (level.getBlockState(pos).is(Blocks.EMERALD_BLOCK)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
        if (activeFormation.chestPos() != null
                && nextPlacementIndex > emeraldPositions.size()
                && level.getBlockState(activeFormation.chestPos()).is(ECAPBlocks.EMERALD_CHEST.get())) {
            level.setBlock(activeFormation.chestPos(), Blocks.AIR.defaultBlockState(), 3);
        }
        int pumpkinIndex = emeraldPositions.size() + (activeFormation.chestPos() == null ? 0 : 1);
        if (nextPlacementIndex > pumpkinIndex
                && level.getBlockState(activeFormation.pumpkinPos()).is(Blocks.CARVED_PUMPKIN)) {
            level.setBlock(activeFormation.pumpkinPos(), Blocks.AIR.defaultBlockState(), 3);
        }
        activeFormation = null;
        nextPlacementIndex = 0;
    }

    private Formation findClearFormation(ServerLevel level, BlockPos anchor) {
        if (constructionType == ConstructionType.SKRIMISHER) {
            Formation skirmisher = createSkrimisherFormation(anchor);
            return isClearFormation(level, skirmisher) ? skirmisher : null;
        }

        Formation eastWest = createFormation(anchor, Direction.EAST, Direction.WEST);
        if (isClearFormation(level, eastWest)) {
            return eastWest;
        }

        Formation northSouth = createFormation(anchor, Direction.NORTH, Direction.SOUTH);
        return isClearFormation(level, northSouth) ? northSouth : null;
    }

    private Formation createFormation(BlockPos anchor, Direction firstArm, Direction secondArm) {
        BlockPos center = anchor.above();
        return new Formation(anchor,
                List.of(anchor, center, center.relative(firstArm), center.relative(secondArm)),
                null,
                anchor.above(2));
    }

    private Formation createSkrimisherFormation(BlockPos anchor) {
        return new Formation(anchor, List.of(anchor), anchor.above(), anchor.above(2));
    }

    private boolean isClearFormation(ServerLevel level, Formation formation) {
        BlockPos anchor = formation.anchor();
        if (!level.getBlockState(anchor.below()).isFaceSturdy(level, anchor.below(), Direction.UP)) {
            return false;
        }
        for (BlockPos pos : formation.emeraldPositions()) {
            if (!level.getBlockState(pos).isAir()) {
                return false;
            }
        }
        if (formation.chestPos() != null && !level.getBlockState(formation.chestPos()).isAir()) {
            return false;
        }
        return level.getBlockState(formation.pumpkinPos()).isAir();
    }

    /**
     * Takes the four construction blocks from the bank, crafting only the shortfall
     * from raw emeralds when the bank does not already have four block items.
     */
    private boolean withdrawAndCraftEmeraldBlocks(ServerLevel level, int requiredBlocks) {
        if (context == null) {
            failureReason = "emerald withdrawal lost work context";
            return false;
        }

        int storedBlocks = Math.min(requiredBlocks, context.bank().getTotalEmeraldBlockCount());
        int blocksToCraft = requiredBlocks - storedBlocks;

        emeraldBlocks = storedBlocks > 0
                ? context.bank().withdrawExactItem(level, Items.EMERALD_BLOCK, storedBlocks)
                : ItemStack.EMPTY;
        if (storedBlocks > 0 && emeraldBlocks.isEmpty()) {
            failureReason = "bank reported " + storedBlocks
                    + " emerald blocks but exact withdrawal failed";
            return false;
        }

        if (blocksToCraft > 0) {
            emeraldsToCraft = context.bank().withdrawExactItem(level, Items.EMERALD,
                    blocksToCraft * 9);
            if (emeraldsToCraft.isEmpty()) {
                failureReason = "failed to withdraw " + (blocksToCraft * 9)
                        + " raw emeralds to craft the block shortfall";
                return false;
            }

            if (emeraldBlocks.isEmpty()) {
                emeraldBlocks = new ItemStack(Items.EMERALD_BLOCK, blocksToCraft);
            } else {
                emeraldBlocks.grow(blocksToCraft);
            }
            // Crafting consumes the moved raw emeralds and produces the missing blocks.
            emeraldsToCraft = ItemStack.EMPTY;
        }

        if (emeraldBlocks.getCount() != requiredBlocks) {
            failureReason = "construction prepared " + emeraldBlocks.getCount()
                    + " emerald blocks instead of " + requiredBlocks;
            return false;
        }
        return true;
    }

    private boolean withdrawSkrimisherMaterials(ServerLevel level) {
        if (!withdrawAndCraftEmeraldBlocks(level, 1)) {
            return false;
        }
        emeraldChest = context.bank().withdrawExactItem(level, ECAPItems.EMERALD_CHEST.get(), 1);
        if (emeraldChest.isEmpty()) {
            failureReason = "failed to withdraw an emerald chest for Skrimisher construction";
            return false;
        }
        return true;
    }

    private void clearFormation(ServerLevel level, List<BlockPos> emeraldPositions, BlockPos pumpkinPos) {
        for (BlockPos pos : emeraldPositions) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
        level.setBlock(pumpkinPos, Blocks.AIR.defaultBlockState(), 3);
    }

    private void returnUnfinishedMaterials(ServerLevel level) {
        if (context == null) {
            return;
        }
        clearPlacedFormation(level);
        if (!emeraldsToCraft.isEmpty()) {
            returnToBankOrVillager(level, emeraldsToCraft);
            emeraldsToCraft = ItemStack.EMPTY;
        }
        if (!emeraldBlocks.isEmpty()) {
            returnToBankOrVillager(level, emeraldBlocks);
            emeraldBlocks = ItemStack.EMPTY;
        }
        if (!emeraldChest.isEmpty()) {
            returnToBankOrVillager(level, emeraldChest);
            emeraldChest = ItemStack.EMPTY;
        }
        if (!carvedPumpkin.isEmpty()) {
            // A failed/interrupted build returns the original uncarved resource.
            returnToBankOrVillager(level, new ItemStack(Items.PUMPKIN, carvedPumpkin.getCount()));
            carvedPumpkin = ItemStack.EMPTY;
        }
    }

    private ConstructionType selectConstructionType(ServerLevel level, BankBlockEntity bank) {
        if (bank.canBeginGolemConstruction()) {
            return ConstructionType.GOLEM;
        }
        return bank.canBeginSkrimisherConstruction(level)
                ? ConstructionType.SKRIMISHER : ConstructionType.NONE;
    }

    private void returnToBankOrVillager(ServerLevel level, ItemStack stack) {
        ItemStack remainder = stack.copy();
        if (context != null && context.bank().storeItemInLinkedChests(level, remainder)) {
            return;
        }
        remainder = villager.getInventory().addItem(remainder);
        if (!remainder.isEmpty()) {
            villager.spawnAtLocation(remainder);
        }
    }

    private WorkContext resolveContext(ServerLevel level) {
        VillageRecord village = VillageRegistryData.get(level).getVillageFor(villager.blockPosition());
        if (village == null) {
            logDiagnostic(level, "blocked: villager is outside every registered village; pos="
                    + villager.blockPosition());
            return null;
        }

        BlockPos bankPos = VillageRegistryData.get(level).getBankPos(village.getVillageId());
        if (bankPos == null) {
            logDiagnostic(level, "blocked: village " + village.getVillageId()
                    + " has no registered bank position");
            return null;
        }
        if (!(level.getBlockEntity(bankPos) instanceof BankBlockEntity bank)) {
            logDiagnostic(level, "blocked: registered bank block entity is unavailable at " + bankPos);
            return null;
        }

        BlockPos processorPos = bank.getClosestEmeraldProcessorPos();
        if (processorPos == null) {
            logDiagnostic(level, "blocked: bank has no cached Emerald Processor; bank=" + bankPos);
            return null;
        }
        if (!(level.getBlockEntity(processorPos) instanceof EmeraldOreProcessorBlockEntity processor)) {
            logDiagnostic(level, "blocked: cached Emerald Processor block entity is unavailable at "
                    + processorPos);
            return null;
        }
        if (bank.getGolemConstructionPos() == null) {
            logDiagnostic(level, "blocked: bank has no golem construction marker; bank=" + bankPos);
            return null;
        }

        bank.registerEmployeeFromJob(level, villager, processorPos);
        return new WorkContext(bank, processor, processorPos, null, bank.getGolemConstructionPos());
    }

    private void moveToProcessor() {
        BlockPos pos = context.navigationTarget();
        villager.getNavigation().moveTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, SPEED);
        setProcessorWalkTarget();
    }

    private void setProcessorWalkTarget() {
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(context.navigationTarget(), SPEED, 1));
    }

    private boolean isAtProcessor(ServerLevel level) {
        BlockPos pos = context.processorPos();
        return level.getBlockState(pos).is(ECAPBlocks.EMERALD_ORE_PROCESSOR.get())
                && villager.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D,
                pos.getZ() + 0.5D) <= ARRIVAL_DIST_SQ;
    }

    private void logDiagnostic(ServerLevel level, String reason) {
        long gameTime = level.getGameTime();
        if (reason.equals(lastDiagnosticReason) && gameTime < nextDiagnosticTick) {
            return;
        }

        lastDiagnosticReason = reason;
        nextDiagnosticTick = gameTime + DIAGNOSTIC_INTERVAL_TICKS;
        EmeraldCapitalism.LOGGER.debug(
                "[EmeraldsmithGolem] CHECK villager={} uuid={} pos={} tick={} {}",
                villager.getName().getString(), villager.getUUID(), villager.blockPosition(),
                gameTime, reason);
    }

    private enum Stage {
        PROCESSOR,
        CONSTRUCTION
    }

    private enum ConstructionResult {
        IN_PROGRESS,
        SUCCESS,
        FAILED
    }

    private enum ConstructionType {
        NONE,
        GOLEM,
        SKRIMISHER
    }

    private record WorkContext(BankBlockEntity bank, EmeraldOreProcessorBlockEntity processor,
                               BlockPos processorPos, BlockPos navigationTarget, BlockPos constructionPos) {
    }

    private record Formation(BlockPos anchor, List<BlockPos> emeraldPositions,
                             BlockPos chestPos, BlockPos pumpkinPos) {
    }
}
