package com.orangevillager61.emeraldcapitalism.world.village;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Moves existing entities out of a volume before a village building overwrites it. */
public final class VillageEntityRelocation {
    private static final int MAX_HORIZONTAL_RADIUS = 16;
    private static final int MAX_VERTICAL_OFFSET = 16;

    private VillageEntityRelocation() {
    }

    /**
     * Relocates every live entity whose hitbox intersects one of the supplied
     * building volumes. Candidates are outside those volumes, free of block and
     * fluid collisions, and have a supported floor unless the entity has no gravity.
     */
    public static void relocateFromBuilding(ServerLevel level, List<BoundingBox> buildingBoxes) {
        if (buildingBoxes.isEmpty()) {
            return;
        }

        AABB query = queryBox(buildingBoxes);
        List<Entity> occupants = level.getEntities((Entity) null, query,
                entity -> entity.isAlive() && intersectsAny(entity.getBoundingBox(), buildingBoxes));
        for (Entity entity : occupants) {
            BlockPos destination = findSafeDestination(level, entity, buildingBoxes);
            if (destination == null) {
                EmeraldCapitalism.LOGGER.warn(
                        "[ECAP] Could not move entity {} out of a village building at {} before placement",
                        entity.getType(), entity.blockPosition());
                continue;
            }

            entity.moveTo(destination.getX() + 0.5D, destination.getY(), destination.getZ() + 0.5D,
                    entity.getYRot(), entity.getXRot());
            entity.setDeltaMovement(Vec3.ZERO);
            entity.resetFallDistance();
            EmeraldCapitalism.LOGGER.debug(
                    "[ECAP] Moved entity {} from a village building to {} before placement",
                    entity.getType(), destination);
        }
    }

    private static BlockPos findSafeDestination(ServerLevel level, Entity entity,
                                                List<BoundingBox> buildingBoxes) {
        BlockPos origin = entity.blockPosition();
        for (int radius = 1; radius <= MAX_HORIZONTAL_RADIUS; radius++) {
            for (int verticalDistance = 0; verticalDistance <= MAX_VERTICAL_OFFSET; verticalDistance++) {
                BlockPos destination = findAtVerticalOffset(level, entity, buildingBoxes,
                        origin, radius, verticalDistance);
                if (destination != null) {
                    return destination;
                }
                if (verticalDistance > 0) {
                    destination = findAtVerticalOffset(level, entity, buildingBoxes,
                            origin, radius, -verticalDistance);
                    if (destination != null) {
                        return destination;
                    }
                }
            }
        }
        return null;
    }

    private static BlockPos findAtVerticalOffset(ServerLevel level, Entity entity,
                                                 List<BoundingBox> buildingBoxes,
                                                 BlockPos origin, int radius, int yOffset) {
        int y = origin.getY() + yOffset;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                    continue;
                }
                BlockPos candidate = new BlockPos(origin.getX() + dx, y, origin.getZ() + dz);
                if (isSafeDestination(level, entity, candidate, buildingBoxes)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean isSafeDestination(ServerLevel level, Entity entity, BlockPos candidate,
                                              List<BoundingBox> buildingBoxes) {
        if (!level.getChunkSource().hasChunk(candidate.getX() >> 4, candidate.getZ() >> 4)) {
            return false;
        }

        AABB destinationBox = entity.getBoundingBox().move(
                candidate.getX() + 0.5D - entity.getX(),
                candidate.getY() - entity.getY(),
                candidate.getZ() + 0.5D - entity.getZ());
        if (intersectsAny(destinationBox, buildingBoxes)
                || destinationBox.minY < level.getMinBuildHeight()
                || destinationBox.maxY > level.getMaxBuildHeight()
                || !hasNoFluid(level, destinationBox)
                || !level.noCollision(entity, destinationBox)
                || !hasSafeFloor(level, entity, candidate)) {
            return false;
        }

        return level.getEntities(entity, destinationBox,
                other -> other.isAlive()).isEmpty();
    }

    private static boolean hasSafeFloor(ServerLevel level, Entity entity, BlockPos candidate) {
        if (entity.isNoGravity()) {
            return true;
        }
        BlockPos below = candidate.below();
        BlockState belowState = level.getBlockState(below);
        return belowState.getFluidState().isEmpty()
                && belowState.isFaceSturdy(level, below, Direction.UP);
    }

    private static boolean hasNoFluid(ServerLevel level, AABB box) {
        int minX = Mth.floor(box.minX);
        int minY = Mth.floor(box.minY);
        int minZ = Mth.floor(box.minZ);
        int maxX = Mth.ceil(box.maxX) - 1;
        int maxY = Mth.ceil(box.maxY) - 1;
        int maxZ = Mth.ceil(box.maxZ) - 1;
        for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (!level.getFluidState(pos).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static AABB queryBox(List<BoundingBox> buildingBoxes) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BoundingBox box : buildingBoxes) {
            minX = Math.min(minX, box.minX());
            minY = Math.min(minY, box.minY());
            minZ = Math.min(minZ, box.minZ());
            maxX = Math.max(maxX, box.maxX());
            maxY = Math.max(maxY, box.maxY());
            maxZ = Math.max(maxZ, box.maxZ());
        }
        return new AABB(minX, minY, minZ, maxX + 1.0D, maxY + 1.0D, maxZ + 1.0D);
    }

    private static boolean intersectsAny(AABB entityBox, List<BoundingBox> buildingBoxes) {
        for (BoundingBox box : buildingBoxes) {
            if (entityBox.maxX > box.minX() && entityBox.minX < box.maxX() + 1.0D
                    && entityBox.maxY > box.minY() && entityBox.minY < box.maxY() + 1.0D
                    && entityBox.maxZ > box.minZ() && entityBox.minZ < box.maxZ() + 1.0D) {
                return true;
            }
        }
        return false;
    }
}
