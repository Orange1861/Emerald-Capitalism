package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.world.village.VillageLibraryStructurePlacer;
import com.orangevillager61.emeraldcapitalism.world.village.VillageTerrain;
import com.orangevillager61.emeraldcapitalism.world.village.VillageTerrainSnapshot;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.FarmPlacement;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.VillageFarmSiteSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Site searches that run on the planning worker read a terrain snapshot from
 * another thread. They must choose exactly what the live-level search chooses.
 */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class WorkerPlanningEquivalenceGameTests {
    private static final int REACH = 96;

    private WorkerPlanningEquivalenceGameTests() {
    }

    @GameTest(template = "empty_3x3x3", batch = "ecap_worker_equivalence")
    public static void librarySiteFromSnapshotMatchesLiveLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos bell = helper.absolutePos(new BlockPos(1, 1, 1));
        List<int[]> forced = forceChunks(level, bell);
        try {
            VillageTerrainSnapshot snapshot = capture(level, bell);
            VillageLibraryStructurePlacer placer = new VillageLibraryStructurePlacer();
            VillageLibraryStructurePlacer.SiteSearch search = placer.prepareSiteSearch(level, "PLAINS");
            helper.assertTrue(search != null, "plains library template is missing");
            VillageLibraryStructurePlacer.PlannedLibrary live = placer.findSite(
                    VillageTerrain.live(level), search, bell, List.of(), box -> false);
            VillageLibraryStructurePlacer.PlannedLibrary fromSnapshot = CompletableFuture
                    .supplyAsync(() -> placer.findSite(snapshot, search, bell, List.of(), box -> false))
                    .join();
            helper.assertTrue(live != null, "live library search found no site on flat ground");
            helper.assertTrue(live.equals(fromSnapshot),
                    "snapshot library site " + fromSnapshot + " differs from live " + live);
            helper.succeed();
        } finally {
            releaseChunks(level, forced);
        }
    }

    @GameTest(template = "empty_3x3x3", batch = "ecap_worker_equivalence")
    public static void farmSitesFromSnapshotMatchLiveLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(1, 1, 1));
        List<int[]> forced = forceChunks(level, center);
        try {
            VillageTerrainSnapshot snapshot = capture(level, center);
            BoundingBox villageBox = new BoundingBox(center.getX() - 40, center.getY() - 8, center.getZ() - 40,
                    center.getX() + 40, center.getY() + 16, center.getZ() + 40);
            VillageFarmSiteSelector selector = new VillageFarmSiteSelector();
            VillageFarmSiteSelector.SiteSearch search = selector.prepareSiteSearch(level, center, "PLAINS");
            helper.assertTrue(search != null, "plains farm templates are missing");
            // Each search fills its own suitability cache, so neither reuses the other's answers.
            List<FarmPlacement> live = selector.findSites(VillageTerrain.live(level), search, center, "PLAINS",
                    villageBox, List.of(), selector.buildSpatialCache(level, center, villageBox, List.of()));
            VillageFarmSiteSelector.VillageSpatialCache workerCache =
                    selector.buildSpatialCache(level, center, villageBox, List.of());
            List<FarmPlacement> fromSnapshot = CompletableFuture
                    .supplyAsync(() -> selector.findSites(snapshot, search, center, "PLAINS",
                            villageBox, List.of(), workerCache))
                    .join();
            helper.assertTrue(!live.isEmpty(), "live farm search found no site on flat ground");
            helper.assertTrue(live.equals(fromSnapshot),
                    "snapshot farm sites " + fromSnapshot + " differ from live " + live);
            helper.succeed();
        } finally {
            releaseChunks(level, forced);
        }
    }

    private static VillageTerrainSnapshot capture(ServerLevel level, BlockPos center) {
        VillageTerrainSnapshot snapshot = new VillageTerrainSnapshot(level,
                (center.getX() - REACH) >> 4, (center.getX() + REACH) >> 4,
                (center.getZ() - REACH) >> 4, (center.getZ() + REACH) >> 4);
        while (!snapshot.captureStep(level, 64)) {
            // Capture every chunk now; the pipeline spreads this over ticks.
        }
        return snapshot;
    }

    private static List<int[]> forceChunks(ServerLevel level, BlockPos center) {
        List<int[]> chunks = new ArrayList<>();
        for (int chunkX = (center.getX() - REACH) >> 4; chunkX <= (center.getX() + REACH) >> 4; chunkX++) {
            for (int chunkZ = (center.getZ() - REACH) >> 4; chunkZ <= (center.getZ() + REACH) >> 4; chunkZ++) {
                if (level.setChunkForced(chunkX, chunkZ, true)) {
                    chunks.add(new int[] {chunkX, chunkZ});
                }
            }
        }
        return chunks;
    }

    private static void releaseChunks(ServerLevel level, List<int[]> chunks) {
        for (int[] chunk : chunks) {
            level.setChunkForced(chunk[0], chunk[1], false);
        }
    }
}
