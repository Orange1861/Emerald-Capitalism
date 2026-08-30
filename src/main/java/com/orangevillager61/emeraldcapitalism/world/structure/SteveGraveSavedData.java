package com.orangevillager61.emeraldcapitalism.world.structure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Optional;

/**
 * World-level state for the single Steve grave.
 *
 * <p>The data is owned by the overworld.  The initial spawn anchor and the
 * resolved target are persisted so the grave remains fixed after restarts and
 * after a later {@code /setworldspawn} command.</p>
 */
public final class SteveGraveSavedData extends SavedData {
    private static final String DATA_NAME = "emeraldcapitalism_steve_grave";

    public enum PlacementState {
        UNRESOLVED(0),
        TARGET_FOUND(1),
        PLACED(2),
        SEARCH_FAILED(3),
        PLACEMENT_FAILED(4);

        private final int id;

        PlacementState(int id) {
            this.id = id;
        }

        private static PlacementState fromId(int id) {
            for (PlacementState state : values()) {
                if (state.id == id) {
                    return state;
                }
            }
            return UNRESOLVED;
        }
    }

    /** Codec-owned persisted schema; all positions are optional for safe first-load defaults. */
    public static final Codec<SteveGraveSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.optionalFieldOf("spawn_anchor")
                    .forGetter(data -> Optional.ofNullable(data.spawnAnchor)),
            BlockPos.CODEC.optionalFieldOf("target")
                    .forGetter(data -> Optional.ofNullable(data.target)),
            BlockPos.CODEC.optionalFieldOf("placed_origin")
                    .forGetter(data -> Optional.ofNullable(data.placedOrigin)),
            Codec.intRange(0, 4).optionalFieldOf("placement_state", 0)
                    .forGetter(data -> data.placementState.id)
    ).apply(instance, SteveGraveSavedData::fromCodec));

    private BlockPos spawnAnchor;
    private BlockPos target;
    private BlockPos placedOrigin;
    private PlacementState placementState = PlacementState.UNRESOLVED;

    public SteveGraveSavedData() {
    }

    private static SteveGraveSavedData fromCodec(
            Optional<BlockPos> spawnAnchor,
            Optional<BlockPos> target,
            Optional<BlockPos> placedOrigin,
            int placementState
    ) {
        SteveGraveSavedData data = new SteveGraveSavedData();
        data.spawnAnchor = spawnAnchor.map(pos -> new BlockPos(pos.getX(), 0, pos.getZ()).immutable()).orElse(null);
        data.target = target.map(pos -> new BlockPos(pos.getX(), 0, pos.getZ()).immutable()).orElse(null);
        data.placedOrigin = placedOrigin.map(BlockPos::immutable).orElse(null);
        data.placementState = PlacementState.fromId(placementState);

        // Do not trust a malformed combination of optional fields to make the
        // grave look completed or permanently unavailable.
        if (data.placementState == PlacementState.PLACED && data.placedOrigin == null) {
            data.placementState = data.target == null
                    ? PlacementState.UNRESOLVED : PlacementState.TARGET_FOUND;
        } else if (data.placementState == PlacementState.TARGET_FOUND && data.target == null) {
            data.placementState = PlacementState.UNRESOLVED;
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        DataResult<net.minecraft.nbt.Tag> encoded = CODEC.encodeStart(NbtOps.INSTANCE, this);
        return encoded.resultOrPartial(message -> EmeraldCapitalism.LOGGER.error(
                        "[ECAP] Could not encode Steve grave data: {}", message))
                .filter(CompoundTag.class::isInstance)
                .map(CompoundTag.class::cast)
                .map(encodedTag -> {
                    tag.merge(encodedTag);
                    return tag;
                })
                .orElse(tag);
    }

    public static SteveGraveSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        return CODEC.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial(message -> EmeraldCapitalism.LOGGER.error(
                        "[ECAP] Could not decode Steve grave data: {}", message))
                .orElseGet(SteveGraveSavedData::new);
    }

    /** Returns the overworld-owned grave state for this server world. */
    public static SteveGraveSavedData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SteveGraveSavedData::new, SteveGraveSavedData::load, null),
                DATA_NAME
        );
    }

    public BlockPos spawnAnchor() {
        return spawnAnchor;
    }

    /** Returns the resolved X/Z target.  Its Y component is always zero until placement. */
    public BlockPos target() {
        return target;
    }

    public BlockPos placedOrigin() {
        return placedOrigin;
    }

    public PlacementState placementState() {
        return placementState;
    }

    public boolean hasTarget() {
        return target != null;
    }

    public boolean isPlaced() {
        return placementState == PlacementState.PLACED && placedOrigin != null;
    }

    public boolean searchFailed() {
        return placementState == PlacementState.SEARCH_FAILED;
    }

    public boolean placementFailed() {
        return placementState == PlacementState.PLACEMENT_FAILED;
    }

    /** Stores only X/Z because the initial world spawn's Y is not relevant to the rule. */
    public void setSpawnAnchor(BlockPos spawn) {
        BlockPos normalized = new BlockPos(spawn.getX(), 0, spawn.getZ()).immutable();
        if (!normalized.equals(spawnAnchor)) {
            spawnAnchor = normalized;
            setDirty();
        }
    }

    /** Stores only X/Z until the terrain-dependent template origin is known. */
    public void setTarget(BlockPos targetPosition) {
        BlockPos normalized = new BlockPos(targetPosition.getX(), 0, targetPosition.getZ()).immutable();
        if (!normalized.equals(target) || placementState != PlacementState.TARGET_FOUND) {
            target = normalized;
            placementState = PlacementState.TARGET_FOUND;
            placedOrigin = null;
            setDirty();
        }
    }

    public void markSearchFailed() {
        if (placementState != PlacementState.SEARCH_FAILED) {
            placementState = PlacementState.SEARCH_FAILED;
            setDirty();
        }
    }

    public void markPlacementFailed() {
        if (placementState != PlacementState.PLACEMENT_FAILED) {
            placementState = PlacementState.PLACEMENT_FAILED;
            setDirty();
        }
    }

    /** Records the irreversible template write immediately after it succeeds. */
    public void markPlaced(BlockPos origin) {
        BlockPos normalized = origin.immutable();
        if (!normalized.equals(placedOrigin) || placementState != PlacementState.PLACED) {
            placedOrigin = normalized;
            placementState = PlacementState.PLACED;
            setDirty();
        }
    }
}
