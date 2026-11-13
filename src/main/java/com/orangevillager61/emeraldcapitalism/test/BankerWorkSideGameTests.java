package com.orangevillager61.emeraldcapitalism.test;

import com.mojang.datafixers.util.Pair;
import com.orangevillager61.emeraldcapitalism.behavior.BankerWorkSideBehavior;
import com.orangevillager61.emeraldcapitalism.behavior.BrainMovementIntent;
import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldChestBlockEntity;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestManager;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillagerPOIRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.VillagerGoalPackages;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Step 3 coverage for banker positioning as a Brain WORK behavior. The three journey tests are
 * natural AI: after the fixture is built, vanilla's Brain, schedule and {@code MoveToTargetSink}
 * do everything. The rest are component tests and are labeled as such.
 */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class BankerWorkSideGameTests {

    static final long WORK_DAY_TIME = 2_500L;
    private static final int ARRIVAL_LIMIT_TICKS = 500;
    private static final int HOLD_TICKS = 400;
    private static final int STEADY_TICKS = 40;

    private BankerWorkSideGameTests() {
    }

    /** A bank registered to its own village, and the banker employed there. */
    record Site(BankBlockEntity bank, BlockPos bankPos, BlockPos workPos, Villager banker) {
    }

    /** Natural AI: one banker walks to the work side and stays through the work day. */
    @GameTest(template = "empty_20x3x20", batch = "ecap_banker_work_side", timeoutTicks = 1_400)
    public static void bankerNaturallyReachesAndHoldsTheWorkSide(GameTestHelper helper) {
        installFloor(helper);
        long previousDayTime = helper.getLevel().getDayTime();
        helper.getLevel().setDayTime(WORK_DAY_TIME);
        Site site = site(helper, new BlockPos(10, 1, 10), new BlockPos(3, 1, 3), 0, 19);
        Journey journey = new Journey(List.of(site), previousDayTime);
        helper.runAfterDelay(1, () -> observe(helper, journey, 0));
    }

    /** Natural AI: two bankers at two banks in two villages each hold their own work side. */
    @GameTest(template = "empty_20x3x20", batch = "ecap_banker_work_side", timeoutTicks = 1_400)
    public static void twoBankersHoldTheirOwnBanksWorkSides(GameTestHelper helper) {
        installFloor(helper);
        long previousDayTime = helper.getLevel().getDayTime();
        helper.getLevel().setDayTime(WORK_DAY_TIME);
        Site first = site(helper, new BlockPos(4, 1, 5), new BlockPos(2, 1, 15), 0, 9);
        Site second = site(helper, new BlockPos(15, 1, 5), new BlockPos(17, 1, 15), 10, 19);
        helper.assertFalse(second.bank().isEmployee(first.banker().getUUID()),
                "the second village's bank claimed the first village's banker");
        helper.assertFalse(first.bank().isEmployee(second.banker().getUUID()),
                "the first village's bank claimed the second village's banker");
        Journey journey = new Journey(List.of(first, second), previousDayTime);
        helper.runAfterDelay(1, () -> observe(helper, journey, 0));
    }

    /** Natural AI: the banker places a prepared replacement chest once it stands at the work side. */
    @GameTest(template = "empty_20x3x20", batch = "ecap_banker_work_side", timeoutTicks = 900)
    public static void bankerPlacesPreparedChestAfterReachingTheWorkSide(GameTestHelper helper) {
        installFloor(helper);
        ServerLevel level = helper.getLevel();
        long previousDayTime = level.getDayTime();
        BlockPos bankLocal = new BlockPos(10, 1, 10);
        BlockPos bankPos = helper.absolutePos(bankLocal);
        helper.setBlock(bankLocal.south(), ECAPBlocks.EMERALD_CHEST.get().defaultBlockState());
        helper.setBlock(bankLocal.north(), ECAPBlocks.EMERALD_CHEST.get().defaultBlockState());
        placeBank(helper, bankLocal);
        BankBlockEntity bank = (BankBlockEntity) level.getBlockEntity(bankPos);
        EmeraldChestBlockEntity storage =
                (EmeraldChestBlockEntity) level.getBlockEntity(helper.absolutePos(bankLocal.south()));
        BlockPos missingPos = helper.absolutePos(bankLocal.north());
        if (bank == null || storage == null) {
            helper.fail("bank or storage chest block entity was not created");
            return;
        }
        storage.setItem(0, new ItemStack(Items.CHEST, 2));
        storage.setItem(1, new ItemStack(Items.EMERALD, 16));
        BankBlockEntity.serverTick(level, bankPos, level.getBlockState(bankPos), bank);
        level.setBlock(missingPos, Blocks.AIR.defaultBlockState(), 3);
        BankBlockEntity.markChestCachesDirtyNear(level, missingPos);
        BankBlockEntity.serverTick(level, bankPos, level.getBlockState(bankPos), bank);
        helper.assertValueEqual(bank.craftMissingEmeraldChests(level), 1,
                "the emeraldsmith repair path did not prepare a replacement chest");

        level.setDayTime(WORK_DAY_TIME);
        Site site = employ(helper, bank, bankPos, new BlockPos(3, 1, 3), 0, 19);
        helper.assertValueEqual(bank.getMissingChestCount(level), 1,
                "the fixture should still be missing its chest before the banker arrives");
        awaitRepair(helper, site, previousDayTime, ARRIVAL_LIMIT_TICKS + 100);
    }

    private static void awaitRepair(GameTestHelper helper, Site site, long previousDayTime, int remaining) {
        ServerLevel level = helper.getLevel();
        BlockPos missingPos = helper.absolutePos(new BlockPos(10, 1, 9));
        if (site.bank().getMissingChestCount(level) == 0) {
            helper.assertValueEqual(site.bank().getPreparedEmeraldChestCount(), 0,
                    "the banker did not consume the prepared chest exactly once");
            helper.assertTrue(level.getBlockState(missingPos).is(ECAPBlocks.EMERALD_CHEST.get()),
                    "the banker did not place a chest at the tracked location");
            helper.assertTrue(BankBlock.isAtBankerWorkPos(
                            level.getBlockState(site.bankPos()), site.bankPos(), site.banker().position()),
                    "the chest was repaired while the banker was away from the work side");
            level.setDayTime(previousDayTime);
            helper.succeed();
            return;
        }
        if (remaining <= 0) {
            level.setDayTime(previousDayTime);
            helper.fail("the banker did not repair the missing chest from the work side: pos="
                    + site.banker().position() + ", missing=" + site.bank().getMissingChestCount(level));
            return;
        }
        helper.runAfterDelay(1, () -> awaitRepair(helper, site, previousDayTime, remaining - 1));
    }

    /**
     * Natural AI: an unemployed villager with the bank as its potential job site walks to the
     * work side by itself and becomes a banker. It must not stop a block short and wait to be
     * pushed (the work-side check needs the villager at the work position).
     */
    @GameTest(template = "empty_20x3x20", batch = "ecap_banker_work_side", timeoutTicks = 1_400)
    public static void unemployedVillagerReachesTheBankWorkSideAndBecomesABankerUnaided(GameTestHelper helper) {
        installFloor(helper);
        ServerLevel level = helper.getLevel();
        long previousDayTime = level.getDayTime();
        level.setDayTime(WORK_DAY_TIME);
        BlockPos bankLocal = new BlockPos(10, 1, 10);
        placeBank(helper, bankLocal);
        BlockPos bankPos = helper.absolutePos(bankLocal);
        BankBlockEntity bank = (BankBlockEntity) level.getBlockEntity(bankPos);
        if (bank == null) {
            helper.fail("bank block entity was not created");
            return;
        }
        UUID villageId = UUID.randomUUID();
        BlockPos min = helper.absolutePos(new BlockPos(0, -1, 0));
        BlockPos max = helper.absolutePos(new BlockPos(20, 5, 20));
        VillageRegistryData registry = VillageRegistryData.get(level);
        VillageRecord village = registry.getOrCreateVillage(villageId, bankPos,
                new AABB(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()));
        registry.registerBankPosition(villageId, bankPos);
        bank.setVillageId(villageId);
        Villager candidate = helper.spawn(EntityType.VILLAGER, 3, 1, 10);
        // Fixture setup: acquisition is vanilla's AcquirePoi and is not what is under test; walking
        // to the work side and assignment are. On 1.21.4 this memory is gone one tick after setup
        // for a reason not yet found, so this test is an expected failure there (see the baseline).
        candidate.getBrain().setMemory(MemoryModuleType.POTENTIAL_JOB_SITE,
                GlobalPos.of(level.dimension(), bankPos));
        village.addMember(new VillagerPOIRecord(
                candidate.getUUID(), candidate.getName().getString(), VillagerProfession.NONE.name(),
                null, null, null, candidate.getHealth(), VillagerPOIRecord.Status.ACTIVE, 0,
                level.getGameTime()));
        awaitBanker(helper, candidate, previousDayTime, 1_200);
    }

    private static void awaitBanker(GameTestHelper helper, Villager candidate, long previousDayTime,
                                    int remaining) {
        ServerLevel level = helper.getLevel();
        if (candidate.getVillagerData().getProfession() == ECAPVillagerProfessions.BANKER.get()) {
            level.setDayTime(previousDayTime);
            helper.succeed();
            return;
        }
        if (remaining <= 0) {
            level.setDayTime(previousDayTime);
            helper.fail("the bank candidate did not become a banker without help: pos=" + candidate.position()
                    + ", potentialJobSite=" + candidate.getBrain()
                    .getMemory(MemoryModuleType.POTENTIAL_JOB_SITE).orElse(null)
                    + ", walkTarget=" + candidate.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null)
                    + ", activity=" + candidate.getBrain().getActiveNonCoreActivity().orElse(null)
                    + ", profession=" + candidate.getVillagerData().getProfession()
                    + ", bankPoi=" + level.getPoiManager().getType(helper.absolutePos(new BlockPos(10, 1, 10)))
                    + ", dayTime=" + level.getDayTime());
            return;
        }
        helper.runAfterDelay(1, () -> awaitBanker(helper, candidate, previousDayTime, remaining - 1));
    }

    /**
     * Component, documenting current design: a bank a player places is player-controlled, and a
     * player-controlled bank is not a banker job site, so no villager is sent to staff it until
     * the controller releases it from the bank screen.
     */
    @GameTest(template = "empty_3x3x3")
    public static void playerPlacedBankIsControlledAndOffersNoBankerJobUntilReleased(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos local = new BlockPos(1, 1, 1);
        BlockPos bankPos = helper.absolutePos(local);
        helper.setBlock(local, ECAPBlocks.BANK.get().defaultBlockState());
        net.minecraft.world.entity.player.Player player =
                helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        BlockState placed = level.getBlockState(bankPos);
        helper.assertTrue(placed.getValue(BankBlock.BANKER_AVAILABLE),
                "control: a bank that nobody placed is a banker job site");
        helper.assertTrue(isBankerPoi(level, bankPos), "control: the unplaced bank is not a banker POI");

        ECAPBlocks.BANK.get().setPlacedBy(level, bankPos, placed, player,
                new net.minecraft.world.item.ItemStack(ECAPBlocks.BANK.get()));
        BankBlockEntity bank = (BankBlockEntity) level.getBlockEntity(bankPos);
        helper.assertTrue(bank != null && bank.isControlledBy(player.getUUID()),
                "the placing player did not become the bank's controller");
        helper.assertFalse(level.getBlockState(bankPos).getValue(BankBlock.BANKER_AVAILABLE),
                "a player-controlled bank still offers the banker job");
        helper.assertFalse(isBankerPoi(level, bankPos), "a player-controlled bank is still a banker POI");

        bank.setController(null);
        helper.assertTrue(level.getBlockState(bankPos).getValue(BankBlock.BANKER_AVAILABLE),
                "releasing control did not restore the banker job");
        helper.assertTrue(isBankerPoi(level, bankPos), "a released bank is not a banker POI again");
        helper.succeed();
    }

    private static boolean isBankerPoi(ServerLevel level, BlockPos pos) {
        return level.getPoiManager().getType(pos)
                .map(type -> type.is(com.orangevillager61.emeraldcapitalism.registry.ECAPPoiTypes.BANKER.getKey()))
                .orElse(false);
    }

    /** Component: the banker's package drops vanilla's job-site strolling and other jobs keep it. */
    @GameTest(template = "empty_3x3x3")
    public static void bankerWorkPackageReplacesVanillaSiteStrollingOnly(GameTestHelper helper) {
        List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> banker =
                VillagerGoalPackages.getWorkPackage(ECAPVillagerProfessions.BANKER.get(), 0.5F);
        helper.assertValueEqual(count(banker, BankerWorkSideBehavior.class), 1,
                "the banker's work package must hold exactly one work-side behavior");
        helper.assertValueEqual(count(banker, net.minecraft.world.entity.ai.behavior.WorkAtPoi.class), 1,
                "the banker lost vanilla work-at-poi");
        helper.assertValueEqual(countNamed(banker, "RunOne"), 1,
                "the banker must keep only vanilla's look-around gate, not the job-site strolling gate");
        helper.assertValueEqual(banker.size(), 9,
                "the banker's package no longer holds the vanilla entries, begging and the work side");
        for (VillagerProfession other : List.of(VillagerProfession.FARMER, VillagerProfession.LIBRARIAN,
                VillagerProfession.NONE)) {
            List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> work =
                    VillagerGoalPackages.getWorkPackage(other, 0.5F);
            helper.assertValueEqual(count(work, BankerWorkSideBehavior.class), 0,
                    "a non-banker work package received the banker behavior: " + other);
            helper.assertValueEqual(countNamed(work, "RunOne"), 2,
                    "a non-banker work package lost a vanilla gate: " + other);
        }
        helper.succeed();
    }

    /**
     * Component, and the legacy-versus-Brain guard (working rule 6): a banker that joined the
     * world through the production injection has no mod goal moving it.
     */
    @GameTest(template = "empty_3x3x3")
    public static void bankerPositioningRunsOnlyOnTheBrain(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        villager.setVillagerData(villager.getVillagerData().setProfession(ECAPVillagerProfessions.BANKER.get()));
        villager.refreshBrain(helper.getLevel());
        boolean legacyGoal = villager.goalSelector.getAvailableGoals().stream()
                .anyMatch(goal -> goal.getGoal().getClass().getSimpleName().contains("Banker"));
        helper.assertFalse(legacyGoal, "a banker goal is still registered on the GoalSelector");
        helper.succeed();
    }

    /** Component: the behavior waits for another behavior's walk target and yields to a newer one. */
    @GameTest(template = "empty_20x3x20", batch = "ecap_banker_work_side")
    public static void bankerWorkSideWaitsForAndYieldsToAnotherWalkTarget(GameTestHelper helper) {
        installFloor(helper);
        ServerLevel level = helper.getLevel();
        Site site = site(helper, new BlockPos(10, 1, 10), new BlockPos(3, 1, 3), 0, 19);
        Villager villager = site.banker();
        villager.setNoAi(true);
        // The behavior ends itself outside the WORK activity (the Brain does not stop it).
        villager.getBrain().setActiveActivityIfPossible(net.minecraft.world.entity.schedule.Activity.WORK);
        Behavior<Villager> behavior = new BankerWorkSideBehavior();
        long now = level.getGameTime();

        WalkTarget other = new WalkTarget(helper.absolutePos(new BlockPos(2, 1, 2)), 0.6F, 1);
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, other);
        helper.assertFalse(behavior.tryStart(level, villager, now),
                "the work-side behavior started over another behavior's walk target");
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        helper.assertTrue(behavior.tryStart(level, villager, now + 1),
                "the work-side behavior did not start for an employed banker");
        behavior.tickOrStop(level, villager, now + 2);
        WalkTarget ours = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        helper.assertTrue(ours != null && "emeraldcapitalism:banker_work_side"
                        .equals(BrainMovementIntent.ownerOf(ours)),
                "the work-side behavior did not publish its own walk target");
        helper.assertValueEqual(ours.getTarget().currentBlockPosition(), site.workPos(),
                "the banker was sent somewhere other than the bank's work side");
        helper.assertTrue(MovementRequestManager.activeHandle(villager) == null,
                "the work-side behavior used MovementRequestManager");

        WalkTarget newer = new WalkTarget(helper.absolutePos(new BlockPos(18, 1, 18)), 0.6F, 1);
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, newer);
        behavior.tickOrStop(level, villager, now + 3);
        helper.assertTrue(behavior.getStatus() == Behavior.Status.STOPPED,
                "the work-side behavior kept running over a newer walk target");
        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null) == newer,
                "stopping the work-side behavior erased a newer walk target");
        helper.succeed();
    }

    private static void observe(GameTestHelper helper, Journey journey, int tick) {
        ServerLevel level = helper.getLevel();
        try {
            boolean allHolding = true;
            for (Site site : journey.sites()) {
                helper.assertTrue(MovementRequestManager.activeHandle(site.banker()) == null,
                        "banker positioning went through MovementRequestManager");
                boolean atWork = BankBlock.isAtBankerWorkPos(
                        level.getBlockState(site.bankPos()), site.bankPos(), site.banker().position());
                if (journey.arrivedAt(site) < 0) {
                    // A fresh Brain starts in IDLE until its first schedule update and the Brain does
                    // not stop vanilla's leftover idle behavior, so a pass-by does not count: the
                    // banker must be working at the work side for 40 ticks in a row.
                    boolean working = atWork && site.banker().getBrain()
                            .isActive(net.minecraft.world.entity.schedule.Activity.WORK);
                    if (journey.steady(site, working) >= STEADY_TICKS) {
                        journey.markArrived(site, tick);
                    }
                    allHolding = false;
                } else if (!atWork) {
                    helper.fail("a banker left the work side " + (tick - journey.arrivedAt(site))
                            + " ticks after arriving: pos=" + site.banker().position()
                            + ", walkTarget=" + site.banker().getBrain()
                            .getMemory(MemoryModuleType.WALK_TARGET).orElse(null));
                    level.setDayTime(journey.previousDayTime());
                    return;
                } else if (tick - journey.arrivedAt(site) < HOLD_TICKS) {
                    allHolding = false;
                }
            }
            if (allHolding) {
                level.setDayTime(journey.previousDayTime());
                helper.succeed();
                return;
            }
            if (tick > ARRIVAL_LIMIT_TICKS + HOLD_TICKS) {
                level.setDayTime(journey.previousDayTime());
                helper.fail("a banker did not reach and hold the work side: "
                        + journey.sites().stream().map(site -> site.banker().position() + " activity="
                                + site.banker().getBrain().getActiveNonCoreActivity().orElse(null)
                                + " arrivedAt=" + journey.arrivedAt(site) + " dayTime=" + level.getDayTime()
                                + " walkTarget=" + site.banker().getBrain()
                                .getMemory(MemoryModuleType.WALK_TARGET).isPresent()).toList());
                return;
            }
            helper.runAfterDelay(1, () -> observe(helper, journey, tick + 1));
        } catch (RuntimeException | Error failure) {
            level.setDayTime(journey.previousDayTime());
            throw failure;
        }
    }

    static Site site(GameTestHelper helper, BlockPos bankLocal, BlockPos bankerLocal,
                             int minX, int maxX) {
        placeBank(helper, bankLocal);
        ServerLevel level = helper.getLevel();
        BlockPos bankPos = helper.absolutePos(bankLocal);
        BankBlockEntity bank = (BankBlockEntity) level.getBlockEntity(bankPos);
        if (bank == null) {
            throw new IllegalStateException("bank block entity was not created");
        }
        return employ(helper, bank, bankPos, bankerLocal, minX, maxX);
    }

    /** Fixture setup only: the village, its bank, and an employed banker with an ordinary Brain. */
    static Site employ(GameTestHelper helper, BankBlockEntity bank, BlockPos bankPos,
                               BlockPos bankerLocal, int minX, int maxX) {
        ServerLevel level = helper.getLevel();
        UUID villageId = UUID.randomUUID();
        VillageRegistryData registry = VillageRegistryData.get(level);
        BlockPos min = helper.absolutePos(new BlockPos(minX, -1, 0));
        BlockPos max = helper.absolutePos(new BlockPos(maxX + 1, 5, 20));
        VillageRecord village = registry.getOrCreateVillage(villageId, bankPos,
                new AABB(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()));
        registry.registerBankPosition(villageId, bankPos);
        bank.setVillageId(villageId);

        Villager banker = helper.spawn(EntityType.VILLAGER, bankerLocal.getX(), bankerLocal.getY(),
                bankerLocal.getZ());
        banker.setVillagerData(banker.getVillagerData().setProfession(ECAPVillagerProfessions.BANKER.get()));
        // Vanilla rebuilds the Brain on a profession change; the XP keeps it from resetting.
        banker.setVillagerXp(1);
        banker.refreshBrain(level);
        banker.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), bankPos));
        village.addMember(new VillagerPOIRecord(
                banker.getUUID(), banker.getName().getString(),
                ECAPVillagerProfessions.BANKER.get().name(), null, null, null, banker.getHealth(),
                VillagerPOIRecord.Status.ACTIVE, 0, level.getGameTime()));
        if (!bank.registerEmployeeFromJob(level, banker, bankPos)) {
            throw new IllegalStateException("the banker was not registered as the bank's employee");
        }
        BlockPos workPos = BankBlock.getBankerWorkPos(level.getBlockState(bankPos), bankPos);
        return new Site(bank, bankPos, workPos, banker);
    }

    static void placeBank(GameTestHelper helper, BlockPos bankLocal) {
        helper.setBlock(bankLocal, ECAPBlocks.BANK.get().defaultBlockState()
                .setValue(BankBlock.FACING, Direction.EAST));
    }

    private static int count(List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> entries,
                             Class<?> type) {
        return (int) entries.stream().filter(entry -> entry.getSecond().getClass() == type).count();
    }

    private static int countNamed(List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> entries,
                                  String simpleName) {
        return (int) entries.stream()
                .filter(entry -> entry.getSecond().getClass().getSimpleName().equals(simpleName)).count();
    }

    static void installFloor(GameTestHelper helper) {
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
    }

    /** Tracks when each banker first stood at its work side. */
    private static final class Journey {
        private final List<Site> sites;
        private final long previousDayTime;
        private final int[] arrivals;
        private final int[] steadyTicks;

        private Journey(List<Site> sites, long previousDayTime) {
            this.sites = sites;
            this.previousDayTime = previousDayTime;
            this.arrivals = new int[sites.size()];
            this.steadyTicks = new int[sites.size()];
            java.util.Arrays.fill(arrivals, -1);
        }

        private List<Site> sites() {
            return sites;
        }

        private long previousDayTime() {
            return previousDayTime;
        }

        /** Counts consecutive ticks {@code working} has held for {@code site}. */
        private int steady(Site site, boolean working) {
            int index = sites.indexOf(site);
            steadyTicks[index] = working ? steadyTicks[index] + 1 : 0;
            return steadyTicks[index];
        }

        private int arrivedAt(Site site) {
            return arrivals[sites.indexOf(site)];
        }

        private void markArrived(Site site, int tick) {
            arrivals[sites.indexOf(site)] = tick;
        }
    }
}
