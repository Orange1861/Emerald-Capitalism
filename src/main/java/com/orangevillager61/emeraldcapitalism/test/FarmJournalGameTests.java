package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.ChunkLoadBudget;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.FarmPlacement;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.JournaledFarmTask;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.VillageFarmPlacer;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.VillageFarmSavedData;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.VillageFarmSiteSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * C1: farm placement is safe across a stop or crash. The interruption test stops a real incremental
 * farm placement after every step, as a crash would, and replays the same planned site; the
 * result must match an uninterrupted run. The journal tests cover failure bookkeeping and reload.
 */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class FarmJournalGameTests {

    private static final ResourceLocation TEMPLATE =
            ResourceLocation.parse("minecraft:village/plains/houses/plains_small_farm_1");
    private static final ResourceLocation PROCESSORS = ResourceLocation.parse("minecraft:farm_plains");
    private static final int MAX_STEPS = 2_000;
    private static final int ARENA_ORIGIN = 4;

    private FarmJournalGameTests() {
    }

    /** The planned farm, the terrain it is placed on, and what to measure afterwards. */
    private record Site(FarmPlacement placement, int floorY, int templateHeight, BlockPos center) {
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_farm_journal", timeoutTicks = 400)
    public static void interruptedFarmPlacementReplaysToTheSameFarmAtEveryStep(GameTestHelper helper) {
        Site site = site(helper);
        ServerLevel level = helper.getLevel();

        resetTerrain(helper, site);
        VillageFarmPlacer.FarmPlacementTask reference = newTask(level, site);
        Set<String> phasesRun = new LinkedHashSet<>();
        Map<String, Double> worstMillisByPhase = new TreeMap<>();
        int steps = 0;
        while (true) {
            String phase = reference.phaseName();
            phasesRun.add(phase);
            long started = System.nanoTime();
            boolean done = reference.processStep();
            worstMillisByPhase.merge(phase, (System.nanoTime() - started) / 1.0E6D, Math::max);
            steps++;
            if (done || steps > MAX_STEPS) {
                break;
            }
        }
        helper.assertTrue(reference.succeeded(), "the uninterrupted reference farm did not place: phase "
                + reference.phaseName());
        Map<String, Integer> expected = blockCounts(level, site);
        helper.assertTrue(expected.getOrDefault("minecraft:farmland", 0) > 0,
                "the reference farm has no farmland, so the comparison would prove nothing");
        EmeraldCapitalism.LOGGER.info("[ECAP][FarmJournal] reference farm: {} steps, phases {}, worst step ms {}",
                steps, phasesRun, worstMillisByPhase);

        Set<String> interruptedIn = new LinkedHashSet<>();
        for (int stopAfter = 0; stopAfter < steps; stopAfter++) {
            resetTerrain(helper, site);
            VillageFarmPlacer.FarmPlacementTask interrupted = newTask(level, site);
            for (int step = 0; step < stopAfter; step++) {
                interrupted.processStep();
            }
            interruptedIn.add(interrupted.phaseName());
            // A crash drops the task. The journal's replay builds a fresh task for the same site.
            VillageFarmPlacer.FarmPlacementTask replay = newTask(level, site);
            int replaySteps = 0;
            while (!replay.processStep() && ++replaySteps < MAX_STEPS) {
                // Runs to completion.
            }
            helper.assertTrue(replay.succeeded(), "the replay failed after stopping the first run after step "
                    + stopAfter + " in phase " + interrupted.phaseName() + ": replay phase " + replay.phaseName());
            Map<String, Integer> actual = blockCounts(level, site);
            helper.assertTrue(actual.equals(expected), "the replayed farm differs from the uninterrupted farm after "
                    + "stopping after step " + stopAfter + " in phase " + interrupted.phaseName() + ": "
                    + difference(expected, actual));
        }
        Set<String> missed = new LinkedHashSet<>(phasesRun);
        missed.removeAll(interruptedIn);
        missed.remove("COMPLETE");
        helper.assertTrue(missed.isEmpty(), "no interruption landed in phases " + missed);
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_farm_journal", timeoutTicks = 200)
    public static void interruptedFarmStaysPendingInTheJournalUntilItsReplayCompletes(GameTestHelper helper) {
        Site site = site(helper);
        ServerLevel level = helper.getLevel();
        resetTerrain(helper, site);
        VillageFarmSavedData journal = new VillageFarmSavedData();

        JournaledFarmTask first = JournaledFarmTask.begin(journal, site.center(), List.of(site.placement()),
                site.placement(), newTask(level, site));
        for (int step = 0; step < 6; step++) {
            first.processStep();
        }
        helper.assertFalse(journal.farmsResolved(site.center()), "an interrupted farm was counted as resolved");
        helper.assertValueEqual(journal.farmsToPlace(site.center()), List.of(site.placement()),
                "the interrupted farm is not waiting in the journal");

        // Stop and restart: the journal is saved and loaded, the task is gone.
        CompoundTag saved = journal.save(new CompoundTag(), level.registryAccess());
        VillageFarmSavedData restored = VillageFarmSavedData.load(saved, level.registryAccess());
        helper.assertValueEqual(restored.farmsToPlace(site.center()), List.of(site.placement()),
                "the pending farm did not survive a save and reload");

        JournaledFarmTask replay = JournaledFarmTask.begin(restored, site.center(), restored.farmsToPlace(site.center()),
                restored.farmsToPlace(site.center()).get(0), newTask(level, site));
        int steps = 0;
        while (!replay.processStep() && ++steps < MAX_STEPS) {
            // Runs to completion.
        }
        helper.assertTrue(replay.succeeded(), "the replayed farm did not place");
        helper.assertTrue(restored.farmsResolved(site.center()), "a completed replay left the farm pending");
        helper.assertValueEqual(restored.unfinishedFarmCount(site.center()), 0,
                "a completed replay left an unfinished farm");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_farm_journal", timeoutTicks = 200)
    public static void failedFarmPlacementIsRetriedThenGivenUp(GameTestHelper helper) {
        Site site = site(helper);
        ServerLevel level = helper.getLevel();
        VillageFarmPlacer placer = new VillageFarmPlacer();
        FarmPlacement unplaceable = new FarmPlacement(site.placement().origin(),
                ResourceLocation.parse("emeraldcapitalism:no_such_farm_template"), PROCESSORS,
                Rotation.NONE, 8, 8);
        VillageFarmSavedData journal = new VillageFarmSavedData();
        VillageFarmSiteSelector selector = new VillageFarmSiteSelector();
        VillageFarmSiteSelector.VillageSpatialCache cache = selector.buildSpatialCache(
                level, site.center(), arena(helper), List.of());

        for (int attempt = 1; attempt <= VillageFarmSavedData.MAX_PLACEMENT_ATTEMPTS; attempt++) {
            helper.assertFalse(journal.hasFarmJournal(site.center()) && journal.farmsResolved(site.center()),
                    "the farm was already resolved before attempt " + attempt);
            JournaledFarmTask task = JournaledFarmTask.begin(journal, site.center(), List.of(unplaceable), unplaceable,
                    placer.createPlacementTask(level, unplaceable, "PLAINS", new ChunkLoadBudget(), cache));
            int steps = 0;
            while (!task.processStep() && ++steps < MAX_STEPS) {
                // Runs to its failure.
            }
            helper.assertFalse(task.succeeded(), "a farm with no template placed");
            if (attempt < VillageFarmSavedData.MAX_PLACEMENT_ATTEMPTS) {
                helper.assertFalse(journal.farmsResolved(site.center()),
                        "a failed farm was treated as resolved after attempt " + attempt);
                helper.assertValueEqual(journal.farmsToPlace(site.center()), List.of(unplaceable),
                        "the failed farm is not waiting for a retry after attempt " + attempt);
            }
        }
        helper.assertTrue(journal.farmsResolved(site.center()), "the village was never resolved after the attempt limit");
        helper.assertTrue(journal.farmsToPlace(site.center()).isEmpty(), "an exhausted farm is still queued");
        helper.assertValueEqual(journal.unfinishedFarmCount(site.center()), 1,
                "the given-up farm is not reported as unfinished");
        helper.succeed();
    }

    private static Site site(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StructureTemplate template = level.getStructureManager().get(TEMPLATE).orElse(null);
        if (template == null) {
            helper.fail("the plains farm template is not available");
            throw new IllegalStateException("template missing");
        }
        int footprintX = template.getSize().getX();
        int footprintZ = template.getSize().getZ();
        if (ARENA_ORIGIN + footprintX + 4 > 20 || ARENA_ORIGIN + footprintZ + 4 > 20) {
            helper.fail("the farm template does not fit the test arena: " + template.getSize());
            throw new IllegalStateException("template too large");
        }
        BlockPos floor = helper.absolutePos(new BlockPos(ARENA_ORIGIN, 0, ARENA_ORIGIN));
        FarmPlacement placement = new FarmPlacement(floor, TEMPLATE, PROCESSORS, Rotation.NONE,
                footprintX, footprintZ);
        BlockPos center = helper.absolutePos(new BlockPos(10, 0, 10));
        return new Site(placement, floor.getY(), template.getSize().getY(), center);
    }

    private static VillageFarmPlacer.FarmPlacementTask newTask(ServerLevel level, Site site) {
        VillageFarmSiteSelector selector = new VillageFarmSiteSelector();
        BoundingBox arena = new BoundingBox(site.placement().origin().getX() - ARENA_ORIGIN, site.floorY() - 8,
                site.placement().origin().getZ() - ARENA_ORIGIN,
                site.placement().origin().getX() - ARENA_ORIGIN + 19, site.floorY() + 40,
                site.placement().origin().getZ() - ARENA_ORIGIN + 19);
        VillageFarmSiteSelector.VillageSpatialCache cache =
                selector.buildSpatialCache(level, site.center(), arena, List.of());
        return new VillageFarmPlacer().createPlacementTask(level, site.placement(), "PLAINS",
                new ChunkLoadBudget(), cache);
    }

    private static BoundingBox arena(GameTestHelper helper) {
        BlockPos min = helper.absolutePos(BlockPos.ZERO);
        return new BoundingBox(min.getX(), min.getY() - 8, min.getZ(), min.getX() + 19, min.getY() + 40, min.getZ() + 19);
    }

    /** Uneven stone terrain, the same every time, so the leveling phase has work to do. */
    private static void resetTerrain(GameTestHelper helper, Site site) {
        ServerLevel level = helper.getLevel();
        FarmPlacement placement = site.placement();
        int minX = placement.origin().getX() - 4;
        int minZ = placement.origin().getZ() - 4;
        for (int x = minX; x < minX + placement.footprintX() + 8; x++) {
            for (int z = minZ; z < minZ + placement.footprintZ() + 8; z++) {
                int height = site.floorY() + Math.floorMod(x * 3 + z * 5, 3);
                for (int y = site.floorY() - 3; y <= site.floorY() + site.templateHeight() + 14; y++) {
                    level.setBlock(new BlockPos(x, y, z),
                            y <= height ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    /** Block counts over the farm and its margin, ignoring crops whose type the processors pick per column. */
    private static Map<String, Integer> blockCounts(ServerLevel level, Site site) {
        FarmPlacement placement = site.placement();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int x = placement.origin().getX() - 2; x < placement.origin().getX() + placement.footprintX() + 2; x++) {
            for (int z = placement.origin().getZ() - 2; z < placement.origin().getZ() + placement.footprintZ() + 2; z++) {
                for (int y = site.floorY() - 2; y <= site.floorY() + site.templateHeight() + 14; y++) {
                    Block block = level.getBlockState(new BlockPos(x, y, z)).getBlock();
                    if (block == Blocks.AIR || block instanceof CropBlock) {
                        continue;
                    }
                    counts.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString(), 1,
                            Integer::sum);
                }
            }
        }
        return new TreeMap<>(counts);
    }

    private static String difference(Map<String, Integer> expected, Map<String, Integer> actual) {
        StringBuilder text = new StringBuilder();
        Set<String> keys = new LinkedHashSet<>(expected.keySet());
        keys.addAll(actual.keySet());
        for (String key : keys) {
            int want = expected.getOrDefault(key, 0);
            int got = actual.getOrDefault(key, 0);
            if (want != got) {
                text.append(key).append(" expected ").append(want).append(" got ").append(got).append("; ");
            }
        }
        return text.toString();
    }
}
