package com.orangevillager61.emeraldcapitalism.world.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Stable spatial bucket for farmer work owned by one village.
 *
 * <p>Cells use the world's chunk grid as their horizontal partition. The key
 * therefore remains stable when the village's bounds expand or contract:
 * existing cell boundaries are never reshaped. The village record is
 * responsible for checking that a queued position is inside the current
 * village bounds before it reaches this class.</p>
 */
public final class VillageWorkCell {

    /** Work cells are one chunk wide and one chunk deep. */
    public static final int CELL_SIZE = 16;
    private static final int MAX_PERSISTED_POSITIONS_PER_COLLECTION = 65_536;

    /** Absolute chunk coordinates identify a cell without depending on village bounds. */
    public record Key(int chunkX, int chunkZ) {

        public Key {
            try {
                Math.multiplyExact(chunkX, CELL_SIZE);
                Math.multiplyExact(chunkZ, CELL_SIZE);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("Work-cell chunk coordinate is outside the block range",
                        exception);
            }
        }

        public static Key from(BlockPos position) {
            Objects.requireNonNull(position, "position");
            return new Key(position.getX() >> 4, position.getZ() >> 4);
        }

        public int minBlockX() {
            return chunkX * CELL_SIZE;
        }

        public int minBlockZ() {
            return chunkZ * CELL_SIZE;
        }
    }

    /** Transient assignment token; leases are deliberately never persisted. */
    public record WorkLease(UUID farmerId, long issuedAt, long expiresAt,
                            Set<BlockPos> claimedPositions) {
        public WorkLease(UUID farmerId, long issuedAt, long expiresAt) {
            this(farmerId, issuedAt, expiresAt, Set.of());
        }

        public WorkLease {
            Objects.requireNonNull(farmerId, "farmerId");
            if (expiresAt < issuedAt) {
                throw new IllegalArgumentException("Work lease expires before it is issued");
            }
            Set<BlockPos> normalizedClaims = new HashSet<>();
            for (BlockPos position : Objects.requireNonNull(claimedPositions, "claimedPositions")) {
                normalizedClaims.add(Objects.requireNonNull(position, "claimed position").immutable());
            }
            claimedPositions = normalizedClaims;
        }

        public Set<BlockPos> claimedPositions() {
            return Collections.unmodifiableSet(claimedPositions);
        }

        public boolean isExpired(long gameTime) {
            return gameTime >= expiresAt;
        }

        boolean claimPosition(BlockPos position) {
            return claimedPositions.add(Objects.requireNonNull(position, "position").immutable());
        }

        boolean releaseClaim(BlockPos position) {
            return claimedPositions.remove(Objects.requireNonNull(position, "position"));
        }

        void clearClaims() {
            claimedPositions.clear();
        }
    }

    private record EncodedCell(
            int chunkX,
            int chunkZ,
            List<BlockPos> farmlandRepairs,
            List<BlockPos> matureCropWork
    ) {
        private static final Codec<EncodedCell> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("chunk_x").forGetter(EncodedCell::chunkX),
                Codec.INT.fieldOf("chunk_z").forGetter(EncodedCell::chunkZ),
                BlockPos.CODEC.sizeLimitedListOf(MAX_PERSISTED_POSITIONS_PER_COLLECTION)
                        .optionalFieldOf("farmland_repairs", List.of())
                        .forGetter(EncodedCell::farmlandRepairs),
                BlockPos.CODEC.sizeLimitedListOf(MAX_PERSISTED_POSITIONS_PER_COLLECTION)
                        .optionalFieldOf("mature_crop_work", List.of())
                        .forGetter(EncodedCell::matureCropWork)
        ).apply(instance, EncodedCell::new));
    }

    /** Legacy decoder retained to migrate phase-2 persisted cell buckets on load. */
    public static final Codec<VillageWorkCell> CODEC = EncodedCell.CODEC.comapFlatMap(
            VillageWorkCell::fromEncoded,
            VillageWorkCell::toEncoded
    );

    private final Key key;
    private final Set<BlockPos> farmlandRepairPositions = new HashSet<>();
    private final Set<BlockPos> matureCropWorkPositions = new HashSet<>();
    private long workReadySince = Long.MIN_VALUE;
    private long leaseRetryAfter = Long.MIN_VALUE;
    private int leaseFailureCount;
    private WorkLease activeLease;

    public VillageWorkCell(Key key) {
        this.key = Objects.requireNonNull(key, "key");
    }

    public Key getKey() {
        return key;
    }

    public int getChunkX() {
        return key.chunkX();
    }

    public int getChunkZ() {
        return key.chunkZ();
    }

    /**
     * Returns this cell clipped to the supplied village bounds. The clipping
     * only describes the village edge; the underlying chunk-aligned cell does
     * not change when the bounds change.
     */
    public AABB getBounds(AABB villageBounds) {
        Objects.requireNonNull(villageBounds, "villageBounds");
        return new AABB(
                Math.max(villageBounds.minX, key.minBlockX()),
                villageBounds.minY,
                Math.max(villageBounds.minZ, key.minBlockZ()),
                Math.min(villageBounds.maxX, key.minBlockX() + CELL_SIZE),
                villageBounds.maxY,
                Math.min(villageBounds.maxZ, key.minBlockZ() + CELL_SIZE)
        );
    }

    /** Returns whether a position belongs to this chunk-aligned cell. */
    public boolean containsPosition(BlockPos position) {
        return Key.from(Objects.requireNonNull(position, "position")).equals(key);
    }

    public Set<BlockPos> getFarmlandRepairPositions() {
        return Collections.unmodifiableSet(farmlandRepairPositions);
    }

    public Set<BlockPos> getMatureCropWorkPositions() {
        return Collections.unmodifiableSet(matureCropWorkPositions);
    }

    public int getPendingRepairCount() {
        return farmlandRepairPositions.size();
    }

    public int getPendingCropWorkCount() {
        return matureCropWorkPositions.size();
    }

    public int getPendingWorkCount() {
        return getPendingRepairCount() + getPendingCropWorkCount();
    }

    /** Returns the first queued position, used only as a bounded lease target hint. */
    public BlockPos getWorkPosition() {
        if (!farmlandRepairPositions.isEmpty()) {
            return farmlandRepairPositions.iterator().next();
        }
        return matureCropWorkPositions.isEmpty()
                ? null
                : matureCropWorkPositions.iterator().next();
    }

    public long getWorkReadySince() {
        return workReadySince;
    }

    public long getLeaseRetryAfter() {
        return leaseRetryAfter;
    }

    public int getLeaseFailureCount() {
        return leaseFailureCount;
    }

    public WorkLease getActiveLease() {
        return activeLease;
    }

    public boolean isEmpty() {
        return farmlandRepairPositions.isEmpty() && matureCropWorkPositions.isEmpty();
    }

    void markWorkReady(long gameTime) {
        if (workReadySince == Long.MIN_VALUE) {
            workReadySince = gameTime;
        }
    }

    void clearWorkStateIfEmpty() {
        if (isEmpty()) {
            workReadySince = Long.MIN_VALUE;
            if (activeLease != null) {
                activeLease.clearClaims();
            }
            activeLease = null;
            leaseRetryAfter = Long.MIN_VALUE;
            leaseFailureCount = 0;
        }
    }

    boolean canLease(long gameTime) {
        return !isEmpty() && activeLease == null && gameTime >= leaseRetryAfter;
    }

    void assignLease(UUID farmerId, long gameTime, long durationTicks) {
        if (!canLease(gameTime)) {
            throw new IllegalStateException("Work cell cannot accept a lease: " + key);
        }
        if (durationTicks <= 0) {
            throw new IllegalArgumentException("Work lease duration must be positive");
        }
        activeLease = new WorkLease(farmerId, gameTime, gameTime + durationTicks, Set.of());
        leaseRetryAfter = Long.MIN_VALUE;
    }

    boolean releaseLease(UUID farmerId) {
        if (activeLease == null || !activeLease.farmerId().equals(farmerId)) {
            return false;
        }
        activeLease.clearClaims();
        activeLease = null;
        return true;
    }

    boolean renewLease(UUID farmerId, long gameTime, long durationTicks) {
        if (activeLease == null
                || !activeLease.farmerId().equals(farmerId)
                || activeLease.isExpired(gameTime)) {
            return false;
        }
        if (durationTicks <= 0) {
            throw new IllegalArgumentException("Work lease duration must be positive");
        }
        activeLease = new WorkLease(activeLease.farmerId(), activeLease.issuedAt(),
                gameTime + durationTicks, activeLease.claimedPositions());
        return true;
    }

    void clearTransientLeaseState() {
        if (activeLease != null) {
            activeLease.clearClaims();
        }
        activeLease = null;
        leaseRetryAfter = Long.MIN_VALUE;
        leaseFailureCount = 0;
        workReadySince = Long.MIN_VALUE;
    }

    boolean releaseExpiredLease(long gameTime) {
        if (activeLease == null || !activeLease.isExpired(gameTime)) {
            return false;
        }
        activeLease.clearClaims();
        activeLease = null;
        return true;
    }

    boolean recordLeaseFailure(UUID farmerId, long gameTime, long retryTicks) {
        if (activeLease == null || !activeLease.farmerId().equals(farmerId)) {
            return false;
        }
        activeLease.clearClaims();
        activeLease = null;
        leaseFailureCount++;
        leaseRetryAfter = gameTime + Math.max(1L, retryTicks);
        return true;
    }

    boolean addFarmlandRepair(BlockPos position) {
        requireCellPosition(position);
        return farmlandRepairPositions.add(position.immutable());
    }

    boolean removeFarmlandRepair(BlockPos position) {
        return farmlandRepairPositions.remove(Objects.requireNonNull(position, "position"));
    }

    boolean addMatureCropWork(BlockPos position) {
        requireCellPosition(position);
        return matureCropWorkPositions.add(position.immutable());
    }

    boolean removeMatureCropWork(BlockPos position) {
        return matureCropWorkPositions.remove(Objects.requireNonNull(position, "position"));
    }

    void retainFarmlandRepairs(java.util.function.Predicate<BlockPos> keep) {
        farmlandRepairPositions.removeIf(keep.negate());
    }

    void retainMatureCropWork(java.util.function.Predicate<BlockPos> keep) {
        matureCropWorkPositions.removeIf(keep.negate());
    }

    private void requireCellPosition(BlockPos position) {
        if (!containsPosition(position)) {
            throw new IllegalArgumentException("Position " + position + " is outside work cell " + key);
        }
    }

    private static DataResult<VillageWorkCell> fromEncoded(EncodedCell encoded) {
        try {
            VillageWorkCell cell = new VillageWorkCell(new Key(encoded.chunkX(), encoded.chunkZ()));
            for (BlockPos position : encoded.farmlandRepairs()) {
                if (!cell.containsPosition(position)) {
                    return DataResult.error(() -> "Farmland repair position is outside its work cell: " + position);
                }
                if (!cell.addFarmlandRepair(position)) {
                    return DataResult.error(() -> "Duplicate farmland repair position in work cell: " + position);
                }
            }
            for (BlockPos position : encoded.matureCropWork()) {
                if (!cell.containsPosition(position)) {
                    return DataResult.error(() -> "Mature crop position is outside its work cell: " + position);
                }
                if (!cell.addMatureCropWork(position)) {
                    return DataResult.error(() -> "Duplicate mature crop position in work cell: " + position);
                }
            }
            return DataResult.success(cell);
        } catch (IllegalArgumentException exception) {
            return DataResult.error(() -> exception.getMessage());
        }
    }

    private static EncodedCell toEncoded(VillageWorkCell cell) {
        return new EncodedCell(cell.key.chunkX(), cell.key.chunkZ(),
                new ArrayList<>(cell.farmlandRepairPositions),
                new ArrayList<>(cell.matureCropWorkPositions));
    }
}
