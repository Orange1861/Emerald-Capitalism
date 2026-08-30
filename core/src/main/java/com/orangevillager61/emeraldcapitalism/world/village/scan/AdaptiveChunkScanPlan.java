package com.orangevillager61.emeraldcapitalism.world.village.scan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bell-outward chunk scan plan for an uninitialized village cache.
 *
 * <p>Chunks are grouped into eight directional segments on each square ring.
 * A direction is pruned only after three sequential, fully observed empty
 * segments. Interesting or unavailable segments reset that direction's streak.
 */
public final class AdaptiveChunkScanPlan {
    public static final int EMPTY_SEGMENT_LIMIT = 3;

    public enum Sector {
        CENTER,
        NORTH,
        NORTH_EAST,
        EAST,
        SOUTH_EAST,
        SOUTH,
        SOUTH_WEST,
        WEST,
        NORTH_WEST
    }

    public enum ChunkOutcome {
        INTERESTING,
        EMPTY,
        UNKNOWN
    }

    public record ChunkCoordinate(int x, int z) {
    }

    private static final List<Sector> RING_ORDER = List.of(
            Sector.NORTH,
            Sector.NORTH_EAST,
            Sector.EAST,
            Sector.SOUTH_EAST,
            Sector.SOUTH,
            Sector.SOUTH_WEST,
            Sector.WEST,
            Sector.NORTH_WEST
    );

    private final List<RingSegment> segments;
    private final Map<Sector, Boolean> active = new EnumMap<>(Sector.class);
    private final Map<Sector, Integer> emptyStreaks = new EnumMap<>(Sector.class);
    private final Map<Sector, Integer> requiredRadii = new EnumMap<>(Sector.class);
    private int segmentIndex;
    private int chunkIndex;
    private boolean segmentInteresting;
    private boolean segmentUnknown;

    public AdaptiveChunkScanPlan(
            int centerX,
            int centerZ,
            int minChunkX,
            int maxChunkX,
            int minChunkZ,
            int maxChunkZ,
            Collection<ChunkCoordinate> requiredChunks
    ) {
        for (Sector sector : RING_ORDER) {
            active.put(sector, true);
            emptyStreaks.put(sector, 0);
            requiredRadii.put(sector, 0);
        }

        for (ChunkCoordinate required : requiredChunks) {
            if (required.x() < minChunkX || required.x() > maxChunkX
                    || required.z() < minChunkZ || required.z() > maxChunkZ) {
                continue;
            }
            int dx = required.x() - centerX;
            int dz = required.z() - centerZ;
            if (dx == 0 && dz == 0) {
                continue;
            }
            Sector sector = sectorFor(dx, dz);
            int radius = Math.max(Math.abs(dx), Math.abs(dz));
            requiredRadii.merge(sector, radius, Math::max);
        }

        this.segments = buildSegments(centerX, centerZ, minChunkX, maxChunkX, minChunkZ, maxChunkZ);
        advancePastInactiveSegments();
    }

    public boolean isComplete() {
        return segmentIndex >= segments.size();
    }

    public ChunkCoordinate currentChunk() {
        if (isComplete()) {
            throw new IllegalStateException("Adaptive chunk scan is complete");
        }
        return segments.get(segmentIndex).chunks().get(chunkIndex);
    }

    public int currentRadius() {
        return isComplete() ? -1 : segments.get(segmentIndex).radius();
    }

    public Sector currentSector() {
        return isComplete() ? null : segments.get(segmentIndex).sector();
    }

    /**
     * Returns a bounded look-ahead that is safe to request before the current chunk is complete.
     * A sector is included only until it would be encountered for a second time, because finishing
     * its current segment may prune that sector from later rings.
     */
    public List<ChunkCoordinate> upcomingChunks(int limit) {
        if (limit <= 0 || isComplete()) {
            return List.of();
        }

        List<ChunkCoordinate> result = new ArrayList<>(limit);
        Set<Sector> encounteredSectors = EnumSet.noneOf(Sector.class);
        int lookaheadSegment = segmentIndex;
        int lookaheadChunk = chunkIndex;
        while (lookaheadSegment < segments.size() && result.size() < limit) {
            RingSegment segment = segments.get(lookaheadSegment);
            if (segment.sector() != Sector.CENTER && !active.get(segment.sector())) {
                lookaheadSegment++;
                lookaheadChunk = 0;
                continue;
            }
            if (!encounteredSectors.add(segment.sector())) {
                break;
            }
            while (lookaheadChunk < segment.chunks().size() && result.size() < limit) {
                result.add(segment.chunks().get(lookaheadChunk++));
            }
            lookaheadSegment++;
            lookaheadChunk = 0;
        }
        return List.copyOf(result);
    }

    /** Records the current chunk and advances to the next active ring segment. */
    public void completeCurrentChunk(ChunkOutcome outcome) {
        if (isComplete()) {
            throw new IllegalStateException("Adaptive chunk scan is complete");
        }

        if (outcome == ChunkOutcome.INTERESTING) {
            segmentInteresting = true;
        } else if (outcome == ChunkOutcome.UNKNOWN) {
            segmentUnknown = true;
        }

        RingSegment segment = segments.get(segmentIndex);
        chunkIndex++;
        if (chunkIndex < segment.chunks().size()) {
            return;
        }

        finishSegment(segment);
        segmentIndex++;
        chunkIndex = 0;
        segmentInteresting = false;
        segmentUnknown = false;
        advancePastInactiveSegments();
    }

    private void finishSegment(RingSegment segment) {
        Sector sector = segment.sector();
        if (sector == Sector.CENTER) {
            return;
        }

        int requiredRadius = requiredRadii.get(sector);
        if (segment.radius() <= requiredRadius || segmentInteresting || segmentUnknown) {
            emptyStreaks.put(sector, 0);
            return;
        }

        int streak = emptyStreaks.get(sector) + 1;
        emptyStreaks.put(sector, streak);
        if (streak >= EMPTY_SEGMENT_LIMIT) {
            active.put(sector, false);
        }
    }

    private void advancePastInactiveSegments() {
        while (segmentIndex < segments.size()) {
            RingSegment segment = segments.get(segmentIndex);
            if (segment.sector() == Sector.CENTER || active.get(segment.sector())) {
                return;
            }
            segmentIndex++;
        }
    }

    private static List<RingSegment> buildSegments(
            int centerX,
            int centerZ,
            int minChunkX,
            int maxChunkX,
            int minChunkZ,
            int maxChunkZ
    ) {
        List<RingSegment> result = new ArrayList<>();
        if (centerX >= minChunkX && centerX <= maxChunkX && centerZ >= minChunkZ && centerZ <= maxChunkZ) {
            result.add(new RingSegment(0, Sector.CENTER, List.of(new ChunkCoordinate(centerX, centerZ))));
        }

        int maxRadius = Math.max(
                Math.max(Math.abs(minChunkX - centerX), Math.abs(maxChunkX - centerX)),
                Math.max(Math.abs(minChunkZ - centerZ), Math.abs(maxChunkZ - centerZ))
        );

        for (int radius = 1; radius <= maxRadius; radius++) {
            Map<Sector, List<ChunkCoordinate>> bySector = new EnumMap<>(Sector.class);
            for (Sector sector : RING_ORDER) {
                bySector.put(sector, new ArrayList<>());
            }

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int chunkX = centerX + dx;
                    int chunkZ = centerZ + dz;
                    if (chunkX < minChunkX || chunkX > maxChunkX
                            || chunkZ < minChunkZ || chunkZ > maxChunkZ) {
                        continue;
                    }
                    bySector.get(sectorFor(dx, dz)).add(new ChunkCoordinate(chunkX, chunkZ));
                }
            }

            for (Sector sector : RING_ORDER) {
                List<ChunkCoordinate> chunks = bySector.get(sector);
                if (chunks.isEmpty()) {
                    continue;
                }
                chunks.sort(Comparator.comparingDouble(chunk -> clockwiseAngle(
                        chunk.x() - centerX,
                        chunk.z() - centerZ
                )));
                result.add(new RingSegment(radius, sector, List.copyOf(chunks)));
            }
        }
        return List.copyOf(result);
    }

    private static Sector sectorFor(int dx, int dz) {
        int absX = Math.abs(dx);
        int absZ = Math.abs(dz);
        if (absX > absZ) {
            return dx > 0 ? Sector.EAST : Sector.WEST;
        }
        if (absZ > absX) {
            return dz > 0 ? Sector.SOUTH : Sector.NORTH;
        }
        if (dx > 0) {
            return dz > 0 ? Sector.SOUTH_EAST : Sector.NORTH_EAST;
        }
        return dz > 0 ? Sector.SOUTH_WEST : Sector.NORTH_WEST;
    }

    private static double clockwiseAngle(int dx, int dz) {
        double angle = Math.atan2(dx, -dz);
        return angle < 0.0 ? angle + (Math.PI * 2.0) : angle;
    }

    private record RingSegment(int radius, Sector sector, List<ChunkCoordinate> chunks) {
    }
}
