package com.orangevillager61.emeraldcapitalism.world.villagefarms;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.*;

/**
 * Places farm structures into the world with terrain adaptation.
 * Loads vanilla farm NBT templates and applies biome-appropriate processors.
 */
public class VillageFarmPlacer {

    private static final int[][] HORIZONTAL_OFFSETS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final int[][] WATER_ESCAPE_OFFSETS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}
    };
    // Leaves enough room for 32 configured farms plus their deferred paths while
    // keeping the complete standardized pipeline below 100 active ticks.
    private static final int WATER_SCAN_TARGET_TICKS = 16;
    private static final int MIN_WATER_SCAN_BLOCKS_PER_TICK = 4_096;

    private static final Map<String, BiomeTerrainInfo> BIOME_TERRAIN = new HashMap<>();

    static {
        // Flat biomes: full leveling to median Y
        BIOME_TERRAIN.put("PLAINS", new BiomeTerrainInfo(Blocks.DIRT, Blocks.GRASS_BLOCK, null, false));
        BIOME_TERRAIN.put("DESERT", new BiomeTerrainInfo(Blocks.SAND, Blocks.SAND, null, false));
        BIOME_TERRAIN.put("SNOWY", new BiomeTerrainInfo(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.SNOW, false));
        // Hilly biomes use gentle leveling, smoothing only within a few blocks of median
        BIOME_TERRAIN.put("SAVANNA", new BiomeTerrainInfo(Blocks.DIRT, Blocks.GRASS_BLOCK, null, true));
        BIOME_TERRAIN.put("TAIGA", new BiomeTerrainInfo(Blocks.DIRT, Blocks.GRASS_BLOCK, null, true));
    }

    private record BiomeTerrainInfo(Block fill, Block top, Block snowLayer, boolean hilly) {}

    private record SurfaceSnapshot(int[] heights, int footprintZ, int placementY, int maxHeight) {
        private int heightAt(int dx, int dz) {
            return heights[dx * footprintZ + dz];
        }
    }

    /**
     * Place a farm structure at the given placement location.
     *
     * @param level     the server level
     * @param placement the placement descriptor
     * @param biomeType the biome type for terrain fill
     * @param budget chunk loading budget for force-loading unloaded chunks
     * @return placement info if successful, or null if placement failed
     */
    public PlacedFarmInfo place(ServerLevel level, FarmPlacement placement, String biomeType,
                                ChunkLoadBudget budget,
                                VillageFarmSiteSelector.VillageSpatialCache spatialCache) {
        try {
            StructureTemplateManager templateManager = level.getStructureManager();

            // Load the template
            Optional<StructureTemplate> templateOpt = templateManager.get(placement.templateLocation());
            if (templateOpt.isEmpty()) {
                EmeraldCapitalism.LOGGER.warn("[ECAP] Could not load farm template: {}",
                        placement.templateLocation());
                return null;
            }

            StructureTemplate template = templateOpt.get();

            // Check if all required chunks are loaded (Option A: skip if not)
            int originX = placement.origin().getX();
            int originZ = placement.origin().getZ();
            int minChunkX = originX >> 4;
            int maxChunkX = (originX + placement.footprintX() - 1) >> 4;
            int minChunkZ = originZ >> 4;
            int maxChunkZ = (originZ + placement.footprintZ() - 1) >> 4;

            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    if (!budget.ensureLoaded(level, cx, cz)) {
                        return null;
                    }
                }
            }

            // Revalidate immediately before terrain leveling: farms belong beside
            // village roads and must never replace either a road structure piece or
            // an already-generated path surface.
            if (VillageFarmSiteSelector.countPathOverlap(originX, originZ,
                    placement.footprintX(), placement.footprintZ(), spatialCache.pathPieces()) > 0
                    || VillageFarmSiteSelector.overlapsExistingPathSurface(level, originX, originZ,
                    placement.footprintX(), placement.footprintZ())) {
                return null;
            }

            // Fallback: check for blacklisted blocks before committing to placement
            if (containsBlacklistedBlocks(level, originX, originZ,
                    placement.footprintX(), placement.footprintZ(), spatialCache.blacklist())) {
                return null;
            }

            if (overlapsVillageBuilding(originX, originZ, placement.footprintX(),
                    placement.footprintZ(), spatialCache.paddedVillageBuildingBoxes())) {
                return null;
            }

            BiomeTerrainInfo terrain = BIOME_TERRAIN.getOrDefault(biomeType,
                    new BiomeTerrainInfo(Blocks.DIRT, Blocks.GRASS_BLOCK, null, false));

            // Find median surface Y across the footprint for leveling
            SurfaceSnapshot surfaceSnapshot = captureSurfaceSnapshot(level, originX, originZ,
                    placement.footprintX(), placement.footprintZ());
            int placementY = surfaceSnapshot.placementY();

            // Level the terrain: hilly biomes get gentle smoothing, flat biomes get full leveling
            levelTerrain(level, originX, originZ, placement.footprintX(), placement.footprintZ(),
                    placementY, terrain, surfaceSnapshot);

            // Clear vegetation and obstructions above the farm.
            // Template height tells us how tall the structure is; clear a few extra
            // blocks above that to remove tree canopies and floating leaves.
            int templateHeight = template.getSize().getY();
            clearAboveFarm(level, originX, originZ, placement.footprintX(), placement.footprintZ(),
                    placementY, templateHeight, surfaceSnapshot.maxHeight());

            // Build placement settings
            StructurePlaceSettings settings = new StructurePlaceSettings()
                    .setRotation(placement.rotation())
                    .setMirror(Mirror.NONE)
                    .setIgnoreEntities(false);

            // Look up and apply processor list
            Registry<StructureProcessorList> processorRegistry = level.registryAccess()
                    .registryOrThrow(Registries.PROCESSOR_LIST);
            StructureProcessorList processors = processorRegistry.get(placement.processorList());
            if (processors != null) {
                for (var processor : processors.list()) {
                    settings.addProcessor(processor);
                }
            } else {
                EmeraldCapitalism.LOGGER.warn("[ECAP] Could not find processor list: {}",
                        placement.processorList());
            }

            // Add jigsaw replacement processor: converts jigsaw blocks to their
            // "turns into" block (e.g. dirt_path) during placement, just like vanilla worldgen
            settings.addProcessor(JigsawReplacementProcessor.INSTANCE);

            // Place the structure
            // For rotated templates, placeInWorld expects the transformed template origin,
            // not the desired min corner of the farm footprint. Convert our footprint-min
            // anchor to the correct transformed origin so terrain clearing/leveling aligns
            // with the actual placed farm.
            // Flag 2 = BLOCK_UPDATE suppressed, standard for worldgen placement
            BlockPos desiredMinCorner = new BlockPos(originX, placementY, originZ);
            BlockPos placePos = StructureTemplate.getZeroPositionWithTransform(
                    desiredMinCorner, Mirror.NONE, placement.rotation(),
                    template.getSize().getX(), template.getSize().getZ());
            RandomSource random = level.getRandom();
            boolean placed = template.placeInWorld(level, placePos, placePos, settings, random, 2);

            if (placed) {
                // Replace jigsaw blocks with their "turns into" (final_state) block
                replaceJigsawBlocks(level, originX, originZ,
                        placement.footprintX(), placement.footprintZ(), placementY);

                // Contain water: place dirt blocks outside the footprint boundary
                // wherever water would flow out (e.g. terrain drops off on a hillside)
                containWater(level, originX, originZ,
                        placement.footprintX(), placement.footprintZ(),
                        placementY, templateHeight);

                // Run this after water containment as well: the containment pass
                // can update blocks adjacent to irrigation channels. Reapply only
                // crop positions declared by the source template.
                repairTemplateCrops(level, template, placePos, settings,
                        originX, originZ, placementY,
                        placement.footprintX(), placement.footprintZ());

                // Verify composters exist (farmer POI blocks)
                int composterCount = countCompostersInFootprint(level, originX, originZ,
                        placement.footprintX(), placement.footprintZ(), placementY);
                if (composterCount == 0) {
                    EmeraldCapitalism.LOGGER.warn(
                            "[ECAP] Farm at ({}, {}, {}) contains no composters, " +
                                    "farmers may not be able to claim this as a job site",
                            originX, placementY, originZ);
                } else {
                    EmeraldCapitalism.LOGGER.debug(
                            "[ECAP] Farm at ({}, {}, {}) has {} composters",
                            originX, placementY, originZ, composterCount);
                }

                EmeraldCapitalism.LOGGER.info(
                        "[ECAP] Placed farm {} at footprint ({}, {}, {}), template origin ({}, {}, {}) with rotation {}",
                        placement.templateLocation(), originX, placementY, originZ,
                        placePos.getX(), placePos.getY(), placePos.getZ(), placement.rotation());

                return new PlacedFarmInfo(originX, originZ, placementY,
                        placement.footprintX(), placement.footprintZ(), templateHeight);
            }

            return null;
        } catch (Exception e) {
            EmeraldCapitalism.LOGGER.error("[ECAP] Failed to place farm at {}: {}",
                    placement.origin(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Restores the crop/support pairs that the source template contains.
     *
     * <p>Farm templates are authored as a flat structure, but a farm can be
     * selected over terrain with several different surface heights. After the
     * template is placed, the shape-update pass may remove a crop whose support
     * block was affected by that terrain transition. Reading the crop positions
     * from the template keeps this repair limited to intended crop blocks and
     * preserves the crop type for each position.</p>
     */
    private void repairTemplateCrops(ServerLevel level, StructureTemplate template,
                                     BlockPos placePos, StructurePlaceSettings settings,
                                     int originX, int originZ, int placementY,
                                     int footprintX, int footprintZ) {
        for (Block cropBlock : BuiltInRegistries.BLOCK) {
            if (!cropBlock.defaultBlockState().is(BlockTags.CROPS)) {
                continue;
            }

            for (StructureTemplate.StructureBlockInfo cropInfo
                    : template.filterBlocks(placePos, settings, cropBlock)) {
                BlockPos cropPos = cropInfo.pos();
                if (cropPos.getX() < originX || cropPos.getX() >= originX + footprintX
                        || cropPos.getZ() < originZ || cropPos.getZ() >= originZ + footprintZ
                        || cropPos.getY() < placementY
                        || cropPos.getY() >= placementY + template.getSize().getY()) {
                    continue;
                }

                BlockPos soilPos = cropPos.below();
                if (!level.getBlockState(soilPos).is(Blocks.FARMLAND)) {
                    level.setBlock(soilPos, Blocks.FARMLAND.defaultBlockState(), 2);
                }
                if (!level.getBlockState(cropPos).is(cropBlock)) {
                    level.setBlock(cropPos, cropInfo.state(), 2);
                }
            }
        }
    }

    private boolean overlapsVillageBuilding(int x, int z, int footprintX, int footprintZ,
                                            List<BoundingBox> paddedBuildingBoxes) {
        BoundingBox farmBB = new BoundingBox(x, 0, z, x + footprintX - 1, 255, z + footprintZ - 1);
        for (BoundingBox padded : paddedBuildingBoxes) {
            if (farmBB.intersects(padded)) {
                return true;
            }
        }
        return false;
    }

    private SurfaceSnapshot captureSurfaceSnapshot(ServerLevel level, int x, int z,
                                                   int footprintX, int footprintZ) {
        int[] heights = new int[footprintX * footprintZ];
        List<Integer> medianSamples = new ArrayList<>();
        int maxHeight = Integer.MIN_VALUE;
        for (int dx = 0; dx < footprintX; dx++) {
            for (int dz = 0; dz < footprintZ; dz++) {
                int height = level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz) - 1;
                heights[dx * footprintZ + dz] = height;
                maxHeight = Math.max(maxHeight, height);
                if ((dx & 1) == 0 && (dz & 1) == 0) {
                    medianSamples.add(height);
                }
            }
        }
        Collections.sort(medianSamples);
        return new SurfaceSnapshot(heights, footprintZ,
                medianSamples.get(medianSamples.size() / 2), maxHeight);
    }

    /**
     * Levels terrain under a farm footprint.
     * <p>
     * The footprint itself is always fully leveled to placementY so the template
     * has a flat foundation (required for crops, water channels, etc.).
     * For hilly biomes (savanna, taiga), a 3-block buffer zone around the
     * footprint is gently blended to avoid harsh cliff walls.
     */
    private void levelTerrain(ServerLevel level, int x, int z, int footprintX, int footprintZ,
                               int placementY, BiomeTerrainInfo terrain,
                               SurfaceSnapshot surfaceSnapshot) {
        // Always fully level the footprint itself
        for (int bx = x; bx < x + footprintX; bx++) {
            for (int bz = z; bz < z + footprintZ; bz++) {
                int surfaceY = surfaceSnapshot.heightAt(bx - x, bz - z);
                levelColumn(level, bx, bz, surfaceY, placementY, terrain);
            }
        }

        // For hilly biomes, blend a buffer zone around the footprint to reduce
        // harsh cliff walls where the leveled area meets natural terrain
        if (terrain.hilly) {
            int buffer = 3;
            for (int bx = x - buffer; bx < x + footprintX + buffer; bx++) {
                for (int bz = z - buffer; bz < z + footprintZ + buffer; bz++) {
                    // Skip blocks inside the footprint (already leveled)
                    if (bx >= x && bx < x + footprintX && bz >= z && bz < z + footprintZ) {
                        continue;
                    }
                    int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz) - 1;
                    int diff = surfaceY - placementY;
                    if (Math.abs(diff) <= 1) continue; // close enough, no blending needed

                    // Blend toward placementY: bring the column halfway closer
                    int targetY = placementY + diff / 2;
                    levelColumn(level, bx, bz, surfaceY, targetY, terrain);
                }
            }
        }
    }

    private void levelColumn(ServerLevel level, int bx, int bz, int surfaceY, int targetY,
                              BiomeTerrainInfo terrain) {
        if (surfaceY > targetY) {
            // Carve down: clear blocks above target Y
            for (int by = surfaceY; by > targetY; by--) {
                level.setBlock(new BlockPos(bx, by, bz), Blocks.AIR.defaultBlockState(), 2);
            }
            // Do not leave an arbitrary carved block (or an air pocket) as the
            // farm foundation. The template's farmland is placed on this plane,
            // so its support must be a solid, deterministic surface block.
            level.setBlock(new BlockPos(bx, targetY, bz), terrain.top.defaultBlockState(), 2);
        } else if (surfaceY < targetY) {
            // Fill up: place fill blocks from surface+1 up to target Y
            for (int by = surfaceY + 1; by < targetY; by++) {
                level.setBlock(new BlockPos(bx, by, bz), terrain.fill.defaultBlockState(), 2);
            }
            // Top block at target Y
            level.setBlock(new BlockPos(bx, targetY, bz), terrain.top.defaultBlockState(), 2);
        }
    }

    /**
     * Clears solid blocks (dirt, sand, gravel, logs, etc.) above the farm's ground
     * plane so terrain doesn't poke through the structure. Starts clearing from
     * one block above placementY (the farm floor) and continues up through the
     * full template height plus 10 extra blocks above that. It also clears a one-
     * block perimeter so hillside overhangs cannot shade or intrude into edge crop
     * rows. Leaves are left alone since they decay naturally.
     */
    private void clearAboveFarm(ServerLevel level, int x, int z, int footprintX, int footprintZ,
                                 int placementY, int templateHeight, int originalMaxSurfaceY) {
        int clearFrom = placementY + 1;
        int templateClearTo = placementY + templateHeight + 10;
        int clearTo = Math.max(templateClearTo, originalMaxSurfaceY + 12);
        int sideBuffer = 1;

        for (int bx = x - sideBuffer; bx < x + footprintX + sideBuffer; bx++) {
            for (int bz = z - sideBuffer; bz < z + footprintZ + sideBuffer; bz++) {
                for (int by = clearFrom; by <= clearTo; by++) {
                    BlockPos pos = new BlockPos(bx, by, bz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;

                    Block block = state.getBlock();

                    // Never remove bedrock or the bottom of the world.
                    if (block == Blocks.BEDROCK || by <= level.getMinBuildHeight()) {
                        break;
                    }

                    // Skip leaves: they'll decay naturally
                    if (state.is(net.minecraft.tags.BlockTags.LEAVES)) {
                        continue;
                    }

                    // Clear everything else: dirt, sand, gravel, logs, grass, etc.
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    /**
     * Fallback check: scans the footprint for blocks that don't generate naturally.
     * If any configured blacklisted block is found, the site likely overlaps an
     * unregistered structure.
     */
    private boolean containsBlacklistedBlocks(ServerLevel level, int x, int z,
                                               int footprintX, int footprintZ,
                                               Set<Block> blacklist) {
        if (blacklist.isEmpty()) {
            return false;
        }

        int step = 2;
        for (int bx = x; bx < x + footprintX; bx += step) {
            for (int bz = z; bz < z + footprintZ; bz += step) {
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz) - 1;
                for (int dy = 0; dy >= -3; dy--) {
                    Block block = level.getBlockState(new BlockPos(bx, surfaceY + dy, bz)).getBlock();
                    if (blacklist.contains(block)) {
                        EmeraldCapitalism.LOGGER.debug(
                                "[ECAP] Found blacklisted block {} at ({}, {}, {})",
                                BuiltInRegistries.BLOCK.getKey(block), bx, surfaceY + dy, bz);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private int countCompostersInFootprint(ServerLevel level, int x, int z,
                                            int footprintX, int footprintZ, int placementY) {
        int count = 0;
        // Search in a reasonable Y range around the placement
        for (int bx = x; bx < x + footprintX; bx++) {
            for (int bz = z; bz < z + footprintZ; bz++) {
                for (int by = placementY - 1; by <= placementY + 5; by++) {
                    if (level.getBlockState(new BlockPos(bx, by, bz)).getBlock() == Blocks.COMPOSTER) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Replace all jigsaw blocks in the placed farm footprint with their "turns into" block.
     * Vanilla does this via the jigsaw_replacement processor during worldgen, but since we
     * place templates manually with placeInWorld(), jigsaw blocks remain as-is.
     */
    private void replaceJigsawBlocks(ServerLevel level, int x, int z,
                                      int footprintX, int footprintZ, int placementY) {
        int replaced = 0;

        for (int bx = x; bx < x + footprintX; bx++) {
            for (int bz = z; bz < z + footprintZ; bz++) {
                // Jigsaw blocks can be at or slightly above placement level
                for (int by = placementY - 1; by <= placementY + 5; by++) {
                    BlockPos pos = new BlockPos(bx, by, bz);
                    if (level.getBlockState(pos).getBlock() != Blocks.JIGSAW) {
                        continue;
                    }

                    // Read the final_state from the jigsaw block entity
                    BlockEntity be = level.getBlockEntity(pos);
                    BlockState replacement = Blocks.AIR.defaultBlockState();

                    if (be instanceof JigsawBlockEntity jigsawBE) {
                        String finalStateStr = jigsawBE.getFinalState();
                        if (finalStateStr != null && !finalStateStr.isEmpty()) {
                            // final_state is a block state string like "minecraft:dirt_path"
                            // Strip any block state properties for simple lookup
                            String blockId = finalStateStr.contains("[")
                                    ? finalStateStr.substring(0, finalStateStr.indexOf('['))
                                    : finalStateStr;
                            ResourceLocation rl = ResourceLocation.tryParse(blockId);
                            if (rl != null) {
                                Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(rl);
                                if (block.isPresent()) {
                                    replacement = block.get().defaultBlockState();
                                } else {
                                    EmeraldCapitalism.LOGGER.debug(
                                            "[ECAP] Unknown block in jigsaw final_state '{}' at {}, using air",
                                            finalStateStr, pos);
                                }
                            }
                        }
                    }

                    // Remove the block entity first, then set the replacement block
                    level.removeBlockEntity(pos);
                    level.setBlock(pos, replacement, 2);
                    replaced++;
                }
            }
        }

        if (replaced > 0) {
            EmeraldCapitalism.LOGGER.debug(
                    "[ECAP] Replaced {} jigsaw blocks in farm at ({}, {}, {})",
                    replaced, x, placementY, z);
        }
    }

    /**
     * Scans the entire footprint for water blocks and ensures every neighbor
     * (4 cardinal directions + below) is solid. If a neighbor is non-solid
     * (air, grass, flowing water escape route), it gets plugged with dirt.
     * For downward and outward gaps, builds a dirt column down to solid ground
     * so water can't cascade down slopes.
     *
     * This runs after placeInWorld so it sees the actual placed water and
     * surrounding terrain state.
     */
    private void containWater(ServerLevel level, int x, int z, int footprintX, int footprintZ,
                               int placementY, int templateHeight) {
        // Collect all water positions in the footprint first
        List<BlockPos> waterPositions = new ArrayList<>();
        for (int bx = x; bx < x + footprintX; bx++) {
            for (int bz = z; bz < z + footprintZ; bz++) {
                for (int by = placementY; by < placementY + templateHeight; by++) {
                    if (isWaterAt(level, bx, by, bz)) {
                        waterPositions.add(new BlockPos(bx, by, bz));
                    }
                }
            }
        }

        // For each water block, check all 5 escape directions (4 cardinal + down)
        for (BlockPos waterPos : waterPositions) {
            for (int[] offset : WATER_ESCAPE_OFFSETS) {
                int nx = waterPos.getX() + offset[0];
                int ny = waterPos.getY() + offset[1];
                int nz = waterPos.getZ() + offset[2];

                BlockPos neighborPos = new BlockPos(nx, ny, nz);
                BlockState neighborState = level.getBlockState(neighborPos);

                boolean replaceable = isReplaceable(neighborState);

                // Only plug if the neighbor is non-solid (water could flow there)
                if (replaceable) {
                    // Build a dirt column downward to solid ground
                    for (int by = ny; by >= ny - 10; by--) {
                        BlockPos plugPos = new BlockPos(nx, by, nz);
                        BlockState plugState = level.getBlockState(plugPos);
                        if (!isReplaceable(plugState)) {
                            break;
                        }
                        level.setBlock(plugPos, Blocks.DIRT.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    private boolean isWaterAt(ServerLevel level, int x, int y, int z) {
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        return !state.getFluidState().isEmpty();
    }

    /** Returns true if water could flow into this block without destroying a crop. */
    private boolean isReplaceable(BlockState state) {
        if (state.isAir()) return true;
        // A crop is an occupied farm position, not an escape route. Treating it as
        // replaceable makes the water plugger turn valid crops into dirt.
        if (state.is(BlockTags.CROPS)) return false;
        if (state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)) return true; // plants do not contain water flow
        if (!state.getFluidState().isEmpty()) return false; // water/lava: already fluid, not an escape
        return state.canBeReplaced();
    }

    /**
     * Village-wide water containment pass that runs after ALL farms in a village are placed.
     * Scans both vanilla farms (inside the village bounding box) and outskirt farms for
     * water blocks adjacent to farmland that have air or replaceable escape routes.
     *
     * <p>Algorithm: find all farmland blocks, check their neighbors for water, then check
     * whether that water has air/replaceable blocks on any side or below: plug with dirt.</p>
     *
     * <p>This is naturally river-safe: rivers never border farmland blocks, so river
     * water is never touched.</p>
     *
     * @param level the server level
     * @param villageBB bounding box of the vanilla village (covers all jigsaw-placed farms)
     * @param placedFarms list of outskirt farms placed by the mod
     */
    public WaterContainmentTask createWaterContainmentTask(BoundingBox villageBB,
                                                            List<PlacedFarmInfo> placedFarms) {
        List<WaterScanRegion> regions = new ArrayList<>(placedFarms.size() + 1);
        regions.add(new WaterScanRegion(villageBB.minX(), villageBB.minZ(),
                villageBB.maxX() - villageBB.minX() + 1,
                villageBB.maxZ() - villageBB.minZ() + 1,
                villageBB.minY(), villageBB.maxY() - villageBB.minY() + 1));
        for (PlacedFarmInfo farm : placedFarms) {
            regions.add(new WaterScanRegion(farm.originX(), farm.originZ(), farm.footprintX(),
                    farm.footprintZ(), farm.placementY(), farm.templateHeight()));
        }

        long totalBlocks = 0;
        for (WaterScanRegion region : regions) {
            totalBlocks += region.volume();
        }
        long targetBudget = (totalBlocks + WATER_SCAN_TARGET_TICKS - 1) / WATER_SCAN_TARGET_TICKS;
        int blocksPerTick = (int) Math.min(Integer.MAX_VALUE,
                Math.max(MIN_WATER_SCAN_BLOCKS_PER_TICK, targetBudget));
        return new WaterContainmentTask(regions, placedFarms.size(), blocksPerTick);
    }

    private record WaterScanRegion(int x, int z, int sizeX, int sizeZ, int minY, int height) {
        private long volume() {
            return (long) sizeX * sizeZ * Math.max(0, height);
        }
    }

    public final class WaterContainmentTask {
        private final List<WaterScanRegion> regions;
        private final int farmCount;
        private final int blocksPerTick;
        private final LongSet checkedWater = new LongOpenHashSet();
        private final BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();
        private final BlockPos.MutableBlockPos waterPos = new BlockPos.MutableBlockPos();
        private int regionIndex;
        private int x;
        private int z;
        private int y;
        private int totalPlugged;
        private boolean started;
        private boolean completed;

        private WaterContainmentTask(List<WaterScanRegion> regions, int farmCount,
                                     int blocksPerTick) {
            this.regions = regions;
            this.farmCount = farmCount;
            this.blocksPerTick = blocksPerTick;
        }

        public boolean process(ServerLevel level) {
            if (completed) {
                return true;
            }
            if (!started) {
                started = true;
                moveToNextValidRegion();
            }

            int scanned = 0;
            while (regionIndex < regions.size() && scanned < blocksPerTick) {
                WaterScanRegion region = regions.get(regionIndex);
                scanPos.set(x, y, z);
                if (level.getBlockState(scanPos).is(Blocks.FARMLAND)) {
                    totalPlugged += processFarmlandWater(level, x, y, z, checkedWater, waterPos);
                }
                scanned++;
                advance(region);
            }

            if (regionIndex >= regions.size()) {
                completed = true;
                if (totalPlugged > 0) {
                    EmeraldCapitalism.LOGGER.info(
                            "[ECAP] Village-wide water containment: plugged {} total blocks (vanilla + {} outskirt farms)",
                            totalPlugged, farmCount);
                } else {
                    EmeraldCapitalism.LOGGER.info(
                            "[ECAP] Village-wide water containment: no escapes found (vanilla + {} outskirt farms)",
                            farmCount);
                }
            }
            return completed;
        }

        private void advance(WaterScanRegion region) {
            y++;
            if (y < region.minY() + region.height()) return;
            y = region.minY();
            z++;
            if (z < region.z() + region.sizeZ()) return;
            z = region.z();
            x++;
            if (x < region.x() + region.sizeX()) return;
            regionIndex++;
            moveToNextValidRegion();
        }

        private void moveToNextValidRegion() {
            while (regionIndex < regions.size()) {
                WaterScanRegion region = regions.get(regionIndex);
                if (region.height() > 0 && region.sizeX() > 0 && region.sizeZ() > 0) {
                    x = region.x();
                    z = region.z();
                    y = region.minY();
                    return;
                }
                regionIndex++;
            }
        }
    }

    private int processFarmlandWater(ServerLevel level, int x, int y, int z,
                                     LongSet checkedWater,
                                     BlockPos.MutableBlockPos waterPos) {
        int plugged = 0;
        for (int[] offset : HORIZONTAL_OFFSETS) {
            waterPos.set(x + offset[0], y, z + offset[2]);
            long key = waterPos.asLong();
            if (checkedWater.contains(key) || level.getBlockState(waterPos).getFluidState().isEmpty()) {
                continue;
            }
            checkedWater.add(key);
            plugged += plugWaterEscapes(level, waterPos);
        }

        waterPos.set(x, y - 1, z);
        long belowKey = waterPos.asLong();
        if (!checkedWater.contains(belowKey)
                && !level.getBlockState(waterPos).getFluidState().isEmpty()) {
            checkedWater.add(belowKey);
            plugged += plugWaterEscapes(level, waterPos);
        }
        return plugged;
    }

    public void containWaterVillageWide(ServerLevel level, BoundingBox villageBB,
                                         List<PlacedFarmInfo> placedFarms) {
        // Collect all regions to scan: village BB + each outskirt farm footprint.
        // Track visited positions to avoid duplicate processing in overlapping areas.
        Set<BlockPos> checkedWater = new HashSet<>();
        int totalPlugged = 0;

        // Scan vanilla village bounding box
        int vanillaPlugged = scanRegionForFarmlandWater(level, checkedWater,
                villageBB.minX(), villageBB.minZ(),
                villageBB.maxX() - villageBB.minX() + 1,
                villageBB.maxZ() - villageBB.minZ() + 1,
                villageBB.minY(), villageBB.maxY() - villageBB.minY() + 1);

        if (vanillaPlugged > 0) {
            EmeraldCapitalism.LOGGER.info(
                    "[ECAP] Village-wide water check: plugged {} blocks in vanilla village area [({},{},{}) to ({},{},{})]",
                    vanillaPlugged,
                    villageBB.minX(), villageBB.minY(), villageBB.minZ(),
                    villageBB.maxX(), villageBB.maxY(), villageBB.maxZ());
        }
        totalPlugged += vanillaPlugged;

        // Scan outskirt farm footprints
        for (PlacedFarmInfo farm : placedFarms) {
            int plugged = scanRegionForFarmlandWater(level, checkedWater,
                    farm.originX(), farm.originZ(),
                    farm.footprintX(), farm.footprintZ(),
                    farm.placementY(), farm.templateHeight());

            if (plugged > 0) {
                EmeraldCapitalism.LOGGER.info(
                        "[ECAP] Village-wide water check: plugged {} blocks at outskirt farm ({}, {}, {})",
                        plugged, farm.originX(), farm.placementY(), farm.originZ());
            }
            totalPlugged += plugged;
        }

        if (totalPlugged > 0) {
            EmeraldCapitalism.LOGGER.info(
                    "[ECAP] Village-wide water containment: plugged {} total blocks (vanilla + {} outskirt farms)",
                    totalPlugged, placedFarms.size());
        } else {
            EmeraldCapitalism.LOGGER.info(
                    "[ECAP] Village-wide water containment: no escapes found (vanilla + {} outskirt farms)",
                    placedFarms.size());
        }
    }

    /**
     * Scans a rectangular region for farmland blocks and checks adjacent water
     * for escape routes. Plugs any air/replaceable neighbors with dirt.
     *
     * @return the number of dirt blocks placed
     */
    private int scanRegionForFarmlandWater(ServerLevel level, Set<BlockPos> checkedWater,
                                            int x, int z, int sizeX, int sizeZ,
                                            int minY, int height) {
        int plugged = 0;

        for (int bx = x; bx < x + sizeX; bx++) {
            for (int bz = z; bz < z + sizeZ; bz++) {
                for (int by = minY; by < minY + height; by++) {
                    BlockPos pos = new BlockPos(bx, by, bz);
                    if (level.getBlockState(pos).getBlock() != Blocks.FARMLAND) {
                        continue;
                    }

                    // Check 4 cardinal neighbors of this farmland for water
                    for (int[] dir : HORIZONTAL_OFFSETS) {
                        BlockPos waterCandidate = new BlockPos(bx + dir[0], by, bz + dir[2]);
                        if (checkedWater.contains(waterCandidate)) {
                            continue; // already processed
                        }
                        BlockState waterState = level.getBlockState(waterCandidate);
                        if (waterState.getFluidState().isEmpty()) {
                            continue; // not water
                        }
                        checkedWater.add(waterCandidate);
                        plugged += plugWaterEscapes(level, waterCandidate);
                    }

                    // Also check the block below farmland: water channels can sit
                    // under the farmland level
                    BlockPos belowFarmland = new BlockPos(bx, by - 1, bz);
                    if (!checkedWater.contains(belowFarmland)) {
                        BlockState belowState = level.getBlockState(belowFarmland);
                        if (!belowState.getFluidState().isEmpty()) {
                            checkedWater.add(belowFarmland);
                            plugged += plugWaterEscapes(level, belowFarmland);
                        }
                    }
                }
            }
        }
        return plugged;
    }

    /**
     * For a given water block, check all 5 escape directions (4 cardinal + down).
     * If any neighbor is air or replaceable, place a dirt block and continue downward
     * to build a column to solid ground.
     *
     * @return the number of dirt blocks placed
     */
    private int plugWaterEscapes(ServerLevel level, BlockPos waterPos) {
        int plugged = 0;

        for (int[] offset : WATER_ESCAPE_OFFSETS) {
            int nx = waterPos.getX() + offset[0];
            int ny = waterPos.getY() + offset[1];
            int nz = waterPos.getZ() + offset[2];

            BlockPos neighborPos = new BlockPos(nx, ny, nz);
            BlockState neighborState = level.getBlockState(neighborPos);

            if (isReplaceable(neighborState)) {
                // Build a dirt column down to solid ground
                for (int by = ny; by >= ny - 10; by--) {
                    BlockPos plugPos = new BlockPos(nx, by, nz);
                    BlockState plugState = level.getBlockState(plugPos);
                    if (!isReplaceable(plugState)) {
                        break;
                    }
                    level.setBlock(plugPos, Blocks.DIRT.defaultBlockState(), 2);

                    // Blend with nearby village surface materials on the same Y level.
                    // If this plugged block borders a path/smooth sandstone directly or diagonally,
                    // replace the dirt with that neighboring material.
                    BlockState preferredSurface = findPreferredPlugSurface(level, plugPos);
                    if (preferredSurface != null) {
                        level.setBlock(plugPos, preferredSurface, 2);
                    }

                    plugged++;
                }
            }
        }
        return plugged;
    }

    /**
     * Looks around the plugged position on the same Y level (cardinal + diagonal)
     * and returns a preferred surface block state when found.
     */
    private BlockState findPreferredPlugSurface(ServerLevel level, BlockPos pluggedPos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                BlockPos around = pluggedPos.offset(dx, 0, dz);
                BlockState aroundState = level.getBlockState(around);

                if (aroundState.is(Blocks.DIRT_PATH) || aroundState.is(Blocks.SMOOTH_SANDSTONE)) {
                    return aroundState;
                }
            }
        }

        return null;
    }
}
