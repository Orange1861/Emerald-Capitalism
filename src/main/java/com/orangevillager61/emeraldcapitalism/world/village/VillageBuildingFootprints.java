package com.orangevillager61.emeraldcapitalism.world.village;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Footprint checks shared by the generated-building site searches. */
final class VillageBuildingFootprints {
    private VillageBuildingFootprints() {
    }

    /** Bounding boxes of the village's non-street pieces: houses, farms, wells and the like. */
    static List<BoundingBox> buildingBoxes(List<StructurePiece> pieces) {
        List<BoundingBox> boxes = new ArrayList<>();
        for (StructurePiece piece : pieces) {
            if (!isStreetPiece(piece)) {
                boxes.add(piece.getBoundingBox());
            }
        }
        return boxes;
    }

    /**
     * Whether a candidate footprint shares any column with a village building.
     * Only X and Z are compared: a candidate footprint is evaluated before its
     * placement height is known, so a vertical comparison would miss buildings.
     */
    static boolean overlapsHorizontally(BoundingBox footprint, List<BoundingBox> buildingBoxes) {
        for (BoundingBox box : buildingBoxes) {
            if (footprint.minX() <= box.maxX() && footprint.maxX() >= box.minX()
                    && footprint.minZ() <= box.maxZ() && footprint.maxZ() >= box.minZ()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStreetPiece(StructurePiece piece) {
        if (piece instanceof PoolElementStructurePiece poolPiece) {
            String element = poolPiece.getElement().toString().toLowerCase(Locale.ROOT);
            return element.contains("/streets/") || element.contains("/street/");
        }
        return false;
    }
}
