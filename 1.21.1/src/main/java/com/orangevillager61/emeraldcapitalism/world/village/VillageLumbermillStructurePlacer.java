package com.orangevillager61.emeraldcapitalism.world.village;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.ChunkLoadBudget;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.event.EventHooks;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/** Places and populates the guaranteed lumbermill building for a village. */
public final class VillageLumbermillStructurePlacer {
    private static final ResourceLocation PLAINS_LUMBERMILL_VARIANT_ONE =
            template("village/plains/houses/lumbermill_plains_1");
    private static final ResourceLocation PLAINS_LUMBERMILL_VARIANT_TWO =
            template("village/plains/houses/lumbermill_plains_2");
    private static final int PLAINS_VARIANT_TWO_CHANCE_PERCENT = 33;
    private static final Map<String, List<ResourceLocation>> LUMBERMILL_TEMPLATES =
            lumbermillTemplates();
    private static final int[] DISTANCES_FROM_BELL = {18, 26, 34, 42, 50, 58};
    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    /** Small perpendicular alternatives keep a road from eliminating an entire tree-facing side. */
    private static final int[] LATERAL_OFFSETS = {0, -18, 18};
    private static final Rotation[] ROTATIONS = {
            Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180,
            Rotation.COUNTERCLOCKWISE_90
    };
    private static final int VILLAGERS_PER_LUMBERMILL = 2;
    private static final int TREE_CACHE_CELL_SIZE = 4;
    private static final int[][] TREE_PERIMETER_POINTS = {
            {-1, -1}, {-1, 0}, {-1, 1},
            {0, -1},             {0, 1},
            {1, -1},  {1, 0},    {1, 1}
    };
    private static final int[] TREE_SAMPLE_MARGINS = {8, 24, 40, 56};
    private static final int[] TREE_SAMPLE_WEIGHTS = {4, 3, 2, 1};

    private static Map<String, List<ResourceLocation>> lumbermillTemplates() {
        ResourceLocation desert = template("village/desert/houses/lumbermill_desert_1");
        ResourceLocation savanna = template("village/savanna/houses/lumbermill_savanna_1");
        ResourceLocation taiga = template("village/taiga/houses/lumbermill_taiga_1");
        return Map.of(
                "DESERT", List.of(desert),
                "PLAINS", List.of(PLAINS_LUMBERMILL_VARIANT_ONE, PLAINS_LUMBERMILL_VARIANT_TWO),
                "SAVANNA", List.of(savanna),
                "TAIGA", List.of(taiga),
                // Snowy villages use the taiga template until a snowy-specific
                // lumbermill is authored. The resource remains single-sourced.
                "SNOWY", List.of(taiga));
    }

    private static ResourceLocation template(String path) {
        return ModIds.id(path);
    }

    /** Chooses a non-overlapping lumbermill site without mutating the world. */
    @Nullable
    public PlannedLumbermill plan(ServerLevel level, BlockPos bellPos, String biomeType,
                                  List<StructurePiece> villagePieces,
                                  VillageRoadPathGenerator.PreparedVillageRoads preparedRoads,
                                  ChunkLoadBudget loadBudget,
                                  Predicate<BoundingBox> reservedCollision) {
        List<ResourceLocation> templateLocations = templateLocationsFor(level, bellPos, biomeType);
        if (templateLocations == null) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] No lumbermill template is configured for biome {}", biomeType);
            return null;
        }

        List<LoadedTemplate> templates = new ArrayList<>();
        for (ResourceLocation location : templateLocations) {
            Optional<StructureTemplate> template = level.getStructureManager().get(location);
            if (template.isPresent()) {
                templates.add(new LoadedTemplate(location, template.get()));
            } else {
                EmeraldCapitalism.LOGGER.error(
                        "[ECAP] Cannot generate village lumbermill: missing template {}",
                        location);
            }
        }
        if (templates.isEmpty()) {
            return null;
        }

        List<LumbermillSite> sites = new ArrayList<>();
        for (LoadedTemplate loaded : templates) {
            for (int distance : DISTANCES_FROM_BELL) {
                for (int[] direction : DIRECTIONS) {
                    for (Rotation rotation : ROTATIONS) {
                        int rotatedX = rotatedSizeX(loaded.template(), rotation);
                        int rotatedZ = rotatedSizeZ(loaded.template(), rotation);
                        StructurePlaceSettings settings = placementSettings(rotation);
                        for (int lateralOffset : LATERAL_OFFSETS) {
                            int lateralX = -direction[1] * lateralOffset;
                            int lateralZ = direction[0] * lateralOffset;
                            int desiredMinX = bellPos.getX() + direction[0] * distance + lateralX
                                    - rotatedX / 2;
                            int desiredMinZ = bellPos.getZ() + direction[1] * distance + lateralZ
                                    - rotatedZ / 2;
                            BlockPos anchor = alignAnchorToFootprint(loaded.template(), settings,
                                    desiredMinX, desiredMinZ);
                            BoundingBox footprint = loaded.template().getBoundingBox(settings, anchor);
                            if (reservedCollision.test(footprint)
                                    || overlapsVillagePiece(footprint, villagePieces)
                                    || preparedRoads.intersectsStreet(footprint)
                                    || !ensureFootprintLoaded(level, footprint, loadBudget)) {
                                continue;
                            }

                            TerrainProfile terrain = terrainProfile(level, footprint.minX(), footprint.minZ(),
                                    footprint.getXSpan(), footprint.getZSpan());
                            if (terrain == null) {
                                continue;
                            }
                            BlockPos origin = anchor.atY(terrain.placementY());
                            footprint = loaded.template().getBoundingBox(settings, origin);
                            sites.add(new LumbermillSite(loaded, anchor, footprint,
                                    terrain.placementY(), terrain.roughness(), rotation, 0));
                        }
                    }
                }
            }
        }

        if (sites.isEmpty()) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Could not find a non-overlapping lumbermill site near village bell {}",
                    bellPos);
            return null;
        }

        applyTreePreference(level, biomeType, sites);
        sites.sort(Comparator.comparingInt(LumbermillSite::treeDensityScore).reversed()
                .thenComparingInt(LumbermillSite::roughness)
                .thenComparingInt(site -> site.anchor().getX())
                .thenComparingInt(site -> site.anchor().getZ())
                .thenComparing(site -> site.loaded().location().toString())
                .thenComparing(site -> site.rotation().name()));
        LumbermillSite site = sites.getFirst();
        BlockPos origin = site.anchor().atY(site.placementY());
        LumbermillEntrance entrance = findTemplateEntrance(level, site.loaded().template(), origin,
                site.rotation(), site.footprint(), bellPos);
        return new PlannedLumbermill(site.loaded().template(), origin, site.rotation(),
                site.footprint(), site.loaded().location(), entrance.pathStart(), entrance.direction());
    }

    /**
     * Selects one plains lumbermill variant from a stable world-generation roll.
     * The bell position distinguishes villages while the world seed keeps the
     * result stable across reloads and repeated generation attempts.
     */
    private static List<ResourceLocation> templateLocationsFor(ServerLevel level, BlockPos bellPos,
                                                                String biomeType) {
        List<ResourceLocation> configured = LUMBERMILL_TEMPLATES.get(biomeType);
        if (!"PLAINS".equals(biomeType) || configured == null) {
            return configured;
        }

        return List.of(isPlainsVariantTwoSelected(level.getSeed(), bellPos)
                ? PLAINS_LUMBERMILL_VARIANT_TWO
                : PLAINS_LUMBERMILL_VARIANT_ONE);
    }

    static boolean isPlainsVariantTwoSelected(long worldSeed, BlockPos bellPos) {
        return RandomSource.create(worldSeed ^ bellPos.asLong())
                .nextInt(100) < PLAINS_VARIANT_TWO_CHANCE_PERCENT;
    }

    /**
     * Adds a small local wood-density signal without expanding the placement search.
     * Only plains sites close to the flattest valid terrain are considered. Samples
     * closer to the building carry more weight, and the probe never loads chunks
     * that were not already available to the pipeline.
     */
    private static void applyTreePreference(ServerLevel level, String biomeType,
                                            List<LumbermillSite> sites) {
        if (!"PLAINS".equals(biomeType)) {
            return;
        }

        int minimumRoughness = sites.stream()
                .mapToInt(LumbermillSite::roughness)
                .min()
                .orElse(Integer.MAX_VALUE);
        TreeSampleCache cache = new TreeSampleCache(level);
        for (int index = 0; index < sites.size(); index++) {
            LumbermillSite site = sites.get(index);
            if (!LumbermillPlacementScoring.withinTreePreferenceTolerance(
                    site.roughness(), minimumRoughness)) {
                continue;
            }
            TreeDensityProfile density = treeDensityProfile(level, site.footprint(), cache);
            sites.set(index, site.withTreeDensityScore(density.normalizedScore()));
        }
    }

    private static TreeDensityProfile treeDensityProfile(ServerLevel level, BoundingBox footprint,
                                                         TreeSampleCache cache) {
        int weightedSignal = 0;
        int sampleWeight = 0;
        int centerX = (footprint.minX() + footprint.maxX()) / 2;
        int centerZ = (footprint.minZ() + footprint.maxZ()) / 2;
        for (int marginIndex = 0; marginIndex < TREE_SAMPLE_MARGINS.length; marginIndex++) {
            int margin = TREE_SAMPLE_MARGINS[marginIndex];
            int weight = TREE_SAMPLE_WEIGHTS[marginIndex];
            int minX = footprint.minX() - margin;
            int maxX = footprint.maxX() + margin;
            int minZ = footprint.minZ() - margin;
            int maxZ = footprint.maxZ() + margin;
            for (int[] point : TREE_PERIMETER_POINTS) {
                int x = point[0] < 0 ? minX : point[0] > 0 ? maxX : centerX;
                int z = point[1] < 0 ? minZ : point[1] > 0 ? maxZ : centerZ;
                int signal = cache.sample(x, z);
                if (signal < 0) {
                    continue;
                }
                weightedSignal += signal * weight;
                sampleWeight += weight;
            }
        }
        return new TreeDensityProfile(weightedSignal, sampleWeight);
    }

    /** Places the selected template after the pipeline has reserved its footprint. */
    public boolean place(ServerLevel level, PlannedLumbermill plan, String biomeType) {
        try {
            BlockPos origin = plan.origin();
            int footprintX = plan.placementBox().getXSpan();
            int footprintZ = plan.placementBox().getZSpan();
            VillageEntityRelocation.relocateFromBuilding(level, List.of(plan.placementBox()));
            levelTerrain(level, plan.placementBox().minX(), plan.placementBox().minZ(),
                    footprintX, footprintZ, origin.getY(), biomeType);
            clearAbove(level, plan.placementBox().minX(), plan.placementBox().minZ(),
                    footprintX, footprintZ, origin.getY(), plan.template().getSize().getY());

            StructurePlaceSettings settings = placementSettings(plan.rotation())
                    .setIgnoreEntities(false)
                    .addProcessor(JigsawReplacementProcessor.INSTANCE);
            boolean placed = plan.template().placeInWorld(level, origin, origin, settings,
                    RandomSource.create(level.getSeed() ^ origin.asLong()), 2);
            if (placed) {
                replaceJigsawBlocks(level, plan.placementBox(), origin.getY(),
                        plan.template().getSize().getY(), plan.rotation());
                correctEntranceStairFacing(level, plan);
                EmeraldCapitalism.LOGGER.info(
                        "[ECAP] Placed mandatory village lumbermill {} at {}",
                        plan.templateLocation(), origin);
            } else {
                EmeraldCapitalism.LOGGER.warn(
                        "[ECAP] Lumbermill template {} refused placement at {}",
                        plan.templateLocation(), origin);
            }
            return placed;
        } catch (Exception exception) {
            EmeraldCapitalism.LOGGER.error(
                    "[ECAP] Failed to place village lumbermill at {}",
                    plan.origin(), exception);
            return false;
        }
    }

    /**
     * The entrance stair's high side belongs at the door, with its low side
     * leading onto the outward path. Structure jigsaws store the authored
     * stair state, so fix only the stair at the resolved entrance after the
     * template rotation has been applied.
     */
    private static void correctEntranceStairFacing(ServerLevel level, PlannedLumbermill plan) {
        BlockPos stairPos = plan.pathStart().relative(plan.entranceDirection().getOpposite());
        BlockState state = level.getBlockState(stairPos);
        if (!(state.getBlock() instanceof StairBlock)
                || !state.hasProperty(StairBlock.FACING)) {
            return;
        }

        Direction expectedFacing = entranceStairFacing(plan.entranceDirection());
        BlockState correctedState = state.setValue(StairBlock.FACING, expectedFacing);
        if (state != correctedState) {
            level.setBlock(stairPos, correctedState, 2);
        }
        restorePairedEntranceStairs(level, stairPos, plan.entranceDirection(), correctedState);
    }

    /** Restores the second stair for the templates with a two-block-wide door. */
    private static void restorePairedEntranceStairs(ServerLevel level, BlockPos stairPos,
                                                    Direction pathDirection,
                                                    BlockState stairState) {
        Direction opposite = pathDirection.getOpposite();
        for (Direction side : new Direction[] {
                pathDirection.getClockWise(), pathDirection.getCounterClockWise()}) {
            BlockPos pairedStairPos = stairPos.relative(side);
            BlockPos doorColumn = pairedStairPos.relative(opposite);
            if (!isDoorColumn(level, doorColumn)) {
                continue;
            }

            BlockState pairedState = level.getBlockState(pairedStairPos);
            if (pairedState.getBlock() instanceof StairBlock) {
                if (pairedState.getValue(StairBlock.FACING) != stairState.getValue(StairBlock.FACING)) {
                    level.setBlock(pairedStairPos,
                            pairedState.setValue(StairBlock.FACING, stairState.getValue(StairBlock.FACING)), 2);
                }
            } else if (pairedState.isAir()) {
                level.setBlock(pairedStairPos, stairState, 2);
            }
        }
    }

    private static boolean isDoorColumn(ServerLevel level, BlockPos column) {
        return level.getBlockState(column).is(BlockTags.DOORS)
                || level.getBlockState(column.above()).is(BlockTags.DOORS)
                || level.getBlockState(column.below()).is(BlockTags.DOORS);
    }

    static Direction entranceStairFacing(Direction pathDirection) {
        return pathDirection.getOpposite();
    }

    /** Spawns the fixed pair for a placed lumbermill, using only safe floor positions. */
    public int spawnVillagers(ServerLevel level, BoundingBox buildingBox) {
        AABB entityBox = new AABB(buildingBox.minX(), buildingBox.minY(), buildingBox.minZ(),
                buildingBox.maxX() + 1.0D, buildingBox.maxY() + 1.0D, buildingBox.maxZ() + 1.0D);
        List<Villager> existingVillagers = level.getEntitiesOfClass(Villager.class, entityBox);
        int existing = existingVillagers.size();
        int needed = Math.max(0, VILLAGERS_PER_LUMBERMILL - existing);
        List<BlockPos> positions = findVillagerSpawnPositions(level, buildingBox, existingVillagers, needed);
        int spawned = 0;
        for (BlockPos position : positions) {
            Villager villager = EntityType.VILLAGER.create(level);
            if (villager == null) {
                continue;
            }
            villager.moveTo(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D,
                    level.getRandom().nextFloat() * 360.0F, 0.0F);
            EventHooks.finalizeMobSpawn(villager, level, level.getCurrentDifficultyAt(position),
                    MobSpawnType.STRUCTURE, null);
            if (level.addFreshEntity(villager)) {
                spawned++;
            }
            if (spawned >= needed) {
                break;
            }
        }
        int total = existing + spawned;
        if (total != VILLAGERS_PER_LUMBERMILL) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Lumbermill at {} spawned {}/{} villagers",
                    buildingBox.getCenter(), total, VILLAGERS_PER_LUMBERMILL);
        }
        return total;
    }

    private static List<BlockPos> findVillagerSpawnPositions(ServerLevel level, BoundingBox buildingBox,
                                                             List<Villager> existingVillagers, int needed) {
        if (needed == 0) {
            return List.of();
        }
        List<BlockPos> candidates = new ArrayList<>();
        int minY = Math.max(level.getMinBuildHeight() + 1, buildingBox.minY());
        int maxY = Math.min(level.getMaxBuildHeight() - 2, buildingBox.maxY());
        for (int y = minY; y <= maxY; y++) {
            for (int x = buildingBox.minX(); x <= buildingBox.maxX(); x++) {
                for (int z = buildingBox.minZ(); z <= buildingBox.maxZ(); z++) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (isSafeVillagerSpawn(level, candidate)
                            && existingVillagers.stream().noneMatch(villager ->
                            villager.blockPosition().distSqr(candidate) < 1.0D)) {
                        candidates.add(candidate);
                    }
                }
            }
        }

        BlockPos center = new BlockPos(buildingBox.getCenter().getX(), buildingBox.minY(),
                buildingBox.getCenter().getZ());
        candidates.sort(Comparator.comparingDouble(position -> position.distSqr(center)));
        List<BlockPos> selected = new ArrayList<>(needed);
        for (BlockPos candidate : candidates) {
            if (selected.stream().noneMatch(existing -> existing.distSqr(candidate) < 1.0D)) {
                selected.add(candidate);
            }
            if (selected.size() >= needed) {
                return selected;
            }
        }

        // A structure can have a decorative floor that is not a full face. Use
        // the surrounding terrain as a bounded fallback rather than spawning in air.
        for (int radius = 1; radius <= 8 && selected.size() < needed; radius++) {
            for (int dx = -radius; dx <= radius && selected.size() < needed; dx++) {
                for (int dz = -radius; dz <= radius && selected.size() < needed; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int x = center.getX() + dx;
                    int z = center.getZ() + dz;
                    int floorY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    BlockPos candidate = new BlockPos(x, floorY + 1, z);
                    if (isSafeVillagerSpawn(level, candidate)
                            && existingVillagers.stream().noneMatch(villager ->
                            villager.blockPosition().distSqr(candidate) < 1.0D)
                            && selected.stream().noneMatch(existing -> existing.distSqr(candidate) < 1.0D)) {
                        selected.add(candidate);
                    }
                }
            }
        }
        return selected;
    }

    private static boolean isSafeVillagerSpawn(ServerLevel level, BlockPos pos) {
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        BlockState below = level.getBlockState(pos.below());
        return (feet.isAir() || feet.canBeReplaced())
                && (head.isAir() || head.canBeReplaced())
                && feet.getFluidState().isEmpty()
                && head.getFluidState().isEmpty()
                && below.getFluidState().isEmpty()
                && below.isFaceSturdy(level, pos.below(), Direction.UP);
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
        return new StructurePlaceSettings().setRotation(rotation).setMirror(Mirror.NONE);
    }

    private static BlockPos alignAnchorToFootprint(StructureTemplate template,
                                                    StructurePlaceSettings settings,
                                                    int desiredMinX, int desiredMinZ) {
        BlockPos initialAnchor = new BlockPos(desiredMinX, 0, desiredMinZ);
        BoundingBox initialBox = template.getBoundingBox(settings, initialAnchor);
        return initialAnchor.offset(desiredMinX - initialBox.minX(), 0,
                desiredMinZ - initialBox.minZ());
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

    /** Finds the authored road jigsaw, falling back to the edge facing the village. */
    private static LumbermillEntrance findTemplateEntrance(ServerLevel level, StructureTemplate template,
                                                            BlockPos placePos, Rotation rotation,
                                                            BoundingBox footprint, BlockPos villageCenter) {
        StructurePlaceSettings settings = placementSettings(rotation);
        for (StructureTemplate.StructureBlockInfo jigsaw
                : template.filterBlocks(placePos, settings, Blocks.JIGSAW)) {
            if (jigsaw.nbt() == null
                    || !"minecraft:building_entrance".equals(jigsaw.nbt().getString("name"))) {
                continue;
            }
            Direction direction = authoredFacing(level, jigsaw.nbt().getString("final_state"))
                    .map(rotation::rotate)
                    .orElseGet(() -> facingOutward(jigsaw.pos(), footprint));
            return new LumbermillEntrance(jigsaw.pos().relative(direction), direction);
        }

        int centerX = (footprint.minX() + footprint.maxX()) / 2;
        int centerZ = (footprint.minZ() + footprint.maxZ()) / 2;
        int deltaX = villageCenter.getX() - centerX;
        int deltaZ = villageCenter.getZ() - centerZ;
        if (Math.abs(deltaX) >= Math.abs(deltaZ)) {
            return deltaX >= 0
                    ? new LumbermillEntrance(new BlockPos(footprint.maxX() + 1, placePos.getY(), centerZ),
                    Direction.EAST)
                    : new LumbermillEntrance(new BlockPos(footprint.minX() - 1, placePos.getY(), centerZ),
                    Direction.WEST);
        }
        return deltaZ >= 0
                ? new LumbermillEntrance(new BlockPos(centerX, placePos.getY(), footprint.maxZ() + 1),
                Direction.SOUTH)
                : new LumbermillEntrance(new BlockPos(centerX, placePos.getY(), footprint.minZ() - 1),
                Direction.NORTH);
    }

    private static Optional<Direction> authoredFacing(ServerLevel level, String finalState) {
        if (finalState == null || finalState.isEmpty()) {
            return Optional.empty();
        }
        try {
            BlockState state = BlockStateParser.parseForBlock(
                    level.registryAccess().lookupOrThrow(Registries.BLOCK), finalState, false).blockState();
            if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
                return Optional.of(state.getValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING));
            }
        } catch (CommandSyntaxException ignored) {
            // A structure-void entrance has no facing; use its transformed edge instead.
        }
        return Optional.empty();
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

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
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
        BlockState fill = "DESERT".equals(biomeType)
                ? Blocks.SAND.defaultBlockState() : Blocks.DIRT.defaultBlockState();
        BlockState top = "DESERT".equals(biomeType)
                ? Blocks.SAND.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
        for (int x = originX; x < originX + sizeX; x++) {
            for (int z = originZ; z < originZ + sizeZ; z++) {
                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (surfaceY > targetY) {
                    for (int y = targetY + 1; y <= surfaceY; y++) {
                        level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                } else if (surfaceY < targetY) {
                    for (int y = surfaceY + 1; y <= targetY; y++) {
                        level.setBlock(new BlockPos(x, y, z), fill, 2);
                    }
                }
                level.setBlock(new BlockPos(x, targetY, z), top, 2);
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

    private static void replaceJigsawBlocks(ServerLevel level, BoundingBox footprint,
                                            int placementY, int templateHeight, Rotation rotation) {
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
                                try {
                                    replacement = rotateBlockState(BlockStateParser.parseForBlock(
                                            level.registryAccess().lookupOrThrow(Registries.BLOCK),
                                            finalState, false).blockState(), rotation);
                                } catch (CommandSyntaxException exception) {
                                    replacement = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                            .getOptional(id).map(net.minecraft.world.level.block.Block::defaultBlockState)
                                            .orElse(Blocks.AIR.defaultBlockState());
                                }
                            }
                        }
                    }
                    level.removeBlockEntity(pos);
                    level.setBlock(pos, replacement, 2);
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static BlockState rotateBlockState(BlockState state, Rotation rotation) {
        return state.rotate(rotation);
    }

    private static final class TreeSampleCache {
        private final ServerLevel level;
        private final Map<Long, Integer> samples = new HashMap<>();

        private TreeSampleCache(ServerLevel level) {
            this.level = level;
        }

        /** Returns a weighted signal, or -1 when the sample's chunk is unavailable. */
        private int sample(int x, int z) {
            int cellX = Math.floorDiv(x, TREE_CACHE_CELL_SIZE);
            int cellZ = Math.floorDiv(z, TREE_CACHE_CELL_SIZE);
            long key = columnKey(cellX, cellZ);
            Integer cached = samples.get(key);
            if (cached != null) {
                return cached;
            }

            int sampleX = cellX * TREE_CACHE_CELL_SIZE + TREE_CACHE_CELL_SIZE / 2;
            int sampleZ = cellZ * TREE_CACHE_CELL_SIZE + TREE_CACHE_CELL_SIZE / 2;
            if (!level.hasChunk(sampleX >> 4, sampleZ >> 4)) {
                samples.put(key, -1);
                return -1;
            }

            int noLeavesY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    sampleX, sampleZ) - 1;
            int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                    sampleX, sampleZ) - 1;
            if (noLeavesY < level.getMinBuildHeight() || surfaceY < level.getMinBuildHeight()) {
                samples.put(key, -1);
                return -1;
            }

            BlockState noLeavesState = level.getBlockState(new BlockPos(sampleX, noLeavesY, sampleZ));
            BlockState surfaceState = level.getBlockState(new BlockPos(sampleX, surfaceY, sampleZ));
            int signal = 0;
            if (noLeavesState.is(BlockTags.LOGS) && !isCherryWood(noLeavesState)) {
                signal += 3;
            }
            if (surfaceState.is(BlockTags.LEAVES) && !isCherryLeaves(surfaceState)) {
                signal += 2;
            } else if (!noLeavesState.is(BlockTags.LOGS)
                    && surfaceState.is(BlockTags.LOGS)
                    && !isCherryWood(surfaceState)) {
                signal += 3;
            }
            samples.put(key, signal);
            return signal;
        }
    }

    private static boolean isCherryWood(BlockState state) {
        return state.is(Blocks.CHERRY_LOG) || state.is(Blocks.STRIPPED_CHERRY_LOG);
    }

    private static boolean isCherryLeaves(BlockState state) {
        return state.is(Blocks.CHERRY_LEAVES);
    }

    public record PlannedLumbermill(StructureTemplate template, BlockPos origin,
                                    Rotation rotation, BoundingBox placementBox,
                                    ResourceLocation templateLocation, BlockPos pathStart,
                                    Direction entranceDirection) {
        public BoundingBox reservationBox() {
            return new BoundingBox(placementBox.minX() - 2, Integer.MIN_VALUE / 2,
                    placementBox.minZ() - 2, placementBox.maxX() + 2,
                    Integer.MAX_VALUE / 2, placementBox.maxZ() + 2);
        }
    }

    private record LoadedTemplate(ResourceLocation location,
                                  StructureTemplate template) {
    }

    private record LumbermillSite(LoadedTemplate loaded, BlockPos anchor, BoundingBox footprint,
                                  int placementY, int roughness, Rotation rotation,
                                  int treeDensityScore) {
        private LumbermillSite withTreeDensityScore(int score) {
            return new LumbermillSite(loaded, anchor, footprint, placementY, roughness,
                    rotation, score);
        }
    }

    private record LumbermillEntrance(BlockPos pathStart, Direction direction) {
    }

    private record TerrainProfile(int placementY, int roughness) {
    }

    private record TreeDensityProfile(int weightedSignal, int sampleCount) {
        private int normalizedScore() {
            return LumbermillPlacementScoring.normalizeTreeSignal(weightedSignal, sampleCount);
        }
    }
}
