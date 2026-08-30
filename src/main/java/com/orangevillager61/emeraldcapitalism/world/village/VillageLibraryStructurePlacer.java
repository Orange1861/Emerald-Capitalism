package com.orangevillager61.emeraldcapitalism.world.village;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookDefinition;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookRegistry;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.ChunkLoadBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.util.function.Predicate;

/** Places one vanilla librarian building for a village. */
public final class VillageLibraryStructurePlacer {
    private static final Map<String, ResourceLocation> LIBRARY_TEMPLATES = new HashMap<>();
    private static final ResourceLocation LIBRARY_PROCESSORS = ResourceLocation.parse("minecraft:mossify_10_percent");
    private static final int[] DISTANCES_FROM_BELL = {18, 26, 34, 42, 50, 58};
    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final Rotation[] ROTATIONS = {
            Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180,
            Rotation.COUNTERCLOCKWISE_90
    };

    static {
        LIBRARY_TEMPLATES.put("PLAINS", ResourceLocation.parse(
                "minecraft:village/plains/houses/plains_library_1"));
        LIBRARY_TEMPLATES.put("DESERT", ResourceLocation.parse(
                "minecraft:village/desert/houses/desert_library_1"));
        LIBRARY_TEMPLATES.put("SAVANNA", ResourceLocation.parse(
                "minecraft:village/savanna/houses/savanna_library_1"));
        LIBRARY_TEMPLATES.put("TAIGA", ResourceLocation.parse(
                "minecraft:village/taiga/houses/taiga_library_1"));
        LIBRARY_TEMPLATES.put("SNOWY", ResourceLocation.parse(
                "minecraft:village/snowy/houses/snowy_library_1"));
    }

    /** Chooses a non-overlapping site without mutating the world. */
    @Nullable
    public PlannedLibrary plan(ServerLevel level, BlockPos bellPos, String biomeType,
                               List<StructurePiece> villagePieces, ChunkLoadBudget loadBudget,
                               Predicate<BoundingBox> reservedCollision) {
        ResourceLocation templateLocation = LIBRARY_TEMPLATES.get(biomeType);
        if (templateLocation == null) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] No library template is configured for biome {}", biomeType);
            return null;
        }

        Optional<StructureTemplate> template = level.getStructureManager().get(templateLocation);
        if (template.isEmpty()) {
            EmeraldCapitalism.LOGGER.error(
                    "[ECAP] Cannot generate village library: missing template {}",
                    templateLocation);
            return null;
        }

        List<BoundingBox> pathBoxes = collectPathBoxes(villagePieces);
        List<LibrarySite> sites = new ArrayList<>();
        for (int distance : DISTANCES_FROM_BELL) {
            for (int[] direction : DIRECTIONS) {
                for (Rotation rotation : ROTATIONS) {
                    int rotatedX = rotatedSizeX(template.get(), rotation);
                    int rotatedZ = rotatedSizeZ(template.get(), rotation);
                    int desiredMinX = bellPos.getX() + direction[0] * distance - rotatedX / 2;
                    int desiredMinZ = bellPos.getZ() + direction[1] * distance - rotatedZ / 2;
                    StructurePlaceSettings placementSettings = placementSettings(rotation);
                    BlockPos anchor = alignAnchorToFootprint(template.get(), placementSettings,
                            desiredMinX, desiredMinZ);
                    BoundingBox footprint = template.get().getBoundingBox(placementSettings, anchor);
                    if (reservedCollision.test(footprint)
                            || overlapsVillagePiece(footprint, villagePieces)
                            || !ensureFootprintLoaded(level, footprint, loadBudget)) {
                        continue;
                    }

                    TerrainProfile terrain = terrainProfile(level, footprint.minX(), footprint.minZ(),
                            footprint.getXSpan(), footprint.getZSpan());
                    if (terrain == null) {
                        continue;
                    }
                    BlockPos origin = anchor.atY(terrain.placementY());
                    footprint = template.get().getBoundingBox(placementSettings, origin);
                    LibraryEntrance entrance = findTemplateEntrance(template.get(), origin,
                            rotation, footprint, bellPos,
                            pathBoxes);
                    sites.add(new LibrarySite(anchor, footprint, terrain.placementY(),
                            terrain.roughness(), rotation, entrance));
                }
            }
        }

        if (sites.isEmpty()) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Could not find a non-overlapping library site near village bell {}",
                    bellPos);
            return null;
        }

        sites.sort(Comparator.comparingInt(LibrarySite::roughness)
                .thenComparingInt((LibrarySite site) -> site.entrance().connectionCost())
                .thenComparingInt(site -> site.anchor().getX())
                .thenComparingInt(site -> site.anchor().getZ())
                .thenComparing(site -> site.rotation().name()));
        LibrarySite site = sites.getFirst();
        BlockPos origin = site.anchor().atY(site.placementY());
        return new PlannedLibrary(template.get(), origin, site.rotation(), site.footprint(),
                templateLocation, site.entrance().start(), site.entrance().direction());
    }

    /** Places the selected template after the pipeline has reserved its footprint. */
    public boolean place(ServerLevel level, PlannedLibrary plan, String biomeType) {
        try {
            BlockPos origin = plan.origin();
            int footprintX = plan.placementBox().getXSpan();
            int footprintZ = plan.placementBox().getZSpan();
            VillageEntityRelocation.relocateFromBuilding(level, List.of(plan.placementBox()));
            levelTerrain(level, plan.placementBox().minX(), plan.placementBox().minZ(),
                    footprintX, footprintZ,
                    origin.getY(), biomeType);
            clearAbove(level, plan.placementBox().minX(), plan.placementBox().minZ(),
                    footprintX, footprintZ,
                    origin.getY(), plan.template().getSize().getY());

            StructurePlaceSettings settings = placementSettings(plan.rotation())
                    .setIgnoreEntities(false);
            StructureProcessorList processors = level.registryAccess()
                    .registryOrThrow(Registries.PROCESSOR_LIST).get(LIBRARY_PROCESSORS);
            if (processors != null) {
                for (var processor : processors.list()) {
                    settings.addProcessor(processor);
                }
            }
            settings.addProcessor(JigsawReplacementProcessor.INSTANCE);

            boolean placed = plan.template().placeInWorld(level, origin, origin, settings,
                    RandomSource.create(level.getSeed() ^ origin.asLong()), 2);
            if (placed) {
                replaceJigsawBlocks(level, plan.placementBox(), origin.getY(),
                        plan.template().getSize().getY());
                installAuthoredBooks(level, plan);
            }
            if (!placed) {
                EmeraldCapitalism.LOGGER.warn(
                        "[ECAP] Vanilla library template {} refused placement at {}",
                        plan.templateLocation(), origin);
            } else {
                EmeraldCapitalism.LOGGER.info(
                        "[ECAP] Placed mandatory village library {} at {}",
                        plan.templateLocation(), origin);
            }
            return placed;
        } catch (Exception exception) {
            EmeraldCapitalism.LOGGER.error(
                    "[ECAP] Failed to place village library at {}",
                    plan.origin(), exception);
            return false;
        }
    }

    /** Replaces one vanilla shelf with a persistent chiseled shelf of real books. */
    private static void installAuthoredBooks(ServerLevel level, PlannedLibrary plan) {
        RandomSource random = RandomSource.create(level.getSeed() ^ plan.origin().asLong());
        List<LibraryBookDefinition> books = LibraryBookRegistry.selectLibraryBooks(random);
        if (books.isEmpty()) {
            EmeraldCapitalism.LOGGER.debug(
                    "No random-pool authored books are available for library at {}", plan.origin());
            return;
        }

        List<BlockPos> shelves = new ArrayList<>();
        BoundingBox box = plan.placementBox();
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    BlockPos position = new BlockPos(x, y, z);
                    if (level.getBlockState(position).is(Blocks.BOOKSHELF)) {
                        shelves.add(position);
                    }
                }
            }
        }
        if (shelves.isEmpty()) {
            EmeraldCapitalism.LOGGER.warn(
                    "Library at {} had no vanilla bookshelf available for authored books", plan.origin());
            return;
        }
        shelves.sort(Comparator.comparingLong(BlockPos::asLong));
        BlockPos shelfPosition = shelves.get(random.nextInt(shelves.size()));
        Direction facing = inferShelfFacing(level, shelfPosition);
        BlockState shelfState = Blocks.CHISELED_BOOKSHELF.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        level.setBlock(shelfPosition, shelfState, 3);

        if (!(level.getBlockEntity(shelfPosition) instanceof ChiseledBookShelfBlockEntity shelf)) {
            EmeraldCapitalism.LOGGER.warn(
                    "Chiseled bookshelf at {} did not create its block entity", shelfPosition);
            return;
        }
        for (int slot = 0; slot < books.size(); slot++) {
            shelf.setItem(slot, books.get(slot).createItemStack());
        }
        shelf.setChanged();
        level.sendBlockUpdated(shelfPosition, shelfState, shelfState, 3);
        EmeraldCapitalism.LOGGER.info(
                "Installed {} authored books in library shelf at {}", books.size(), shelfPosition);
    }

    private static Direction inferShelfFacing(ServerLevel level, BlockPos shelfPosition) {
        for (Direction wallDirection : Direction.Plane.HORIZONTAL) {
            if (!level.getBlockState(shelfPosition.relative(wallDirection)).isAir()
                    && level.getBlockState(shelfPosition.relative(wallDirection.getOpposite())).isAir()) {
                return wallDirection.getOpposite();
            }
        }
        return Direction.NORTH;
    }

    private static int rotatedSizeX(StructureTemplate template, Rotation rotation) {
        return rotation == Rotation.NONE || rotation == Rotation.CLOCKWISE_180
                ? template.getSize().getX() : template.getSize().getZ();
    }

    private static int rotatedSizeZ(StructureTemplate template, Rotation rotation) {
        return rotation == Rotation.NONE || rotation == Rotation.CLOCKWISE_180
                ? template.getSize().getZ() : template.getSize().getX();
    }

    private static StructurePlaceSettings placementSettings(Rotation rotation) {
        return new StructurePlaceSettings()
                .setRotation(rotation)
                .setMirror(Mirror.NONE);
    }

    private static BlockPos alignAnchorToFootprint(StructureTemplate template,
                                                    StructurePlaceSettings settings,
                                                    int desiredMinX, int desiredMinZ) {
        BlockPos initialAnchor = new BlockPos(desiredMinX, 0, desiredMinZ);
        BoundingBox initialBox = template.getBoundingBox(settings, initialAnchor);
        return initialAnchor.offset(desiredMinX - initialBox.minX(), 0,
                desiredMinZ - initialBox.minZ());
    }

    private static BoundingBox horizontalBox(int originX, int originZ, int sizeX, int sizeZ) {
        return new BoundingBox(originX, Integer.MIN_VALUE / 2, originZ,
                originX + sizeX - 1, Integer.MAX_VALUE / 2, originZ + sizeZ - 1);
    }

    private static boolean ensureFootprintLoaded(ServerLevel level, BoundingBox footprint,
                                                  ChunkLoadBudget loadBudget) {
        for (int chunkX = footprint.minX() >> 4; chunkX <= footprint.maxX() >> 4; chunkX++) {
            for (int chunkZ = footprint.minZ() >> 4; chunkZ <= footprint.maxZ() >> 4; chunkZ++) {
                if (!loadBudget.ensureLoaded(level, chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean overlapsVillagePiece(BoundingBox footprint, List<StructurePiece> pieces) {
        for (StructurePiece piece : pieces) {
            if (footprint.intersects(piece.getBoundingBox())) {
                return true;
            }
        }
        return false;
    }

    private static LibraryEntrance findTemplateEntrance(StructureTemplate template, BlockPos placePos,
                                                        Rotation rotation, BoundingBox footprint,
                                                        BlockPos villageCenter,
                                                        List<BoundingBox> pathBoxes) {
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation)
                .setMirror(Mirror.NONE);
        List<StructureTemplate.StructureBlockInfo> jigsaws = template.filterBlocks(
                placePos, settings, Blocks.JIGSAW);
        LibraryEntrance best = null;
        for (StructureTemplate.StructureBlockInfo jigsaw : jigsaws) {
            if (jigsaw.nbt() == null || !jigsaw.nbt().contains("final_state")) {
                continue;
            }
            String finalState = jigsaw.nbt().getString("final_state");
            String name = jigsaw.nbt().getString("name");
            boolean buildingEntrance = name.contains("building_entrance");
            if (!buildingEntrance && !finalState.contains("dirt_path")) {
                continue;
            }
            BlockPos start = jigsaw.pos();
            Direction direction = facingOutward(start, footprint);
            BlockPos pathStart = buildingEntrance ? start.relative(direction) : start;
            LibraryEntrance candidate = libraryEntrance(pathStart, direction,
                    villageCenter, pathBoxes);
            if (best == null || candidate.connectionCost() < best.connectionCost()) {
                best = candidate;
            }
        }
        if (best != null) {
            return best;
        }

        int centerX = (footprint.minX() + footprint.maxX()) / 2;
        int centerZ = (footprint.minZ() + footprint.maxZ()) / 2;
        int deltaX = villageCenter.getX() - centerX;
        int deltaZ = villageCenter.getZ() - centerZ;
        if (Math.abs(deltaX) >= Math.abs(deltaZ)) {
            return deltaX >= 0
                    ? libraryEntrance(new BlockPos(footprint.maxX() + 1, placePos.getY(), centerZ),
                    Direction.EAST, villageCenter, pathBoxes)
                    : libraryEntrance(new BlockPos(footprint.minX() - 1, placePos.getY(), centerZ),
                    Direction.WEST, villageCenter, pathBoxes);
        }
        return deltaZ >= 0
                ? libraryEntrance(new BlockPos(centerX, placePos.getY(), footprint.maxZ() + 1),
                Direction.SOUTH, villageCenter, pathBoxes)
                : libraryEntrance(new BlockPos(centerX, placePos.getY(), footprint.minZ() - 1),
                Direction.NORTH, villageCenter, pathBoxes);
    }

    private static LibraryEntrance libraryEntrance(BlockPos start, Direction direction,
                                                    BlockPos villageCenter,
                                                    List<BoundingBox> pathBoxes) {
        BlockPos pathTarget = findNearestRoadPoint(start, pathBoxes);
        if (pathTarget == null) {
            pathTarget = villageCenter;
        }
        int targetX = pathTarget.getX() - start.getX();
        int targetZ = pathTarget.getZ() - start.getZ();
        int frontDot = targetX * direction.getStepX() + targetZ * direction.getStepZ();
        double distance = Math.sqrt((double) targetX * targetX + (double) targetZ * targetZ);
        int connectionCost = (int) Math.min(Integer.MAX_VALUE,
                Math.round(distance * 10.0D) + (frontDot < 0 ? 10_000 : 0));
        return new LibraryEntrance(start, direction, connectionCost);
    }

    @Nullable
    private static BlockPos findNearestRoadPoint(BlockPos from, List<BoundingBox> pathBoxes) {
        BlockPos nearest = null;
        double nearestDistanceSq = Double.MAX_VALUE;
        for (BoundingBox box : pathBoxes) {
            int x = Math.max(box.minX(), Math.min(box.maxX(), from.getX()));
            int z = Math.max(box.minZ(), Math.min(box.maxZ(), from.getZ()));
            double distanceSq = (double) (x - from.getX()) * (x - from.getX())
                    + (double) (z - from.getZ()) * (z - from.getZ());
            if (distanceSq < nearestDistanceSq) {
                nearestDistanceSq = distanceSq;
                nearest = new BlockPos(x, from.getY(), z);
            }
        }
        return nearest;
    }

    private static List<BoundingBox> collectPathBoxes(List<StructurePiece> villagePieces) {
        List<BoundingBox> pathBoxes = new ArrayList<>();
        for (StructurePiece piece : villagePieces) {
            if (isPathPiece(piece)) {
                pathBoxes.add(piece.getBoundingBox());
            }
        }
        return pathBoxes;
    }

    private static boolean isPathPiece(StructurePiece piece) {
        if (piece instanceof PoolElementStructurePiece poolPiece) {
            String element = poolPiece.getElement().toString().toLowerCase(Locale.ROOT);
            return element.contains("/streets/") || element.contains("/street/");
        }
        return false;
    }

    private static Direction facingOutward(BlockPos entrance, BoundingBox footprint) {
        int centerX = (footprint.minX() + footprint.maxX()) / 2;
        int centerZ = (footprint.minZ() + footprint.maxZ()) / 2;
        int deltaX = entrance.getX() - centerX;
        int deltaZ = entrance.getZ() - centerZ;
        if (Math.abs(deltaX) >= Math.abs(deltaZ)) {
            return deltaX >= 0 ? Direction.EAST : Direction.WEST;
        }
        return deltaZ >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static void replaceJigsawBlocks(ServerLevel level, BoundingBox footprint,
                                            int placementY, int templateHeight) {
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                for (int y = placementY - 1; y <= placementY + templateHeight + 1; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.getBlockState(pos).getBlock() != Blocks.JIGSAW) {
                        continue;
                    }
                    BlockState replacement = Blocks.AIR.defaultBlockState();
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity instanceof JigsawBlockEntity jigsaw) {
                        String finalState = jigsaw.getFinalState();
                        if (finalState != null && !finalState.isEmpty()) {
                            String blockId = finalState.contains("[")
                                    ? finalState.substring(0, finalState.indexOf('[')) : finalState;
                            ResourceLocation id = ResourceLocation.tryParse(blockId);
                            if (id != null) {
                                replacement = BuiltInRegistries.BLOCK.getOptional(id)
                                        .map(Block::defaultBlockState)
                                        .orElse(Blocks.AIR.defaultBlockState());
                            }
                        }
                    }
                    level.removeBlockEntity(pos);
                    level.setBlock(pos, replacement, 2);
                }
            }
        }
    }

    @Nullable
    private static TerrainProfile terrainProfile(ServerLevel level, int originX, int originZ,
                                                 int sizeX, int sizeZ) {
        int[] heights = new int[sizeX * sizeZ];
        int index = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int x = originX; x < originX + sizeX; x++) {
            for (int z = originZ; z < originZ + sizeZ; z++) {
                int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (!level.getBlockState(new BlockPos(x, height, z)).getFluidState().isEmpty()) {
                    return null;
                }
                heights[index++] = height;
                min = Math.min(min, height);
                max = Math.max(max, height);
            }
        }
        java.util.Arrays.sort(heights);
        return new TerrainProfile(heights[heights.length / 2], max - min);
    }

    private static void levelTerrain(ServerLevel level, int originX, int originZ,
                                     int sizeX, int sizeZ, int targetY, String biomeType) {
        var fill = "DESERT".equals(biomeType) ? Blocks.SAND : Blocks.DIRT;
        var top = "DESERT".equals(biomeType) ? Blocks.SAND : Blocks.GRASS_BLOCK;
        for (int x = originX; x < originX + sizeX; x++) {
            for (int z = originZ; z < originZ + sizeZ; z++) {
                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (surfaceY > targetY) {
                    for (int y = targetY + 1; y <= surfaceY; y++) {
                        level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                } else if (surfaceY < targetY) {
                    for (int y = surfaceY + 1; y <= targetY; y++) {
                        level.setBlock(new BlockPos(x, y, z), fill.defaultBlockState(), 2);
                    }
                }
                level.setBlock(new BlockPos(x, targetY, z), top.defaultBlockState(), 2);
            }
        }
    }

    private static void clearAbove(ServerLevel level, int originX, int originZ,
                                   int sizeX, int sizeZ, int targetY, int height) {
        for (int x = originX; x < originX + sizeX; x++) {
            for (int z = originZ; z < originZ + sizeZ; z++) {
                for (int y = targetY + 1; y <= targetY + height + 2; y++) {
                    if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                        level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    public record PlannedLibrary(StructureTemplate template, BlockPos origin,
                                 Rotation rotation, BoundingBox placementBox,
                                 ResourceLocation templateLocation, BlockPos pathStart,
                                 Direction entranceDirection) {
        public BoundingBox reservationBox() {
            return new BoundingBox(placementBox.minX() - 2, Integer.MIN_VALUE / 2,
                    placementBox.minZ() - 2, placementBox.maxX() + 2,
                    Integer.MAX_VALUE / 2, placementBox.maxZ() + 2);
        }
    }

    private record LibrarySite(BlockPos anchor, BoundingBox footprint, int placementY,
                               int roughness, Rotation rotation,
                               LibraryEntrance entrance) {
    }

    private record LibraryEntrance(BlockPos start, Direction direction, int connectionCost) {
    }

    private record TerrainProfile(int placementY, int roughness) {
    }
}
