package com.orangevillager61.emeraldcapitalism.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.orangevillager61.emeraldcapitalism.network.VillagePOIClientCache;
import com.orangevillager61.emeraldcapitalism.world.village.JobSiteEntry;
import com.orangevillager61.emeraldcapitalism.world.village.VillagerPOIRecord;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import com.orangevillager61.emeraldcapitalism.util.RenderLineBoxCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Renders colored wireframe markers for village POI data when the overlay is toggled on.
 * <p>
 * Colors:
 * <ul>
 *   <li>Green: bell / meeting point</li>
 *   <li>Blue: claimed bed positions</li>
 *   <li>Yellow: claimed job site positions</li>
 *   <li>Grey: unclaimed bed or job site positions</li>
 *   <li>Dark green: bank blocks</li>
 *   <li>White (translucent): village bounding box</li>
 * </ul>
 * <p>
 * Render data is pre-built when the cache updates, not per frame.
 */
public final class VillagePOIOverlayRenderer {

    private static boolean enabled;
    private static long lastCacheTimestamp;
    private static List<MarkerBox> markers = Collections.emptyList();

    private VillagePOIOverlayRenderer() {}

    // Toggle

    public static boolean isEnabled() {
        return enabled;
    }

    public static void toggle() {
        if (enabled) {
            clear();
        } else {
            enabled = true;
        }
    }

    /** Clears overlay state when the client level or network session ends. */
    public static void clear() {
        enabled = false;
        markers = Collections.emptyList();
        lastCacheTimestamp = 0;
    }

    // Render entry point

    /**
     * Called from RenderLevelStageEvent (AFTER_TRANSLUCENT_BLOCKS).
     */
    public static void render(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, Vec3 cameraPos) {
        if (!enabled || !VillagePOIClientCache.hasData()) {
            return;
        }

        // Rebuild markers if cache has been updated since last build
        long cacheTimestamp = VillagePOIClientCache.getUpdateTimestamp();
        if (cacheTimestamp != lastCacheTimestamp) {
            rebuildMarkers();
            lastCacheTimestamp = cacheTimestamp;
        }

        if (markers.isEmpty()) {
            return;
        }

        VertexConsumer lineConsumer = bufferSource.getBuffer(RenderType.LINES);

        poseStack.pushPose();
        // Translate to world origin relative to camera
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        for (MarkerBox marker : markers) {
            RenderLineBoxCompat.render(
                    poseStack, lineConsumer,
                    marker.box,
                    marker.r, marker.g, marker.b, marker.a
            );
        }

        poseStack.popPose();
    }

    // Pre-build render data

    private static void rebuildMarkers() {
        List<MarkerBox> built = new ArrayList<>();

        // Bell / meeting point: green
        BlockPos bell = VillagePOIClientCache.getBellPosition();
        if (bell != null) {
            built.add(new MarkerBox(blockBox(bell), 0.0f, 1.0f, 0.0f, 1.0f));
        }

        // Collect claimed bed and job site positions from villager records
        Set<BlockPos> claimedBeds = new HashSet<>();
        Set<BlockPos> claimedJobSites = new HashSet<>();
        List<JobSiteEntry> jobSites = VillagePOIClientCache.getJobSites();
        Map<BlockPos, JobSiteEntry> jobSitesByPosition = new HashMap<>(jobSites.size());
        for (JobSiteEntry entry : jobSites) {
            jobSitesByPosition.putIfAbsent(entry.position(), entry);
        }
        for (VillagerPOIRecord record : VillagePOIClientCache.getRecords()) {
            BlockPos bed = record.getBedPos();
            if (bed != null) {
                claimedBeds.add(bed);
                // Claimed bed: blue
                built.add(new MarkerBox(blockBox(bed), 0.2f, 0.4f, 1.0f, 1.0f));
            }

            BlockPos job = record.getJobSitePos();
            if (job != null) {
                claimedJobSites.add(job);
                // Claimed job site: yellow for normal, dark green for infrastructure
                JobSiteEntry claimedEntry = jobSitesByPosition.get(job);
                boolean infra = claimedEntry != null && isInfrastructureType(claimedEntry.jobType());
                if (infra) {
                    built.add(new MarkerBox(blockBox(job), 0.0f, 0.45f, 0.1f, 1.0f));
                } else {
                    built.add(new MarkerBox(blockBox(job), 1.0f, 0.9f, 0.0f, 1.0f));
                }
            }
        }

        // Unclaimed beds: grey
        for (BlockPos bedPos : VillagePOIClientCache.getBedPositions()) {
            if (!claimedBeds.contains(bedPos)) {
                built.add(new MarkerBox(blockBox(bedPos), 0.5f, 0.5f, 0.5f, 0.8f));
            }
        }

        // Unclaimed job sites: grey for normal, dark green for infrastructure (Bank / Village Ledger)
        for (JobSiteEntry entry : jobSites) {
            if (!claimedJobSites.contains(entry.position())) {
                if (isInfrastructureType(entry.jobType())) {
                    built.add(new MarkerBox(blockBox(entry.position()), 0.0f, 0.45f, 0.1f, 1.0f));
                } else {
                    built.add(new MarkerBox(blockBox(entry.position()), 0.5f, 0.5f, 0.5f, 0.8f));
                }
            }
        }

        // Repair queue positions: red/orange (farmland needing repair)
        for (BlockPos pos : VillagePOIClientCache.getRepairQueuePositions()) {
            built.add(new MarkerBox(blockBox(pos), 1.0f, 0.3f, 0.1f, 0.9f));
        }

        // Village bounding box: white outline
        AABB bounds = VillagePOIClientCache.getBoundingBox();
        if (bounds != null) {
            built.add(new MarkerBox(bounds, 1.0f, 1.0f, 1.0f, 1.0f));
        }

        markers = List.copyOf(built);
    }

    /**
     * Returns true for job-site types that represent village infrastructure
     * (Bank, Village Ledger) rather than a vanilla villager profession.
     * These are rendered dark green instead of yellow/grey.
     */
    private static boolean isInfrastructureType(String jobType) {
        return "Bank".equals(jobType) || "Village Ledger".equals(jobType);
    }

    /** Creates a 1×1×1 AABB at a block position. */
    private static AABB blockBox(BlockPos pos) {
        return new AABB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
    }

    // Marker data

    private record MarkerBox(AABB box, float r, float g, float b, float a) {}
}
