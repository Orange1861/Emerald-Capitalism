package com.orangevillager61.emeraldcapitalism.world.villagefarms;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.block.entity.VillageManagerBlockEntity;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import java.util.*;

/**
 * Selects valid farm sites from village path endpoints and empty gaps inside
 * the village bounding box.
 *
 * <p>Path endpoints are preferred, with bounded random candidates inside the
 * village bounds filling any remaining capacity.</p>
 */
public class VillageFarmSiteSelector {

    private static final Map<String, List<FarmTemplate>> BIOME_FARMS = new HashMap<>();
    private static final int[][] PATH_CLEAR_DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    static {
        BIOME_FARMS.put("PLAINS", List.of(
                new FarmTemplate("minecraft:village/plains/houses/plains_large_farm_1", "minecraft:farm_plains"),
                new FarmTemplate("minecraft:village/plains/houses/plains_small_farm_1", "minecraft:farm_plains")
        ));
        BIOME_FARMS.put("DESERT", List.of(
                new FarmTemplate("minecraft:village/desert/houses/desert_farm_1", "minecraft:farm_desert"),
                new FarmTemplate("minecraft:village/desert/houses/desert_farm_2", "minecraft:farm_desert")
        ));
        BIOME_FARMS.put("SAVANNA", List.of(
                new FarmTemplate("minecraft:village/savanna/houses/savanna_large_farm_1", "minecraft:farm_savanna"),
                new FarmTemplate("minecraft:village/savanna/houses/savanna_large_farm_2", "minecraft:farm_savanna"),
                new FarmTemplate("minecraft:village/savanna/houses/savanna_small_farm_1", "minecraft:farm_savanna")
        ));
        BIOME_FARMS.put("TAIGA", List.of(
                new FarmTemplate("minecraft:village/taiga/houses/taiga_large_farm_1", "minecraft:farm_taiga"),
                new FarmTemplate("minecraft:village/taiga/houses/taiga_large_farm_2", "minecraft:farm_taiga"),
                new FarmTemplate("minecraft:village/taiga/houses/taiga_small_farm_1", "minecraft:farm_taiga")
        ));
        BIOME_FARMS.put("SNOWY", List.of(
                new FarmTemplate("minecraft:village/snowy/houses/snowy_farm_1", "minecraft:farm_snowy"),
                new FarmTemplate("minecraft:village/snowy/houses/snowy_farm_2", "minecraft:farm_snowy")
        ));
    }

    private record FarmTemplate(String nbtPath, String processorPath) {
        ResourceLocation nbtLocation() {
            return ResourceLocation.parse(nbtPath);
        }
        ResourceLocation processorLocation() {
            return ResourceLocation.parse(processorPath);
        }
    }

    private record TemplateDetails(FarmTemplate template, int sizeX, int sizeZ) {}

    private record TerrainKey(int x, int z, int footprintX, int footprintZ) {}

    public static final class VillageSpatialCache {
        private final Set<Block> blacklist;
        private final List<BoundingBox> nonVillagePieceBoxes;
        private final List<BoundingBox> bankExclusionBoxes;
        private final List<BoundingBox> paddedVillageBuildingBoxes;
        private final List<StructurePiece> pathPieces;
        private final List<BoundingBox> pipelineBuildingReservations;
        private final Set<Long> pipelinePathReservations;
        private final Map<TerrainKey, Boolean> terrainSuitability = new HashMap<>();

        private VillageSpatialCache(Set<Block> blacklist,
                                    List<BoundingBox> nonVillagePieceBoxes,
                                     List<BoundingBox> bankExclusionBoxes,
                                     List<BoundingBox> paddedVillageBuildingBoxes,
                                     List<StructurePiece> pathPieces,
                                     List<BoundingBox> pipelineBuildingReservations,
                                     Set<Long> pipelinePathReservations) {
            this.blacklist = Set.copyOf(blacklist);
            this.nonVillagePieceBoxes = List.copyOf(nonVillagePieceBoxes);
            this.bankExclusionBoxes = List.copyOf(bankExclusionBoxes);
            this.paddedVillageBuildingBoxes = List.copyOf(paddedVillageBuildingBoxes);
            this.pathPieces = List.copyOf(pathPieces);
            this.pipelineBuildingReservations = List.copyOf(pipelineBuildingReservations);
            this.pipelinePathReservations = Set.copyOf(pipelinePathReservations);
        }

        Set<Block> blacklist() { return blacklist; }
        List<BoundingBox> nonVillagePieceBoxes() { return nonVillagePieceBoxes; }
        List<BoundingBox> bankExclusionBoxes() { return bankExclusionBoxes; }
        List<BoundingBox> paddedVillageBuildingBoxes() { return paddedVillageBuildingBoxes; }
        List<StructurePiece> pathPieces() { return pathPieces; }

        /** Adds pre-placement claims without repeating the expensive structure scan. */
        public VillageSpatialCache withPipelineReservations(List<BoundingBox> buildingReservations,
                                                            Set<Long> pathReservations) {
            return new VillageSpatialCache(blacklist, nonVillagePieceBoxes, bankExclusionBoxes,
                    paddedVillageBuildingBoxes, pathPieces, buildingReservations, pathReservations);
        }
    }

    private record StructureBoxSets(List<BoundingBox> villageBuildings,
                                    List<BoundingBox> nonVillagePieces) {}

    public VillageSpatialCache buildSpatialCache(ServerLevel level, BlockPos center,
                                                  BoundingBox villageBB,
                                                  List<StructurePiece> pieces) {
        int overlapPaddingChunks = 2;
        int windowMinCX = (villageBB.minX() >> 4) - overlapPaddingChunks;
        int windowMaxCX = (villageBB.maxX() >> 4) + overlapPaddingChunks;
        int windowMinCZ = (villageBB.minZ() >> 4) - overlapPaddingChunks;
        int windowMaxCZ = (villageBB.maxZ() >> 4) + overlapPaddingChunks;

        StructureBoxSets structureBoxes = collectStructurePieceBoxes(
                level, windowMinCX, windowMaxCX, windowMinCZ, windowMaxCZ);
        List<BoundingBox> villageBuildings = new ArrayList<>(structureBoxes.villageBuildings());
        if (villageBuildings.isEmpty()) {
            for (StructurePiece piece : pieces) {
                if (!isPathPiece(piece)) {
                    villageBuildings.add(piece.getBoundingBox());
                }
            }
        }

        List<BoundingBox> paddedBuildings = new ArrayList<>(villageBuildings.size());
        for (BoundingBox box : villageBuildings) {
            paddedBuildings.add(new BoundingBox(
                    box.minX() - 2, 0, box.minZ() - 2,
                    box.maxX() + 2, 255, box.maxZ() + 2));
        }

        List<StructurePiece> pathPieces = new ArrayList<>();
        for (StructurePiece piece : pieces) {
            if (isPathPiece(piece)) {
                pathPieces.add(piece);
            }
        }

        return new VillageSpatialCache(resolveBlockBlacklist(), structureBoxes.nonVillagePieces(),
                collectBankExclusionBoxes(level, center), paddedBuildings, pathPieces,
                List.of(), Set.of());
    }

    /**
     * Find suitable farm placement sites around a village.
     *
     * @param level      the server level
     * @param center     the village center position
     * @param biomeType  the biome type (PLAINS, DESERT, etc.)
     * @param villageBB  the village bounds used for farm count and in-bounds candidates
     * @param pieces     the village structure pieces
     * @param budget chunk loading budget for force-loading unloaded chunks
     * @return list of farm placements (may be empty if no suitable sites)
     */
    public List<FarmPlacement> findSites(ServerLevel level, BlockPos center, String biomeType,
                                          BoundingBox villageBB, List<StructurePiece> pieces,
                                          ChunkLoadBudget budget, VillageSpatialCache context) {
        List<FarmTemplate> farmTemplates = BIOME_FARMS.get(biomeType);
        if (farmTemplates == null || farmTemplates.isEmpty()) {
            EmeraldCapitalism.LOGGER.warn("[ECAP] No farm templates for biome: {}", biomeType);
            return Collections.emptyList();
        }

        StructureTemplateManager templateManager = level.getStructureManager();

        // Seed placement RNG from the world seed and village center for deterministic layouts.
        long villageSeed = level.getSeed()
                ^ ((long) center.getX() * 341873128712L)
                ^ ((long) center.getZ() * 132897987541L);
        RandomSource random = RandomSource.create(villageSeed);

        int bbChunksX = (villageBB.maxX() >> 4) - (villageBB.minX() >> 4) + 1;
        int bbChunksZ = (villageBB.maxZ() >> 4) - (villageBB.minZ() >> 4) + 1;
        int bbChunkCount = bbChunksX * bbChunksZ;

        int baseFarms = Config.outskirtFarmsBaseFarmCount.get();
        int perChunkBonus = Config.outskirtFarmsPerChunkBonus.get();
        int maxCount = Math.min(baseFarms + perChunkBonus * bbChunkCount, Config.outskirtFarmsMaxCount.get());

        Map<FarmTemplate, TemplateDetails> templateDetails = buildTemplateDetails(farmTemplates, templateManager);
        if (templateDetails.isEmpty()) {
            EmeraldCapitalism.LOGGER.warn("[ECAP] No loadable farm templates for biome: {}", biomeType);
            return Collections.emptyList();
        }

        EmeraldCapitalism.LOGGER.debug(
                "[ECAP] Village BB covers {}x{} = {} chunks, target farm count: {} (base {} + {} per chunk, max {})",
                bbChunksX, bbChunksZ, bbChunkCount, maxCount, baseFarms, perChunkBonus, Config.outskirtFarmsMaxCount.get());

        List<StructurePiece> pathPieces = context.pathPieces();

        EmeraldCapitalism.LOGGER.debug(
                "[ECAP] Village at ({}, {}, {}): {} path pieces, {} building pieces",
                center.getX(), center.getY(), center.getZ(), pathPieces.size(), pieces.size() - pathPieces.size());

        List<BoundingBox> villageBuildingPieceBoxes = context.paddedVillageBuildingBoxes();
        EmeraldCapitalism.LOGGER.debug(
                "[ECAP] Building overlap set for village at ({}, {}, {}): {} building boxes",
                center.getX(), center.getY(), center.getZ(), villageBuildingPieceBoxes.size());

        List<CandidateSite> candidates = new ArrayList<>();
        int bbCenterX = (villageBB.minX() + villageBB.maxX()) / 2;
        int bbCenterZ = (villageBB.minZ() + villageBB.maxZ()) / 2;

        // Prefer path endpoints, then fill remaining capacity inside the village bounds.
        List<PathEndpoint> endpoints = findPathEndpoints(pathPieces, center);
        for (PathEndpoint endpoint : endpoints) {
            CandidateSite site = createCandidateAtEndpoint(
                    endpoint, random, templateDetails,
                    level, villageBuildingPieceBoxes, pathPieces, budget, context);
            if (site != null) {
                candidates.add(site);
            }
        }

        EmeraldCapitalism.LOGGER.debug(
                "[ECAP] Path-endpoint candidates: {}/{} endpoints yielded valid sites",
                candidates.size(), endpoints.size());

        if (candidates.size() < maxCount) {
            int insideCount = Math.max(20, maxCount * 3);
            int bbWidth = villageBB.maxX() - villageBB.minX();
            int bbDepth = villageBB.maxZ() - villageBB.minZ();

            for (int i = 0; i < insideCount; i++) {
                int candidateX = villageBB.minX() + random.nextInt(Math.max(1, bbWidth));
                int candidateZ = villageBB.minZ() + random.nextInt(Math.max(1, bbDepth));
                double angle = Math.atan2(candidateZ - bbCenterZ, candidateX - bbCenterX);

                CandidateSite site = createCandidateAt(
                        candidateX, candidateZ, angle, random, templateDetails,
                        level, villageBuildingPieceBoxes, pathPieces, budget, context);
                if (site != null) {
                    candidates.add(site);
                }
            }
        }

        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // Select by angular spread, checking overlap before acceptance.
        candidates.sort(Comparator.comparingDouble(c -> c.angle));

        List<FarmPlacement> placements = new ArrayList<>();
        List<BoundingBox> placedFootprints = new ArrayList<>();
        Set<CandidateSite> used = new HashSet<>();

        while (placements.size() < maxCount && used.size() < candidates.size()) {
            // Prefer the unused candidate farthest from accepted angles, then the shortest path.
            double bestMinDist = -1;
            int bestPathDistance = Integer.MAX_VALUE;
            CandidateSite bestCandidate = null;

            // Prefer roadside plots whenever any remain. More distant candidates
            // are retained as a fallback when terrain prevents enough roadside farms.
            boolean hasRoadsideCandidate = candidates.stream()
                    .anyMatch(candidate -> !used.contains(candidate) && candidate.pathDistance <= 1);

            for (CandidateSite candidate : candidates) {
                if (used.contains(candidate)) continue;
                if (hasRoadsideCandidate && candidate.pathDistance > 1) continue;

                double minAngularDist = Double.MAX_VALUE;
                for (CandidateSite sel : used) {
                    double dist = Math.abs(candidate.angle - sel.angle);
                    dist = Math.min(dist, 2 * Math.PI - dist);
                    minAngularDist = Math.min(minAngularDist, dist);
                }

                if (minAngularDist > bestMinDist
                        || (Double.compare(minAngularDist, bestMinDist) == 0
                        && candidate.pathDistance < bestPathDistance)) {
                    bestMinDist = minAngularDist;
                    bestPathDistance = candidate.pathDistance;
                    bestCandidate = candidate;
                }
            }

            if (bestCandidate == null) break;

            used.add(bestCandidate);

            // Check overlap with already-placed farms
            int centerSampleX = bestCandidate.x + bestCandidate.footprintX / 2;
            int centerSampleZ = bestCandidate.z + bestCandidate.footprintZ / 2;
            int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, centerSampleX, centerSampleZ) - 1;

            BoundingBox farmBB = new BoundingBox(
                    bestCandidate.x, 0, bestCandidate.z,
                    bestCandidate.x + bestCandidate.footprintX - 1, 255,
                    bestCandidate.z + bestCandidate.footprintZ - 1
            );

            boolean overlaps = false;
            for (BoundingBox existing : placedFootprints) {
                if (farmBB.intersects(existing)) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) continue;

            placedFootprints.add(farmBB);
            placements.add(new FarmPlacement(
                    new BlockPos(bestCandidate.x, surfaceY, bestCandidate.z),
                    bestCandidate.template.nbtLocation(),
                    bestCandidate.template.processorLocation(),
                    bestCandidate.rotation,
                    bestCandidate.footprintX,
                    bestCandidate.footprintZ
            ));
        }

        return placements;
    }

    // Path piece detection

    /**
     * Checks if a structure piece is a village path/street piece by inspecting
     * the pool element's template name.
     */
    private static boolean isPathPiece(StructurePiece piece) {
        if (piece instanceof PoolElementStructurePiece poolPiece) {
            String elementStr = poolPiece.getElement().toString();
            return elementStr.contains("/streets/") || elementStr.contains("/street/");
        }
        return false;
    }

    private record PathEndpoint(int x, int z, double dirX, double dirZ) {
        double angle() {
            return Math.atan2(dirZ, dirX);
        }
    }

    /**
     * For each path piece, find its outward endpoint: the end of the path BB
     * that faces away from the village center.
     */
    private List<PathEndpoint> findPathEndpoints(List<StructurePiece> pathPieces, BlockPos villageCenter) {
        List<PathEndpoint> endpoints = new ArrayList<>();
        int vcx = villageCenter.getX();
        int vcz = villageCenter.getZ();

        for (StructurePiece piece : pathPieces) {
            BoundingBox bb = piece.getBoundingBox();
            int midX = (bb.minX() + bb.maxX()) / 2;
            int midZ = (bb.minZ() + bb.maxZ()) / 2;

            // Direction from village center to this piece's center
            double dx = midX - vcx;
            double dz = midZ - vcz;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1) continue; // Too close to center, skip

            double normDx = dx / len;
            double normDz = dz / len;

            // The outward endpoint is the edge of the BB in the outward direction.
            // For a path elongated in X: outward end is minX or maxX.
            // For a path elongated in Z: outward end is minZ or maxZ.
            int extentX = bb.maxX() - bb.minX();
            int extentZ = bb.maxZ() - bb.minZ();

            int endpointX, endpointZ;
            if (extentX >= extentZ) {
                // Elongated in X: pick the X edge facing outward
                endpointX = (normDx >= 0) ? bb.maxX() : bb.minX();
                endpointZ = midZ;
            } else {
                // Elongated in Z: pick the Z edge facing outward
                endpointX = midX;
                endpointZ = (normDz >= 0) ? bb.maxZ() : bb.minZ();
            }

            endpoints.add(new PathEndpoint(endpointX, endpointZ, normDx, normDz));
        }

        // Deduplicate endpoints that are very close to each other (within 6 blocks)
        List<PathEndpoint> deduplicated = new ArrayList<>();
        for (PathEndpoint ep : endpoints) {
            boolean tooClose = false;
            for (PathEndpoint existing : deduplicated) {
                int ddx = ep.x - existing.x;
                int ddz = ep.z - existing.z;
                if (ddx * ddx + ddz * ddz < 36) { // 6 blocks
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) {
                deduplicated.add(ep);
            }
        }

        return deduplicated;
    }

    // Candidate creation

    /**
     * Create a candidate farm site at a path endpoint, trying multiple offsets.
     * Tries: right at the endpoint, then small outward offsets, then sideways nudges.
     */
    private CandidateSite createCandidateAtEndpoint(PathEndpoint endpoint, RandomSource random,
                                                     Map<FarmTemplate, TemplateDetails> templateDetails,
                                                     ServerLevel level, List<BoundingBox> buildingPieceBoxes,
                                                     List<StructurePiece> pathPieces,
                                                     ChunkLoadBudget budget,
                                                     VillageSpatialCache context) {
        // Perpendicular direction for sideways nudges
        double perpX = -endpoint.dirZ;
        double perpZ = endpoint.dirX;

        // Try multiple placements: outward offsets 0,3,6 blocks, then sideways nudges +/-8
        int[][] offsets = {
                {0, 0}, {3, 0}, {6, 0},
                {2, 8}, {2, -8},
        };
        for (int[] off : offsets) {
            int candidateX = endpoint.x + (int) (endpoint.dirX * off[0] + perpX * off[1]);
            int candidateZ = endpoint.z + (int) (endpoint.dirZ * off[0] + perpZ * off[1]);

            CandidateSite site = createCandidateAt(candidateX, candidateZ, endpoint.angle(), random,
                    templateDetails, level, buildingPieceBoxes, pathPieces, budget, context);
            if (site != null) {
                return site;
            }
        }
        return null;
    }

    /**
     * Create a candidate farm site at an arbitrary position, checking terrain and building overlap.
     * Tries all templates in shuffled order, falling back to smaller ones if the first pick doesn't fit.
     * Road overlap is never accepted: an overlapping anchor is moved far enough to put the complete
     * farm footprint beside the road, with direct roadside adjacency preferred.
     */
    private CandidateSite createCandidateAt(int candidateX, int candidateZ, double angle, RandomSource random,
                                             Map<FarmTemplate, TemplateDetails> templateDetails,
                                             ServerLevel level, List<BoundingBox> buildingPieceBoxes,
                                             List<StructurePiece> pathPieces,
                                             ChunkLoadBudget budget,
                                             VillageSpatialCache context) {
        // Shuffle templates so we try them in random order, falling back to others if one doesn't fit
        List<TemplateDetails> shuffled = new ArrayList<>(templateDetails.values());
        Collections.shuffle(shuffled, new java.util.Random(random.nextLong()));
        Rotation rotation = Rotation.getRandom(random);

        for (TemplateDetails details : shuffled) {
            FarmTemplate template = details.template();
            int footprintX, footprintZ;
            if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) {
                footprintX = details.sizeZ();
                footprintZ = details.sizeX();
            } else {
                footprintX = details.sizeX();
                footprintZ = details.sizeZ();
            }

            CandidatePosition position = findPathClearPosition(
                    level, candidateX, candidateZ, footprintX, footprintZ,
                    buildingPieceBoxes, pathPieces, budget, context);
            if (position != null) {
                return new CandidateSite(position.x, position.z, angle, template, rotation,
                        footprintX, footprintZ, position.pathDistance);
            }
        }

        return null;
    }

    /**
     * Count how many blocks of a farm footprint overlap with path piece bounding boxes (horizontal only).
     */
    static int countPathOverlap(int x, int z, int footprintX, int footprintZ,
                                List<StructurePiece> pathPieces) {
        BoundingBox farmBB = new BoundingBox(x, 0, z, x + footprintX - 1, 255, z + footprintZ - 1);
        int overlap = 0;
        for (StructurePiece path : pathPieces) {
            BoundingBox pathBB = path.getBoundingBox();
            BoundingBox pathHoriz = new BoundingBox(pathBB.minX(), 0, pathBB.minZ(),
                    pathBB.maxX(), 255, pathBB.maxZ());
            if (farmBB.intersects(pathHoriz)) {
                // Approximate overlap area as intersection of the two horizontal rectangles
                int overlapMinX = Math.max(x, pathBB.minX());
                int overlapMaxX = Math.min(x + footprintX - 1, pathBB.maxX());
                int overlapMinZ = Math.max(z, pathBB.minZ());
                int overlapMaxZ = Math.min(z + footprintZ - 1, pathBB.maxZ());
                overlap += (overlapMaxX - overlapMinX + 1) * (overlapMaxZ - overlapMinZ + 1);
            }
        }
        return overlap;
    }

    /**
     * Finds the nearest valid footprint that clears both structure road boxes and
     * already-generated road surface blocks. The widening search is intentionally
     * bounded by the farm size so site selection stays deterministic and cheap.
     */
    @javax.annotation.Nullable
    private CandidatePosition findPathClearPosition(ServerLevel level, int candidateX, int candidateZ,
                                                    int footprintX, int footprintZ,
                                                    List<BoundingBox> buildingPieceBoxes,
                                                    List<StructurePiece> pathPieces,
                                                    ChunkLoadBudget budget,
                                                    VillageSpatialCache context) {
        CandidatePosition direct = validatePathClearPosition(
                level, candidateX, candidateZ, footprintX, footprintZ,
                buildingPieceBoxes, pathPieces, budget, context);
        if (direct != null) {
            return direct;
        }

        int maxNudge = Math.max(footprintX, footprintZ) + 6;
        CandidatePosition best = null;
        int bestMovement = Integer.MAX_VALUE;
        for (int distance = 1; distance <= maxNudge; distance++) {
            for (int[] direction : PATH_CLEAR_DIRECTIONS) {
                int offsetX = direction[0] * distance;
                int offsetZ = direction[1] * distance;
                int x = candidateX + offsetX;
                int z = candidateZ + offsetZ;
                CandidatePosition position = validatePathClearPosition(
                        level, x, z, footprintX, footprintZ,
                        buildingPieceBoxes, pathPieces, budget, context);
                if (position == null) {
                    continue;
                }
                int movement = Math.abs(offsetX) + Math.abs(offsetZ);
                if (best == null
                        || position.pathDistance < best.pathDistance
                        || (position.pathDistance == best.pathDistance && movement < bestMovement)) {
                    best = position;
                    bestMovement = movement;
                }
            }
            if (best != null && best.pathDistance == 0) {
                return best;
            }
        }
        return best;
    }

    @javax.annotation.Nullable
    private CandidatePosition validatePathClearPosition(ServerLevel level, int x, int z,
                                                        int footprintX, int footprintZ,
                                                        List<BoundingBox> buildingPieceBoxes,
                                                        List<StructurePiece> pathPieces,
                                                        ChunkLoadBudget budget,
                                                        VillageSpatialCache context) {
        if (countPathOverlap(x, z, footprintX, footprintZ, pathPieces) > 0) {
            return null;
        }
        if (!isTerrainSuitable(level, x, z, footprintX, footprintZ,
                buildingPieceBoxes, budget, context)) {
            return null;
        }
        // Terrain validation above ensures every footprint chunk is available
        // before this exact surface scan reads the existing road blocks.
        if (overlapsExistingPathSurface(level, x, z, footprintX, footprintZ)) {
            return null;
        }
        return new CandidatePosition(x, z,
                distanceToNearestPath(x, z, footprintX, footprintZ, pathPieces));
    }

    /** Returns true when an existing village-road surface would be replaced by the farm. */
    static boolean overlapsExistingPathSurface(ServerLevel level, int x, int z,
                                               int footprintX, int footprintZ) {
        for (int sx = x; sx < x + footprintX; sx++) {
            for (int sz = z; sz < z + footprintZ; sz++) {
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, sx, sz) - 1;
                BlockState state = level.getBlockState(new BlockPos(sx, surfaceY, sz));
                if (state.is(net.minecraft.world.level.block.Blocks.DIRT_PATH)
                        || state.is(net.minecraft.world.level.block.Blocks.SMOOTH_SANDSTONE)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Horizontal block gap between a farm footprint and the nearest road piece. */
    private int distanceToNearestPath(int x, int z, int footprintX, int footprintZ,
                                      List<StructurePiece> pathPieces) {
        if (pathPieces.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int maxX = x + footprintX - 1;
        int maxZ = z + footprintZ - 1;
        int nearest = Integer.MAX_VALUE;
        for (StructurePiece path : pathPieces) {
            BoundingBox box = path.getBoundingBox();
            int gapX = Math.max(0, Math.max(box.minX() - maxX - 1, x - box.maxX() - 1));
            int gapZ = Math.max(0, Math.max(box.minZ() - maxZ - 1, z - box.maxZ() - 1));
            nearest = Math.min(nearest, Math.max(gapX, gapZ));
        }
        return nearest;
    }

    // Terrain validation

    /** Returns whether the exact block below a farm candidate has a sturdy upward face. */
    public static boolean isFarmSurfaceSupported(BlockGetter level, BlockPos supportPos) {
        BlockState supportState = level.getBlockState(supportPos);
        return supportState.isFaceSturdy(level, supportPos, Direction.UP);
    }

    /** Validates terrain and structure constraints for a candidate footprint. */
    private boolean isTerrainSuitable(ServerLevel level, int x, int z, int footprintX, int footprintZ,
                                       List<BoundingBox> buildingPieceBoxes, ChunkLoadBudget budget,
                                       VillageSpatialCache context) {
        TerrainKey key = new TerrainKey(x, z, footprintX, footprintZ);
        Boolean cached = context.terrainSuitability.get(key);
        if (cached != null) {
            return cached;
        }
        boolean suitable = evaluateTerrainSuitability(
                level, x, z, footprintX, footprintZ, buildingPieceBoxes, budget, context);
        context.terrainSuitability.put(key, suitable);
        return suitable;
    }

    private boolean evaluateTerrainSuitability(ServerLevel level, int x, int z,
                                                int footprintX, int footprintZ,
                                                List<BoundingBox> buildingPieceBoxes,
                                                ChunkLoadBudget budget,
                                                VillageSpatialCache context) {
        if (overlapsPipelineReservation(x, z, footprintX, footprintZ, context)) {
            return false;
        }

        int minChunkX = x >> 4;
        int maxChunkX = (x + footprintX - 1) >> 4;
        int minChunkZ = z >> 4;
        int maxChunkZ = (z + footprintZ - 1) >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!budget.ensureLoaded(level, cx, cz)) {
                    return false;
                }
            }
        }

        int sampleStep = 3;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int totalSamples = 0;
        int badSamples = 0;
        List<Integer> heights = new ArrayList<>();

        for (int sx = x; sx < x + footprintX; sx += sampleStep) {
            for (int sz = z; sz < z + footprintZ; sz += sampleStep) {
                totalSamples++;
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, sx, sz) - 1;
                minY = Math.min(minY, surfaceY);
                maxY = Math.max(maxY, surfaceY);
                heights.add(surfaceY);

                BlockPos surfacePos = new BlockPos(sx, surfaceY, sz);
                BlockState surfaceState = level.getBlockState(surfacePos);

                if (!context.blacklist().isEmpty()) {
                    for (int dy = 0; dy >= -3; dy--) {
                        if (context.blacklist().contains(
                                level.getBlockState(surfacePos.offset(0, dy, 0)).getBlock())) {
                            return false;
                        }
                    }
                }

                if (!surfaceState.getFluidState().isEmpty() ||
                        surfaceState.getBlock() == net.minecraft.world.level.block.Blocks.ICE ||
                        surfaceState.getBlock() == net.minecraft.world.level.block.Blocks.PACKED_ICE ||
                        surfaceState.getBlock() == net.minecraft.world.level.block.Blocks.BLUE_ICE) {
                    badSamples++;
                    continue;
                }

                if (!isFarmSurfaceSupported(level, surfacePos)) {
                    badSamples++;
                }
            }
        }

        if (totalSamples > 0 && badSamples * 5 > totalSamples * 2) {
            return false;
        }

        if (maxY - minY > 10) {
            return false;
        }

        int medianY = minY;
        if (!heights.isEmpty()) {
            Collections.sort(heights);
            medianY = heights.get(heights.size() / 2);
        }

        // Reject sites with steep embankments around the footprint, which tend to
        // cause farms to look partially buried into nearby hills after leveling.
        int ringSamples = 0;
        int highWallSamples = 0;
        int ringPad = 2;
        for (int sx = x - ringPad; sx <= x + footprintX - 1 + ringPad; sx += sampleStep) {
            for (int sz = z - ringPad; sz <= z + footprintZ - 1 + ringPad; sz += sampleStep) {
                boolean insideFootprint = sx >= x && sx < x + footprintX && sz >= z && sz < z + footprintZ;
                if (insideFootprint) {
                    continue;
                }
                ringSamples++;
                int ringY = level.getHeight(Heightmap.Types.WORLD_SURFACE, sx, sz) - 1;
                if (ringY - medianY >= 4) {
                    highWallSamples++;
                }
            }
        }
        if (ringSamples > 0 && highWallSamples * 5 > ringSamples * 2) {
            return false;
        }

        // Check overlap with individual building pieces only (NOT the overall village BB).
        // Path pieces are excluded: farms are allowed to be adjacent to/near paths.
        BoundingBox farmBB = new BoundingBox(x, 0, z, x + footprintX - 1, 255, z + footprintZ - 1);
        for (BoundingBox pieceBB : buildingPieceBoxes) {
            if (farmBB.intersects(pieceBB)) {
                return false;
            }
        }

        // The bank is placed before farms. Keep both its footprint and the entrance
        // approach clear so an outskirt farm cannot obstruct the door or overlap it.
        if (overlapsBankExclusion(x, z, footprintX, footprintZ, context)) {
            return false;
        }

        // Check overlap with non-village generated structures
        if (overlapsNonVillageStructure(x, z, footprintX, footprintZ, context)) {
            return false;
        }

        return true;
    }

    // Structure overlap checks

    private boolean overlapsNonVillageStructure(int x, int z, int footprintX, int footprintZ,
                                                VillageSpatialCache context) {
        BoundingBox farmBB = new BoundingBox(x, Integer.MIN_VALUE / 2, z,
                x + footprintX - 1, Integer.MAX_VALUE / 2, z + footprintZ - 1);

        for (BoundingBox pieceBB : context.nonVillagePieceBoxes()) {
            if (farmBB.intersects(pieceBB)) {
                return true;
            }
        }
        return false;
    }

    private boolean overlapsBankExclusion(int x, int z, int footprintX, int footprintZ,
                                          VillageSpatialCache context) {
        BoundingBox farmBB = new BoundingBox(x, Integer.MIN_VALUE / 2, z,
                x + footprintX - 1, Integer.MAX_VALUE / 2, z + footprintZ - 1);
        for (BoundingBox bankBox : context.bankExclusionBoxes()) {
            if (farmBB.intersects(bankBox)) {
                return true;
            }
        }
        return false;
    }

    private boolean overlapsPipelineReservation(int x, int z, int footprintX, int footprintZ,
                                                VillageSpatialCache context) {
        BoundingBox farmBB = new BoundingBox(x, Integer.MIN_VALUE / 2, z,
                x + footprintX - 1, Integer.MAX_VALUE / 2, z + footprintZ - 1);
        for (BoundingBox reserved : context.pipelineBuildingReservations) {
            if (farmBB.intersects(reserved)) {
                return true;
            }
        }
        for (int sx = x; sx < x + footprintX; sx++) {
            for (int sz = z; sz < z + footprintZ; sz++) {
                long key = ((long) sx << 32) ^ (sz & 0xFFFFFFFFL);
                if (context.pipelinePathReservations.contains(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the bank building plus an eight-block no-farm apron on every side.
     * The template is unrotated today, but protecting the full perimeter avoids
     * coupling farm safety to a specific door orientation.
     */
    private List<BoundingBox> collectBankExclusionBoxes(ServerLevel level, BlockPos villageCenter) {
        VillageRecord village = VillageRegistryData.get(level).getVillageFor(villageCenter);
        if (village == null) {
            return List.of();
        }
        BlockPos managerPos = VillageRegistryData.get(level).getVMPos(village.getVillageId());
        if (managerPos == null
                || !(level.getBlockEntity(managerPos) instanceof VillageManagerBlockEntity manager)
                || manager.getBankPos() == null) {
            return List.of();
        }

        BlockPos bankPos = manager.getBankPos();
        // Bank building: X [-7, +6], Z [-5, +8] from the bank block. Reserve an
        // eight-block apron around all four sides, including the doorway approach.
        return List.of(new BoundingBox(
                bankPos.getX() - 15, Integer.MIN_VALUE / 2, bankPos.getZ() - 13,
                bankPos.getX() + 14, Integer.MAX_VALUE / 2, bankPos.getZ() + 16
        ));
    }

    private List<BoundingBox> collectVillageBuildingPieceBoxes(ServerLevel level,
                                                         int minCX, int maxCX,
                                                         int minCZ, int maxCZ,
                                                         List<StructurePiece> fallbackPieces) {
        Registry<Structure> structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        List<BoundingBox> boxes = new ArrayList<>();
        Set<Long> visitedStarts = new HashSet<>();

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                ChunkAccess chunk = level.getChunk(cx, cz);

                for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
                    if (!entry.getValue().isValid()) continue;
                    ResourceLocation id = structureRegistry.getKey(entry.getKey());
                    if (id == null || !isVillageStructure(id)) continue;

                    for (StructurePiece piece : entry.getValue().getPieces()) {
                        if (!isPathPiece(piece)) {
                            boxes.add(piece.getBoundingBox());
                        }
                    }
                }

                for (Map.Entry<Structure, LongSet> entry : chunk.getAllReferences().entrySet()) {
                    ResourceLocation id = structureRegistry.getKey(entry.getKey());
                    if (id == null || !isVillageStructure(id)) continue;

                    for (long startChunkLong : entry.getValue()) {
                        if (!visitedStarts.add(startChunkLong)) {
                            continue;
                        }

                        int startCX = ChunkPos.getX(startChunkLong);
                        int startCZ = ChunkPos.getZ(startChunkLong);
                        if (!level.hasChunk(startCX, startCZ)) {
                            continue;
                        }

                        ChunkAccess startChunk = level.getChunk(startCX, startCZ);
                        StructureStart start = startChunk.getStartForStructure(entry.getKey());
                        if (start == null || !start.isValid()) continue;

                        for (StructurePiece piece : start.getPieces()) {
                            if (!isPathPiece(piece)) {
                                boxes.add(piece.getBoundingBox());
                            }
                        }
                    }
                }
            }
        }

        if (boxes.isEmpty()) {
            for (StructurePiece piece : fallbackPieces) {
                if (!isPathPiece(piece)) {
                    boxes.add(piece.getBoundingBox());
                }
            }
        }

        return boxes;
    }

    private Map<FarmTemplate, TemplateDetails> buildTemplateDetails(List<FarmTemplate> farmTemplates,
                                                                     StructureTemplateManager templateManager) {
        Map<FarmTemplate, TemplateDetails> details = new HashMap<>();
        for (FarmTemplate template : farmTemplates) {
            Optional<StructureTemplate> structureTemplate = templateManager.get(template.nbtLocation());
            if (structureTemplate.isEmpty()) {
                continue;
            }
            net.minecraft.core.Vec3i rawSize = structureTemplate.get().getSize();
            details.put(template, new TemplateDetails(template, rawSize.getX(), rawSize.getZ()));
        }
        return details;
    }

    private StructureBoxSets collectStructurePieceBoxes(ServerLevel level,
                                                         int minCX, int maxCX,
                                                         int minCZ, int maxCZ) {
        Registry<Structure> structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        List<BoundingBox> villageBuildings = new ArrayList<>();
        List<BoundingBox> nonVillagePieces = new ArrayList<>();
        Set<StructureStart> visitedStarts = Collections.newSetFromMap(new IdentityHashMap<>());

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                ChunkAccess chunk = level.getChunk(cx, cz);

                for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
                    collectStructureStart(entry.getKey(), entry.getValue(), structureRegistry,
                            visitedStarts, villageBuildings, nonVillagePieces);
                }

                for (Map.Entry<Structure, LongSet> entry : chunk.getAllReferences().entrySet()) {
                    for (long startChunkLong : entry.getValue()) {
                        int startCX = ChunkPos.getX(startChunkLong);
                        int startCZ = ChunkPos.getZ(startChunkLong);
                        if (!level.hasChunk(startCX, startCZ)) {
                            continue;
                        }
                        StructureStart start = level.getChunk(startCX, startCZ)
                                .getStartForStructure(entry.getKey());
                        collectStructureStart(entry.getKey(), start, structureRegistry,
                                visitedStarts, villageBuildings, nonVillagePieces);
                    }
                }
            }
        }
        return new StructureBoxSets(villageBuildings, nonVillagePieces);
    }

    private void collectStructureStart(Structure structure, StructureStart start,
                                       Registry<Structure> structureRegistry,
                                       Set<StructureStart> visitedStarts,
                                       List<BoundingBox> villageBuildings,
                                       List<BoundingBox> nonVillagePieces) {
        if (start == null || !start.isValid() || !visitedStarts.add(start)) {
            return;
        }
        ResourceLocation id = structureRegistry.getKey(structure);
        if (id != null && isVillageStructure(id)) {
            for (StructurePiece piece : start.getPieces()) {
                if (!isPathPiece(piece)) {
                    villageBuildings.add(piece.getBoundingBox());
                }
            }
            return;
        }
        for (StructurePiece piece : start.getPieces()) {
            nonVillagePieces.add(piece.getBoundingBox());
        }
    }

    private List<BoundingBox> collectNonVillagePieceBoxes(ServerLevel level,
                                                          int minCX, int maxCX,
                                                          int minCZ, int maxCZ) {
        Registry<Structure> structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        List<BoundingBox> boxes = new ArrayList<>();
        Set<Long> visitedStarts = new HashSet<>();

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                ChunkAccess chunk = level.getChunk(cx, cz);

                for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
                    if (!entry.getValue().isValid()) continue;
                    ResourceLocation id = structureRegistry.getKey(entry.getKey());
                    if (id != null && isVillageStructure(id)) continue;

                    for (StructurePiece piece : entry.getValue().getPieces()) {
                        boxes.add(piece.getBoundingBox());
                    }
                }

                for (Map.Entry<Structure, LongSet> entry : chunk.getAllReferences().entrySet()) {
                    ResourceLocation id = structureRegistry.getKey(entry.getKey());
                    if (id != null && isVillageStructure(id)) continue;

                    for (long startChunkLong : entry.getValue()) {
                        if (!visitedStarts.add(startChunkLong)) {
                            continue;
                        }

                        int startCX = ChunkPos.getX(startChunkLong);
                        int startCZ = ChunkPos.getZ(startChunkLong);
                        if (!level.hasChunk(startCX, startCZ)) {
                            continue;
                        }

                        ChunkAccess startChunk = level.getChunk(startCX, startCZ);
                        StructureStart start = startChunk.getStartForStructure(entry.getKey());
                        if (start == null || !start.isValid()) continue;

                        for (StructurePiece piece : start.getPieces()) {
                            boxes.add(piece.getBoundingBox());
                        }
                    }
                }
            }
        }

        return boxes;
    }

    private static boolean isVillageStructure(ResourceLocation id) {
        return id.getNamespace().equals("minecraft") && id.getPath().startsWith("village_");
    }

    private Set<Block> resolveBlockBlacklist() {
        Set<Block> blocks = new HashSet<>();
        for (String blockId : Config.outskirtFarmsBlockBlacklist.get()) {
            ResourceLocation rl = ResourceLocation.tryParse(blockId);
            if (rl != null) {
                BuiltInRegistries.BLOCK.getOptional(rl).ifPresent(blocks::add);
            }
        }
        return blocks;
    }

    // Utilities

    private record CandidatePosition(int x, int z, int pathDistance) {
    }

    private record CandidateSite(int x, int z, double angle,
                                 FarmTemplate template, Rotation rotation,
                                 int footprintX, int footprintZ,
                                 int pathDistance) {
    }
}
