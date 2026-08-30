package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Spatial claims made during planning, before any building mutates the world. */
public final class VillageGenerationReservations {
    private final List<Reservation> buildings = new ArrayList<>();
    private final Set<Long> pathColumns = new HashSet<>();

    public boolean intersects(BoundingBox footprint) {
        for (Reservation reservation : buildings) {
            if (footprint.intersects(reservation.exclusionBox())) {
                return true;
            }
        }
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                if (pathColumns.contains(columnKey(x, z))) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean reserveBuilding(ResourceLocation owner, BoundingBox placementBox,
                                   BoundingBox exclusionBox) {
        // Only actual blocks must clear earlier exclusions. A new approach apron may
        // surround an earlier small utility (such as the manager) without overwriting it.
        if (intersects(placementBox)) {
            return false;
        }
        buildings.add(new Reservation(owner, placementBox, exclusionBox));
        return true;
    }

    public boolean reserveBuilding(ResourceLocation owner, BoundingBox footprint) {
        return reserveBuilding(owner, footprint, footprint);
    }

    public void reservePath(Iterable<BlockPos> surfaceCells) {
        for (BlockPos cell : surfaceCells) {
            pathColumns.add(columnKey(cell.getX(), cell.getZ()));
        }
    }

    public List<BoundingBox> buildingBoxes() {
        return buildings.stream().map(Reservation::exclusionBox).toList();
    }

    public Set<Long> pathColumns() {
        return Collections.unmodifiableSet(pathColumns);
    }

    public static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private record Reservation(ResourceLocation owner, BoundingBox placementBox,
                               BoundingBox exclusionBox) {
    }
}
