package com.orangevillager61.emeraldcapitalism.world.village;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.block.EmeraldOreProcessorBlock;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldChestBlockEntity;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import com.orangevillager61.emeraldcapitalism.entity.ai.VaultGolemGoals;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.registry.ECAPEntityTypes;
import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.ChunkLoadBudget;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookDefinition;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookRarity;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookRegistry;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookStackFactory;
import com.orangevillager61.emeraldcapitalism.worldgen.BankVaultRuinsProcessor;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.util.SpawnReasonCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Places the generated bank as a single, two-template village feature.
 *
 * <p>The templates intentionally have no jigsaw connectors: the vault needs a fixed
 * underground relationship to the top.  Both pieces are therefore placed together,
 * only after their chunks and templates have been validated, so a village can never
 * receive only one half of the bank.</p>
 */
public final class VillageBankStructurePlacer {

    private static final ResourceLocation BANK_TOP = ModIds.id("bank_top");
    private static final ResourceLocation BANK_VAULT = ModIds.id("bank_vault");
    private static final ResourceLocation BANK_VAULT_RUINS = ModIds.id("bank_vault_ruins");

    // bank_top.nbt is a 15x14x15 template. Keep the footprint in sync with the
    // authored asset because site selection, terrain grading, and validation all
    // depend on these dimensions.
    private static final int TOP_SIZE = 15;
    private static final int TOP_HEIGHT = 14;
    private static final int VAULT_SIZE_X = 9;
    private static final int VAULT_HEIGHT = 9;
    private static final int VAULT_SIZE_Z = 10;
    private static final int VAULT_VILLAGER_COUNT = 3;
    private static final int INITIAL_CHARCOAL_COUNT = 64;
    private static final int INITIAL_PUMPKIN_COUNT = 3;
    private static final int INITIAL_LOG_COUNT = 48;
    private static final float ABANDONED_VAULT_BANK_RULE_BOOK_CHANCE = 0.33F;
    private static final int TERRAIN_BLEND_RADIUS = 4;
    private static final int ABANDONED_VAULT_TOP_DEPTH = 8;

    /** The bank block's known position within {@code bank_top.nbt}. */
    private static final BlockPos BANK_BLOCK_OFFSET = new BlockPos(7, 1, 5);
    /** The two tall emerald doors share this local center line at the bank front. */
    private static final BlockPos BANK_DOOR_CENTER = new BlockPos(0, 0, 5);
    /** First exterior path block immediately outside the two tall emerald doors. */
    private static final BlockPos BANK_DOOR_PATH_START = new BlockPos(-1, 0, 5);
    /** The emerald processor's known position within {@code bank_top.nbt}. */
    private static final BlockPos EMERALD_ORE_PROCESSOR_OFFSET = new BlockPos(8, 1, 7);
    /** The emerald processor's authored FACING value in {@code bank_top.nbt}. */
    private static final Direction AUTHORED_EMERALD_ORE_PROCESSOR_FACING = Direction.NORTH;
    /**
     * The vault's roof is directly beneath the top's floor.  The X component is the
     * requested five blocks in the positive-X direction from the top template origin.
     */
    private static final BlockPos VAULT_OFFSET = new BlockPos(5, -9, 0);

    // The wider ring ensures a bank can be placed even in dense or hilly villages.
    // Village managers are linked directly after placement, so the outer sites do not
    // depend on their short nearby-bank discovery radius.
    private static final int[] DISTANCES_FROM_BELL = {18, 26, 34, 42, 50};
    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final Rotation[] BANK_ROTATIONS = {
            Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90
    };
    private static final VillageRoadPathGenerator ROAD_PATH_GENERATOR = new VillageRoadPathGenerator();

    /**
     * Places both bank templates near a village bell and returns the generated bank's
     * position.  A null result means the templates were unavailable or all placement
     * attempts failed; callers should leave the village otherwise intact.
     */
    @Nullable
    public BlockPos place(ServerLevel level, BlockPos bellPos, List<StructurePiece> villagePieces,
                          @Nullable BlockPos managerPos) {
        PlannedBank plan = plan(level, bellPos, villagePieces, managerPos, new ChunkLoadBudget());
        if (plan == null) {
            return null;
        }
        PlacedBank placed = placePlanned(level, plan);
        if (placed == null) {
            return null;
        }
        finishPlannedPlacement(level, placed);
        VillageRoadPathGenerator.PreparedVillageRoads preparedRoads = ROAD_PATH_GENERATOR
                .prepare(level, villagePieces)
                .withAdditionalBuildings(List.of(plan.placementBox()));
        ROAD_PATH_GENERATOR.generate(level, plan.pathStart(), plan.pathTarget(), villagePieces,
                plan.biomeType(), bankEntranceDirection(plan.rotation()), preparedRoads);
        return placed.bankPos();
    }

    /**
     * Chooses and validates a bank site without changing blocks. The pipeline reserves
     * this result and its connector before any lower-importance building is planned.
     */
    @Nullable
    public PlannedBank plan(ServerLevel level, BlockPos bellPos, List<StructurePiece> villagePieces,
                            @Nullable BlockPos managerPos, ChunkLoadBudget loadBudget) {
        return plan(level, bellPos, villagePieces, managerPos, loadBudget, ignored -> false);
    }

    @Nullable
    public PlannedBank plan(ServerLevel level, BlockPos bellPos, List<StructurePiece> villagePieces,
                            @Nullable BlockPos managerPos, ChunkLoadBudget loadBudget,
                            Predicate<BoundingBox> reservedCollision) {
        StructureTemplateManager templateManager = level.getStructureManager();
        Optional<StructureTemplate> topTemplate = templateManager.get(BANK_TOP);
        Optional<StructureTemplate> vaultTemplate = templateManager.get(BANK_VAULT);
        if (topTemplate.isEmpty() || vaultTemplate.isEmpty()) {
            EmeraldCapitalism.LOGGER.error(
                    "[ECAP] Cannot generate village bank near {}: missing top={} vault={}",
                    bellPos, topTemplate.isPresent(), vaultTemplate.isPresent());
            return null;
        }

        if (!hasExpectedSize(topTemplate.get(), TOP_SIZE, TOP_HEIGHT, TOP_SIZE)
                || !hasExpectedSize(vaultTemplate.get(), VAULT_SIZE_X, VAULT_HEIGHT, VAULT_SIZE_Z)) {
            EmeraldCapitalism.LOGGER.error(
                    "[ECAP] Cannot generate village bank near {}: bank template dimensions changed",
                    bellPos);
            return null;
        }

        String biomeType = VillagePathBlocks.inferBiomeType(level, bellPos, villagePieces);
        List<BankSite> safeSites = new ArrayList<>();
        for (int distance : DISTANCES_FROM_BELL) {
            for (int[] direction : DIRECTIONS) {
                int originX = bellPos.getX() + direction[0] * distance - TOP_SIZE / 2;
                int originZ = bellPos.getZ() + direction[1] * distance - TOP_SIZE / 2;
                BoundingBox footprint = new BoundingBox(originX, Integer.MIN_VALUE / 2, originZ,
                        originX + TOP_SIZE - 1, Integer.MAX_VALUE / 2, originZ + TOP_SIZE - 1);
                if (reservedCollision.test(footprint)) {
                    continue;
                }
                BankSite site = resolveSite(level, originX, originZ, bellPos,
                        villagePieces, managerPos, loadBudget);
                if (site == null) {
                    continue;
                }
                safeSites.add(site);
            }
        }

        Comparator<BankSite> terrainOrder = Comparator
                .comparingInt(BankSite::connectionCost)
                .thenComparingInt(BankSite::terrainCost)
                .thenComparingInt(BankSite::maxHeightDifference);
        safeSites.sort(terrainOrder);

        // Existing roads are connector targets, never valid bank footprints. The old
        // fallback selected a road-overlapping site when all safer candidates failed,
        // allowing the bank entrance and its connector to overwrite the street.
        BankSite selected = safeSites.isEmpty() ? null : safeSites.getFirst();
        if (selected != null) {
            return new PlannedBank(topTemplate.get(), vaultTemplate.get(), selected.origin(),
                    selected.rotation(), selected.pathStart(), selected.pathTarget(), biomeType);
        }

        EmeraldCapitalism.LOGGER.warn(
                "[ECAP] Could not find a safe bank site near village bell at {}", bellPos);
        return null;
    }

    /**
     * Places the buried vault-ruins template for a vanilla abandoned village.
     * Unlike a normal bank, this feature has no bank block or village link: it is
     * deliberately just the decayed replacement structure.
     */
    @Nullable
    public BlockPos placeAbandonedVault(ServerLevel level, BlockPos bellPos,
                                        List<StructurePiece> villagePieces) {
        PlannedAbandonedVault plan = planAbandonedVault(
                level, bellPos, villagePieces, new ChunkLoadBudget());
        return plan == null ? null : placePlannedAbandonedVault(level, plan);
    }

    @Nullable
    public PlannedAbandonedVault planAbandonedVault(ServerLevel level, BlockPos bellPos,
                                                    List<StructurePiece> villagePieces,
                                                    ChunkLoadBudget loadBudget) {
        return planAbandonedVault(level, bellPos, villagePieces, loadBudget, ignored -> false);
    }

    @Nullable
    public PlannedAbandonedVault planAbandonedVault(ServerLevel level, BlockPos bellPos,
                                                    List<StructurePiece> villagePieces,
                                                    ChunkLoadBudget loadBudget,
                                                    Predicate<BoundingBox> reservedCollision) {
        StructureTemplateManager templateManager = level.getStructureManager();
        Optional<StructureTemplate> ruinsTemplate = templateManager.get(BANK_VAULT_RUINS);
        if (ruinsTemplate.isEmpty()) {
            EmeraldCapitalism.LOGGER.error(
                    "[ECAP] Cannot generate abandoned-village vault near {}: missing template",
                    bellPos);
            return null;
        }

        StructureTemplate template = ruinsTemplate.get();
        int sizeX = template.getSize().getX();
        int sizeY = template.getSize().getY();
        int sizeZ = template.getSize().getZ();
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            EmeraldCapitalism.LOGGER.error(
                    "[ECAP] Cannot generate abandoned-village vault near {}: invalid template size {}x{}x{}",
                    bellPos, sizeX, sizeY, sizeZ);
            return null;
        }

        VillagePieceBounds pieceBounds = preparePieceBounds(villagePieces);
        for (int distance : DISTANCES_FROM_BELL) {
            for (int[] direction : DIRECTIONS) {
                int originX = bellPos.getX() + direction[0] * distance - sizeX / 2;
                int originZ = bellPos.getZ() + direction[1] * distance - sizeZ / 2;
                BoundingBox footprint = new BoundingBox(originX, Integer.MIN_VALUE / 2, originZ,
                        originX + sizeX - 1, Integer.MAX_VALUE / 2, originZ + sizeZ - 1);
                if (reservedCollision.test(footprint)) {
                    continue;
                }
                if (pieceBounds.overlapType(originX, originZ, sizeX, sizeZ)
                        != VillagePieceBounds.NO_OVERLAP
                        || !ensureTemplateChunksLoaded(level, originX, originZ, sizeX, sizeZ, loadBudget)) {
                    continue;
                }

                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG,
                        originX + sizeX / 2, originZ + sizeZ / 2) - 1;
                int originY = surfaceY - ABANDONED_VAULT_TOP_DEPTH - sizeY + 1;
                if (originY < com.orangevillager61.emeraldcapitalism.util.WorldHeightCompat.min(level)
                        || originY + sizeY > com.orangevillager61.emeraldcapitalism.util.WorldHeightCompat.max(level)) {
                    continue;
                }

                BlockPos origin = new BlockPos(originX, originY, originZ);
                return new PlannedAbandonedVault(template, origin, sizeX, sizeY, sizeZ, bellPos);
            }
        }

        EmeraldCapitalism.LOGGER.warn(
                "[ECAP] Could not find a safe abandoned-village vault site near bell at {}",
                bellPos);
        return null;
    }

    @Nullable
    public BlockPos placePlannedAbandonedVault(ServerLevel level, PlannedAbandonedVault plan) {
        VillageEntityRelocation.relocateFromBuilding(level, List.of(new BoundingBox(
                plan.origin().getX(), plan.origin().getY(), plan.origin().getZ(),
                plan.origin().getX() + plan.sizeX() - 1,
                plan.origin().getY() + plan.sizeY() - 1,
                plan.origin().getZ() + plan.sizeZ() - 1)));
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(false)
                .addProcessor(new BankVaultRuinsProcessor());
        if (!plan.template().placeInWorld(level, plan.origin(), plan.origin(), settings,
                level.getRandom(), 2)) {
            return null;
        }
        seedAbandonedVaultMap(level, plan);
        seedAbandonedVaultBankRuleBook(level, plan);
        VillageRegistryData.get(level).markAbandonedVaultPosition(plan.origin());
        EmeraldCapitalism.LOGGER.info(
                "[ECAP] Generated abandoned-village vault ruins at {} for bell {}",
                plan.origin(), plan.bellPos());
        return plan.origin();
    }

    /** Ensures every manually placed abandoned vault has one second-vault locator ticket. */
    private void seedAbandonedVaultMap(ServerLevel level, PlannedAbandonedVault plan) {
        BlockPos origin = plan.origin();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + plan.sizeX() - 1,
                origin.getY() + plan.sizeY() - 1,
                origin.getZ() + plan.sizeZ() - 1)) {
            if (!(level.getBlockEntity(pos) instanceof EmeraldChestBlockEntity chest)) {
                continue;
            }

            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                if (chest.getItem(slot).is(ECAPItems.SECOND_ABANDONED_VAULT_MAP.get())) {
                    return;
                }
            }
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                if (chest.getItem(slot).isEmpty()) {
                    chest.setItem(slot, new ItemStack(ECAPItems.SECOND_ABANDONED_VAULT_MAP.get()));
                    return;
                }
            }
        }

        EmeraldCapitalism.LOGGER.warn(
                "[ECAP] Abandoned vault at {} had no chest slot for its second-vault map",
                origin);
    }

    @Nullable
    private BankSite resolveSite(ServerLevel level, int originX, int originZ, BlockPos villageCenter,
                                 List<StructurePiece> villagePieces, @Nullable BlockPos managerPos,
                                 ChunkLoadBudget loadBudget) {
        if (!ensureChunksLoaded(level, originX, originZ, loadBudget)) {
            return null;
        }

        // A persisted pending-village retry may not retain the transient structure-piece
        // list. Recover structure starts from loaded chunk references before deciding
        // whether this bank would overlap an existing house.
        VillagePieceBounds pieceBounds = preparePieceBounds(
                level, originX, originZ, villagePieces);
        int overlap = pieceBounds.overlapType(originX, originZ, TOP_SIZE, TOP_SIZE);
        if (overlap != VillagePieceBounds.NO_OVERLAP
                || pieceBounds.overlapsBuilding(placementProtectionBox(originX, originZ))) {
            return null;
        }

        TerrainProfile terrain = findTerrainProfile(level, originX, originZ);
        if (terrain == null) {
            return null;
        }

        BlockPos origin = new BlockPos(originX, terrain.gradeY(), originZ);
        BlockPos vaultOrigin = origin.offset(VAULT_OFFSET);
        if (vaultOrigin.getY() < com.orangevillager61.emeraldcapitalism.util.WorldHeightCompat.min(level)
                || vaultOrigin.getY() + VAULT_HEIGHT > com.orangevillager61.emeraldcapitalism.util.WorldHeightCompat.max(level)) {
            return null;
        }

        if (containsProtectedBlock(level, origin, managerPos)) {
            return null;
        }

        BankOrientation orientation = chooseOrientation(origin, villageCenter, pieceBounds.pathBoxes());
        return new BankSite(origin, terrain.earthwork(), terrain.maxHeightDifference(),
                orientation.rotation(), orientation.pathStart(), orientation.pathTarget(),
                orientation.connectionCost());
    }

    /**
     * Selects the bank rotation whose fixed front entrance faces the nearest
     * village road. The template's authored front is local negative X.
     */
    private BankOrientation chooseOrientation(BlockPos topOrigin,
                                              BlockPos villageCenter, List<BoundingBox> pathBoxes) {
        BankOrientation best = null;
        for (Rotation rotation : BANK_ROTATIONS) {
            BlockPos topPlacePos = StructureTemplate.getZeroPositionWithTransform(
                    topOrigin, Mirror.NONE, rotation, TOP_SIZE, TOP_SIZE);
            BlockPos doorCenter = worldOffset(topPlacePos, BANK_DOOR_CENTER, rotation);
            BlockPos pathStart = worldOffset(topPlacePos, BANK_DOOR_PATH_START, rotation);
            BlockPos pathTarget = findNearestRoadPoint(pathStart, pathBoxes);
            if (pathTarget == null) {
                pathTarget = villageCenter;
            }

            int targetX = pathTarget.getX() - doorCenter.getX();
            int targetZ = pathTarget.getZ() - doorCenter.getZ();
            BlockPos front = rotateOffset(new BlockPos(-1, 0, 0), rotation);
            int frontDot = targetX * front.getX() + targetZ * front.getZ();
            double distance = Math.sqrt(targetX * targetX + targetZ * targetZ);
            int connectionCost = (int) Math.min(Integer.MAX_VALUE,
                    Math.round(distance * 10.0D) + (frontDot < 0 ? 10_000 : 0));

            BankOrientation candidate = new BankOrientation(rotation, pathStart, pathTarget, connectionCost);
            if (best == null || candidate.connectionCost() < best.connectionCost()) {
                best = candidate;
            }
        }
        return best;
    }

    @Nullable
    private BlockPos findNearestRoadPoint(BlockPos from, List<BoundingBox> pathBoxes) {
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

    private VillagePieceBounds preparePieceBounds(List<StructurePiece> pieces) {
        List<BoundingBox> paths = new ArrayList<>();
        List<BoundingBox> buildings = new ArrayList<>();
        for (StructurePiece piece : pieces) {
            (isPathPiece(piece) ? paths : buildings).add(piece.getBoundingBox());
        }
        return new VillagePieceBounds(List.copyOf(paths), List.copyOf(buildings));
    }

    private static BlockPos worldOffset(BlockPos templateOrigin, BlockPos localOffset, Rotation rotation) {
        return templateOrigin.offset(rotateOffset(localOffset, rotation));
    }

    private static BlockPos rotateOffset(BlockPos offset, Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new BlockPos(-offset.getZ(), offset.getY(), offset.getX());
            case CLOCKWISE_180 -> new BlockPos(-offset.getX(), offset.getY(), -offset.getZ());
            case COUNTERCLOCKWISE_90 -> new BlockPos(offset.getZ(), offset.getY(), -offset.getX());
            case NONE -> offset;
        };
    }

    private static BlockPos transformedMinCorner(BlockPos templateOrigin, Rotation rotation,
                                                  int sizeX, int sizeZ) {
        return switch (rotation) {
            case CLOCKWISE_90 -> templateOrigin.offset(-(sizeZ - 1), 0, 0);
            case CLOCKWISE_180 -> templateOrigin.offset(-(sizeX - 1), 0, -(sizeZ - 1));
            case COUNTERCLOCKWISE_90 -> templateOrigin.offset(0, 0, -(sizeX - 1));
            case NONE -> templateOrigin;
        };
    }

    private static boolean isQuarterTurn(Rotation rotation) {
        return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
    }

    private boolean ensureChunksLoaded(ServerLevel level, int originX, int originZ, ChunkLoadBudget budget) {
        // The bank can rotate after the site has been selected. Cover the
        // axis-aligned envelope of every possible vault orientation so a rotated
        // pair never writes into an unprepared chunk. The terrain blend and
        // entrance apron are also part of the placement transaction.
        int minX = originX - TERRAIN_BLEND_RADIUS - (VAULT_SIZE_Z - 1);
        int maxX = originX + TOP_SIZE - 1 + VAULT_SIZE_X + TERRAIN_BLEND_RADIUS;
        int minZ = originZ - TERRAIN_BLEND_RADIUS - (VAULT_SIZE_X - 1);
        int maxZ = originZ + TOP_SIZE - 1 + VAULT_SIZE_Z + TERRAIN_BLEND_RADIUS;
        return ensureChunksLoaded(level, minX, maxX, minZ, maxZ, budget);
    }

    private boolean ensureTemplateChunksLoaded(ServerLevel level, int originX, int originZ,
                                                int sizeX, int sizeZ, ChunkLoadBudget budget) {
        return ensureChunksLoaded(level, originX, originX + sizeX - 1,
                originZ, originZ + sizeZ - 1, budget);
    }

    private boolean ensureChunksLoaded(ServerLevel level, int minX, int maxX, int minZ, int maxZ,
                                       ChunkLoadBudget budget) {
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                if (!budget.ensureLoaded(level, chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Nullable
    private TerrainProfile findTerrainProfile(ServerLevel level, int originX, int originZ) {
        int[] heights = new int[TOP_SIZE * TOP_SIZE];
        int heightIndex = 0;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int x = originX; x < originX + TOP_SIZE; x++) {
            for (int z = originZ; z < originZ + TOP_SIZE; z++) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                BlockState state = level.getBlockState(new BlockPos(x, y, z));
                if (!state.getFluidState().isEmpty()) {
                    return null;
                }
                heights[heightIndex++] = y;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }

        Arrays.sort(heights);
        int gradeY = heights[heights.length / 2];
        int earthwork = 0;
        for (int height : heights) {
            earthwork += Math.abs(height - gradeY);
        }
        // Do not reject steep terrain: it remains a valid fallback if the village has
        // no flatter land. The score ensures it is chosen only after gentler sites.
        return new TerrainProfile(gradeY, earthwork, maxY - minY);
    }

    private boolean containsProtectedBlock(ServerLevel level, BlockPos origin, @Nullable BlockPos managerPos) {
        BoundingBox placementBox = placementProtectionBox(origin.getX(), origin.getZ());
        BoundingBox topBox = new BoundingBox(placementBox.minX(), origin.getY(), placementBox.minZ(),
                placementBox.maxX(), origin.getY() + TOP_HEIGHT + 4, placementBox.maxZ());
        if (managerPos != null && topBox.isInside(managerPos)) {
            return true;
        }
        for (BlockPos pos : BlockPos.betweenClosed(topBox.minX(), topBox.minY(), topBox.minZ(),
                topBox.maxX(), topBox.maxY(), topBox.maxZ())) {
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.BELL) || state.is(Blocks.FARMLAND) || state.is(Blocks.COMPOSTER)
                    || state.getBlock() == com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks.VILLAGE_MANAGER.get()
                    || state.getBlock() == com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks.BANK.get()) {
                return true;
            }
        }
        return false;
    }

    /** Covers every surface column and entrance-apron column that placement may rewrite. */
    private BoundingBox placementProtectionBox(int originX, int originZ) {
        return new BoundingBox(
                originX - TERRAIN_BLEND_RADIUS, Integer.MIN_VALUE / 2, originZ - TERRAIN_BLEND_RADIUS,
                originX + TOP_SIZE - 1 + TERRAIN_BLEND_RADIUS, Integer.MAX_VALUE / 2,
                originZ + TOP_SIZE - 1 + TERRAIN_BLEND_RADIUS);
    }

    @Nullable
    public PlacedBank placePlanned(ServerLevel level, PlannedBank plan) {
        StructureTemplate topTemplate = plan.topTemplate();
        StructureTemplate vaultTemplate = plan.vaultTemplate();
        BlockPos topOrigin = plan.origin();
        Rotation rotation = plan.rotation();
        BlockPos topPlacePos = StructureTemplate.getZeroPositionWithTransform(
                topOrigin, Mirror.NONE, rotation, TOP_SIZE, TOP_SIZE);
        BlockPos vaultOrigin = topPlacePos.offset(rotateOffset(VAULT_OFFSET, rotation));
        BlockPos vaultClearOrigin = transformedMinCorner(vaultOrigin, rotation, VAULT_SIZE_X, VAULT_SIZE_Z);
        int vaultClearSizeX = isQuarterTurn(rotation) ? VAULT_SIZE_Z : VAULT_SIZE_X;
        int vaultClearSizeZ = isQuarterTurn(rotation) ? VAULT_SIZE_X : VAULT_SIZE_Z;
        // Planning and placement are separated across server ticks. Recheck the
        // world immediately before mutating it so a house or protected block added
        // after planning cannot be replaced by a stale plan.
        VillagePieceBounds currentPieceBounds = preparePieceBounds(level,
                topOrigin.getX(), topOrigin.getZ(), List.of());
        if (currentPieceBounds.overlapsBuilding(placementProtectionBox(topOrigin.getX(), topOrigin.getZ()))
                || containsProtectedBlock(level, topOrigin, null)) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Refusing stale bank placement at {} because its write area is occupied",
                    topOrigin);
            return null;
        }
        PlacementSnapshot snapshot = PlacementSnapshot.capture(level,
                placementRollbackBox(level, topOrigin, vaultOrigin, vaultClearOrigin,
                        vaultClearSizeX, vaultClearSizeZ));
        boolean committed = false;
        try {
            // Template NBT stores only non-air blocks. Clear both volumes first so the
            // top is not obstructed by trees and the vault is genuinely excavated.
            levelTerrain(level, topOrigin);
            blendTerrainIntoSurroundings(level, topOrigin);
            // Do not clear the ground plane (Y=0). Its explicit air entries are restored
            // to dirt after placement so the structure cannot leave one-block-deep holes.
            clearVolume(level, topOrigin.above(), TOP_SIZE, TOP_HEIGHT - 1, TOP_SIZE);
            gradeAndClearEntrance(level, topPlacePos, rotation);
            clearVolume(level, vaultClearOrigin, vaultClearSizeX, VAULT_HEIGHT, vaultClearSizeZ);

            StructurePlaceSettings settings = new StructurePlaceSettings()
                    .setRotation(rotation)
                    .setMirror(Mirror.NONE)
                    .setIgnoreEntities(false);
            RandomSource random = level.getRandom();
            if (!vaultTemplate.placeInWorld(level, vaultOrigin, vaultOrigin, settings, random, 2)
                    || !topTemplate.placeInWorld(level, topPlacePos, topPlacePos, settings, random, 2)) {
                EmeraldCapitalism.LOGGER.error(
                        "[ECAP] Failed to place one or both bank templates at top={} vault={}",
                        topOrigin, vaultOrigin);
                return null;
            }
            restoreGroundPlane(level, topOrigin);
            grassifyFoundationDirtAdjacentToGrass(level, topOrigin);

            BlockPos bankPos = topPlacePos.offset(rotateOffset(BANK_BLOCK_OFFSET, rotation));
            BlockEntity bank = level.getBlockEntity(bankPos);
            if (!(bank instanceof BankBlockEntity generatedBank)) {
                EmeraldCapitalism.LOGGER.error(
                        "[ECAP] Generated bank template at {} does not contain a bank block entity at {}",
                        topOrigin, bankPos);
                return null;
            }

            // Structure block-entity NBT can retain the controller that saved the
            // template. Generated village banks always start independent so their
            // banker POI is available and villagers can use the public front.
            generatedBank.setController(null);
            alignGeneratedBankFacing(level, bankPos, rotation);
            BlockPos processorPos = topPlacePos.offset(rotateOffset(EMERALD_ORE_PROCESSOR_OFFSET, rotation));
            alignGeneratedProcessorFacing(level, processorPos, rotation);

            captureGolemConstructionLocation(level, topPlacePos, vaultOrigin, rotation,
                    generatedBank);

            replaceGeneratedBankCoalWithCharcoal(level, topPlacePos, rotation);
            seedInitialBread(level, vaultOrigin, rotation);
            seedInitialPumpkins(level, vaultOrigin, rotation);
        seedInitialLogs(level, vaultOrigin, rotation, plan.biomeType());
        seedInitialNearestVaultMap(level, vaultOrigin, rotation);
            seedInitialBankRuleBook(level, vaultOrigin, rotation);

            // Entity relocation is deferred until all fallible placement work succeeds,
            // so a failed generation attempt does not move villagers out of their homes.
            VillageEntityRelocation.relocateFromBuilding(level, List.of(
                    new BoundingBox(topPlacePos.getX(), topPlacePos.getY(), topPlacePos.getZ(),
                            topPlacePos.getX() + TOP_SIZE - 1,
                            topPlacePos.getY() + TOP_HEIGHT - 1,
                            topPlacePos.getZ() + TOP_SIZE - 1),
                    new BoundingBox(vaultClearOrigin.getX(), vaultOrigin.getY(), vaultClearOrigin.getZ(),
                            vaultClearOrigin.getX() + vaultClearSizeX - 1,
                            vaultOrigin.getY() + VAULT_HEIGHT - 1,
                            vaultClearOrigin.getZ() + vaultClearSizeZ - 1)));

            committed = true;
            return new PlacedBank(bankPos, vaultOrigin, rotation);
        } catch (Exception exception) {
            EmeraldCapitalism.LOGGER.error(
                    "[ECAP] Bank placement failed at top={} vault={}; restoring prior blocks",
                    topOrigin, vaultOrigin, exception);
            return null;
        } finally {
            if (!committed) {
                snapshot.restore(level);
            }
        }
    }

    private BoundingBox placementRollbackBox(ServerLevel level, BlockPos topOrigin,
                                             BlockPos vaultOrigin, BlockPos vaultClearOrigin,
                                             int vaultClearSizeX, int vaultClearSizeZ) {
        int minX = Math.min(topOrigin.getX() - TERRAIN_BLEND_RADIUS, vaultClearOrigin.getX());
        int minZ = Math.min(topOrigin.getZ() - TERRAIN_BLEND_RADIUS, vaultClearOrigin.getZ());
        int maxX = Math.max(topOrigin.getX() + TOP_SIZE - 1 + TERRAIN_BLEND_RADIUS,
                vaultClearOrigin.getX() + vaultClearSizeX - 1);
        int maxZ = Math.max(topOrigin.getZ() + TOP_SIZE - 1 + TERRAIN_BLEND_RADIUS,
                vaultClearOrigin.getZ() + vaultClearSizeZ - 1);
        int minSurface = Integer.MAX_VALUE;
        int maxSurface = Integer.MIN_VALUE;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                minSurface = Math.min(minSurface, surface);
                maxSurface = Math.max(maxSurface, surface);
            }
        }
        int minY = Math.max(com.orangevillager61.emeraldcapitalism.util.WorldHeightCompat.min(level),
                Math.min(Math.min(topOrigin.getY(), vaultOrigin.getY()), minSurface));
        int maxY = Math.min(com.orangevillager61.emeraldcapitalism.util.WorldHeightCompat.max(level) - 1,
                Math.max(Math.max(topOrigin.getY() + TOP_HEIGHT + 3,
                        vaultOrigin.getY() + VAULT_HEIGHT - 1), maxSurface));
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Replaces the authored starter fuel with charcoal in newly generated banks. */
    private void replaceGeneratedBankCoalWithCharcoal(ServerLevel level, BlockPos topOrigin,
                                                       Rotation rotation) {
        BlockPos minCorner = transformedMinCorner(topOrigin, rotation, TOP_SIZE, TOP_SIZE);

        for (int x = 0; x < TOP_SIZE; x++) {
            for (int y = 0; y < TOP_HEIGHT; y++) {
                for (int z = 0; z < TOP_SIZE; z++) {
                    BlockPos chestPos = minCorner.offset(x, y, z);
                    if (!(level.getBlockEntity(chestPos) instanceof EmeraldChestBlockEntity chest)) {
                        continue;
                    }
                    for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                        ItemStack existing = chest.getItem(slot);
                        if (existing.is(Items.COAL)) {
                            chest.setItem(slot, new ItemStack(Items.CHARCOAL, INITIAL_CHARCOAL_COUNT));
                        }
                    }
                }
            }
        }
    }

    /** Deferred entity work run after every pipeline structure and connector is complete. */
    public void finishPlannedPlacement(ServerLevel level, PlacedBank placed) {
        if (!(level.getBlockEntity(placed.bankPos()) instanceof BankBlockEntity bank)) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Cannot spawn inhabitants: generated bank is missing at {}",
                    placed.bankPos());
            return;
        }
        List<BlockPos> golemPositions = spawnVaultGolems(
                level, placed.vaultOrigin(), placed.rotation(), bank);
        spawnVaultVillagers(level, placed.vaultOrigin(), placed.rotation(), golemPositions);
    }

    /** Returns the world direction pointing outward from the authored bank doors. */
    private static Direction bankEntranceDirection(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> Direction.NORTH;
            case CLOCKWISE_180 -> Direction.EAST;
            case COUNTERCLOCKWISE_90 -> Direction.SOUTH;
            case NONE -> Direction.WEST;
        };
    }

    /**
     * Structure placement does not call {@link BankBlock#getStateForPlacement}.
     * Keep the generated bank's deposit-facing state aligned with the doors
     * and the rotation selected for the whole template.
     */
    private void alignGeneratedBankFacing(ServerLevel level, BlockPos bankPos, Rotation rotation) {
        BlockState state = level.getBlockState(bankPos);
        if (!state.is(ECAPBlocks.BANK.get()) || !state.hasProperty(BankBlock.FACING)) {
            return;
        }

        Direction expectedFacing = bankEntranceDirection(rotation);

        if (state.getValue(BankBlock.FACING) != expectedFacing) {
            level.setBlock(bankPos, state.setValue(BankBlock.FACING, expectedFacing), 2);
        }
    }

    /**
     * Keeps the generated processor's front aligned with its authored direction
     * after the bank template is rotated around the selected site.
     */
    private void alignGeneratedProcessorFacing(ServerLevel level, BlockPos processorPos,
                                                Rotation rotation) {
        BlockState state = level.getBlockState(processorPos);
        if (!state.is(ECAPBlocks.EMERALD_ORE_PROCESSOR.get())
                || !state.hasProperty(EmeraldOreProcessorBlock.FACING)) {
            return;
        }

        Direction expectedFacing = rotation.rotate(AUTHORED_EMERALD_ORE_PROCESSOR_FACING);
        if (state.getValue(EmeraldOreProcessorBlock.FACING) != expectedFacing) {
            level.setBlock(processorPos,
                    state.setValue(EmeraldOreProcessorBlock.FACING, expectedFacing), 2);
        }
    }

    /**
     * Resolves the authored construction marker into bank state, then removes the
     * marker from the generated world so it cannot be mistaken for a usable block.
     */
    private void captureGolemConstructionLocation(ServerLevel level, BlockPos topPlacePos,
                                                  BlockPos vaultOrigin, Rotation rotation,
                                                  BankBlockEntity bank) {
        List<BlockPos> markers = new ArrayList<>();
        BlockPos topMin = transformedMinCorner(topPlacePos, rotation, TOP_SIZE, TOP_SIZE);
        collectConstructionMarkers(level, topMin, TOP_SIZE, topPlacePos.getY(), TOP_HEIGHT,
                TOP_SIZE, markers);

        BlockPos vaultMin = transformedMinCorner(vaultOrigin, rotation, VAULT_SIZE_X, VAULT_SIZE_Z);
        int vaultSizeX = isQuarterTurn(rotation) ? VAULT_SIZE_Z : VAULT_SIZE_X;
        int vaultSizeZ = isQuarterTurn(rotation) ? VAULT_SIZE_X : VAULT_SIZE_Z;
        collectConstructionMarkers(level, vaultMin, vaultSizeX, vaultOrigin.getY(), VAULT_HEIGHT,
                vaultSizeZ, markers);

        if (markers.isEmpty()) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Generated bank at {} has no Golem Construction Location marker",
                    bank.getBlockPos());
            return;
        }

        if (markers.size() > 1) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Generated bank at {} has {} Golem Construction Location markers; using {}",
                    bank.getBlockPos(), markers.size(), markers.getFirst());
        }

        BlockPos constructionPos = markers.getFirst();
        bank.setGolemConstructionPos(constructionPos);
        for (BlockPos marker : markers) {
            level.setBlock(marker, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private void collectConstructionMarkers(ServerLevel level, BlockPos minCorner, int sizeX,
                                             int minY, int sizeY, int sizeZ,
                                             List<BlockPos> markers) {
        for (BlockPos pos : BlockPos.betweenClosed(
                minCorner.getX(), minY, minCorner.getZ(),
                minCorner.getX() + sizeX - 1, minY + sizeY - 1,
                minCorner.getZ() + sizeZ - 1)) {
            if (level.getBlockState(pos).is(ECAPBlocks.GOLEM_CONSTRUCTION_LOCATION.get())) {
                markers.add(pos.immutable());
            }
        }
    }

    /** Seeds newly generated normal bank vaults with 32 bread. */
    private void seedInitialBread(ServerLevel level, BlockPos vaultOrigin, Rotation rotation) {
        int remaining = 32;
        BlockPos minCorner = transformedMinCorner(vaultOrigin, rotation, VAULT_SIZE_X, VAULT_SIZE_Z);
        int sizeX = isQuarterTurn(rotation) ? VAULT_SIZE_Z : VAULT_SIZE_X;
        int sizeZ = isQuarterTurn(rotation) ? VAULT_SIZE_X : VAULT_SIZE_Z;

        for (int x = 0; x < sizeX && remaining > 0; x++) {
            for (int y = 0; y < VAULT_HEIGHT && remaining > 0; y++) {
                for (int z = 0; z < sizeZ && remaining > 0; z++) {
                    BlockPos chestPos = minCorner.offset(x, y, z);
                    if (!(level.getBlockEntity(chestPos) instanceof EmeraldChestBlockEntity chest)) {
                        continue;
                    }

                    for (int slot = 0; slot < chest.getContainerSize() && remaining > 0; slot++) {
                        ItemStack existing = chest.getItem(slot);
                        if (!existing.is(Items.BREAD) || existing.getCount() >= existing.getMaxStackSize()) {
                            continue;
                        }

                        int added = Math.min(remaining, existing.getMaxStackSize() - existing.getCount());
                        existing.grow(added);
                        chest.setItem(slot, existing);
                        remaining -= added;
                    }

                    for (int slot = 0; slot < chest.getContainerSize() && remaining > 0; slot++) {
                        if (!chest.getItem(slot).isEmpty()) {
                            continue;
                        }

                        int added = Math.min(remaining, Items.BREAD.getDefaultMaxStackSize());
                        chest.setItem(slot, new ItemStack(Items.BREAD, added));
                        remaining -= added;
                    }
                }
            }
        }

        if (remaining > 0) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Generated bank vault at {} could only fit {} of 32 starter bread",
                    vaultOrigin, 32 - remaining);
        }
    }

    /** Seeds newly generated normal bank vaults with three pumpkins. */
    private void seedInitialPumpkins(ServerLevel level, BlockPos vaultOrigin, Rotation rotation) {
        int remaining = INITIAL_PUMPKIN_COUNT;
        BlockPos minCorner = transformedMinCorner(vaultOrigin, rotation, VAULT_SIZE_X, VAULT_SIZE_Z);
        int sizeX = isQuarterTurn(rotation) ? VAULT_SIZE_Z : VAULT_SIZE_X;
        int sizeZ = isQuarterTurn(rotation) ? VAULT_SIZE_X : VAULT_SIZE_Z;

        for (int x = 0; x < sizeX && remaining > 0; x++) {
            for (int y = 0; y < VAULT_HEIGHT && remaining > 0; y++) {
                for (int z = 0; z < sizeZ && remaining > 0; z++) {
                    BlockPos chestPos = minCorner.offset(x, y, z);
                    if (!(level.getBlockEntity(chestPos) instanceof EmeraldChestBlockEntity chest)) {
                        continue;
                    }

                    for (int slot = 0; slot < chest.getContainerSize() && remaining > 0; slot++) {
                        ItemStack existing = chest.getItem(slot);
                        if (!existing.is(Items.PUMPKIN) || existing.getCount() >= existing.getMaxStackSize()) {
                            continue;
                        }

                        int added = Math.min(remaining, existing.getMaxStackSize() - existing.getCount());
                        existing.grow(added);
                        chest.setItem(slot, existing);
                        remaining -= added;
                    }

                    for (int slot = 0; slot < chest.getContainerSize() && remaining > 0; slot++) {
                        if (!chest.getItem(slot).isEmpty()) {
                            continue;
                        }

                        int added = Math.min(remaining, Items.PUMPKIN.getDefaultMaxStackSize());
                        chest.setItem(slot, new ItemStack(Items.PUMPKIN, added));
                        remaining -= added;
                    }
                }
            }
        }

        if (remaining > 0) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Generated bank vault at {} could only fit {} of {} starter pumpkins",
                    vaultOrigin, INITIAL_PUMPKIN_COUNT - remaining, INITIAL_PUMPKIN_COUNT);
        }
    }

    /** Seeds newly generated normal bank vaults with 48 logs matching the village biome. */
    private void seedInitialLogs(ServerLevel level, BlockPos vaultOrigin, Rotation rotation,
                                 String biomeType) {
        Item logs = starterLogsForBiome(biomeType);
        int remaining = INITIAL_LOG_COUNT;
        BlockPos minCorner = transformedMinCorner(vaultOrigin, rotation, VAULT_SIZE_X, VAULT_SIZE_Z);
        int sizeX = isQuarterTurn(rotation) ? VAULT_SIZE_Z : VAULT_SIZE_X;
        int sizeZ = isQuarterTurn(rotation) ? VAULT_SIZE_X : VAULT_SIZE_Z;

        for (int x = 0; x < sizeX && remaining > 0; x++) {
            for (int y = 0; y < VAULT_HEIGHT && remaining > 0; y++) {
                for (int z = 0; z < sizeZ && remaining > 0; z++) {
                    BlockPos chestPos = minCorner.offset(x, y, z);
                    if (!(level.getBlockEntity(chestPos) instanceof EmeraldChestBlockEntity chest)) {
                        continue;
                    }

                    for (int slot = 0; slot < chest.getContainerSize() && remaining > 0; slot++) {
                        ItemStack existing = chest.getItem(slot);
                        if (!existing.is(logs) || existing.getCount() >= existing.getMaxStackSize()) {
                            continue;
                        }

                        int added = Math.min(remaining, existing.getMaxStackSize() - existing.getCount());
                        existing.grow(added);
                        chest.setItem(slot, existing);
                        remaining -= added;
                    }

                    for (int slot = 0; slot < chest.getContainerSize() && remaining > 0; slot++) {
                        if (!chest.getItem(slot).isEmpty()) {
                            continue;
                        }

                        int added = Math.min(remaining, logs.getDefaultMaxStackSize());
                        chest.setItem(slot, new ItemStack(logs, added));
                        remaining -= added;
                    }
                }
            }
        }

        if (remaining > 0) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Generated bank vault at {} could only fit {} of {} starter {}",
                    vaultOrigin, INITIAL_LOG_COUNT - remaining, INITIAL_LOG_COUNT,
                    com.orangevillager61.emeraldcapitalism.util.ItemNameCompat.get(logs).getString());
        }
    }

    /** Seeds each generated bank with exactly one one-copy nearest-vault locator ticket. */
    private void seedInitialNearestVaultMap(ServerLevel level, BlockPos vaultOrigin, Rotation rotation) {
        BlockPos minCorner = transformedMinCorner(vaultOrigin, rotation, VAULT_SIZE_X, VAULT_SIZE_Z);
        int sizeX = isQuarterTurn(rotation) ? VAULT_SIZE_Z : VAULT_SIZE_X;
        int sizeZ = isQuarterTurn(rotation) ? VAULT_SIZE_X : VAULT_SIZE_Z;

        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < VAULT_HEIGHT; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    BlockPos chestPos = minCorner.offset(x, y, z);
                    if (!(level.getBlockEntity(chestPos) instanceof EmeraldChestBlockEntity chest)) {
                        continue;
                    }
                    for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                        if (chest.getItem(slot).isEmpty()) {
                            chest.setItem(slot, new ItemStack(ECAPItems.ABANDONED_VAULT_MAP.get()));
                            return;
                        }
                    }
                }
            }
        }

        EmeraldCapitalism.LOGGER.warn(
                "[ECAP] Generated bank vault at {} had no empty slot for its abandoned-vault map",
                vaultOrigin);
    }

    /** Seeds each generated bank with exactly one randomly selected bank-rule book. */
    private void seedInitialBankRuleBook(ServerLevel level, BlockPos vaultOrigin, Rotation rotation) {
        List<LibraryBookDefinition> books = LibraryBookRegistry.entries(LibraryBookRarity.BANK_RULE);
        if (books.isEmpty()) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Generated bank vault at {} has no bank-rule books available",
                    vaultOrigin);
            return;
        }

        LibraryBookDefinition book = books.get(level.getRandom().nextInt(books.size()));
        if (placeBankRuleBook(level, vaultOrigin, VAULT_SIZE_X, VAULT_HEIGHT, VAULT_SIZE_Z,
                rotation, book)) {
            return;
        }

        EmeraldCapitalism.LOGGER.warn(
                "[ECAP] Generated bank vault at {} had no empty slot for its bank-rule book",
                vaultOrigin);
    }

    /** Returns the vanilla village wood palette for the bank's generated biome style. */
    static Item starterLogsForBiome(String biomeType) {
        return switch (biomeType == null ? "" : biomeType.toUpperCase(Locale.ROOT)) {
            case "DESERT" -> Items.JUNGLE_LOG;
            case "SAVANNA" -> Items.ACACIA_LOG;
            case "TAIGA", "SNOWY" -> Items.SPRUCE_LOG;
            case "PLAINS" -> Items.OAK_LOG;
            default -> Items.OAK_LOG;
        };
    }

    /** Adds one bank-rule book to an abandoned vault on its single chance roll. */
    private void seedAbandonedVaultBankRuleBook(ServerLevel level, PlannedAbandonedVault plan) {
        if (level.getRandom().nextFloat() >= ABANDONED_VAULT_BANK_RULE_BOOK_CHANCE) {
            return;
        }

        List<LibraryBookDefinition> books = LibraryBookRegistry.entries(LibraryBookRarity.BANK_RULE);
        if (books.isEmpty()) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Abandoned vault at {} rolled a bank-rule book but none are available",
                    plan.origin());
            return;
        }

        LibraryBookDefinition book = books.get(level.getRandom().nextInt(books.size()));
        if (placeBankRuleBook(level, plan.origin(), plan.sizeX(), plan.sizeY(), plan.sizeZ(),
                Rotation.NONE, book)) {
            return;
        }

        EmeraldCapitalism.LOGGER.warn(
                "[ECAP] Abandoned vault at {} had no empty slot for its bank-rule book",
                plan.origin());
    }

    private boolean placeBankRuleBook(ServerLevel level, BlockPos origin, int sizeX, int sizeY,
                                      int sizeZ, Rotation rotation, LibraryBookDefinition book) {
        BlockPos minCorner = transformedMinCorner(origin, rotation, sizeX, sizeZ);
        int worldSizeX = isQuarterTurn(rotation) ? sizeZ : sizeX;
        int worldSizeZ = isQuarterTurn(rotation) ? sizeX : sizeZ;
        List<EmeraldChestBlockEntity> chests = new ArrayList<>();
        for (int x = 0; x < worldSizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < worldSizeZ; z++) {
                    BlockPos chestPos = minCorner.offset(x, y, z);
                    if (!(level.getBlockEntity(chestPos) instanceof EmeraldChestBlockEntity chest)) {
                        continue;
                    }
                    chests.add(chest);
                }
            }
        }

        // Structure placement is a fresh-bank boundary, but remove any authored copies
        // first so the generated bank has exactly one bank-rule book even if its template
        // later gains a prefilled copy.
        for (EmeraldChestBlockEntity chest : chests) {
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                if (isBankRuleBook(chest.getItem(slot))) {
                    chest.removeItemNoUpdate(slot);
                }
            }
        }

        for (EmeraldChestBlockEntity chest : chests) {
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                if (chest.getItem(slot).isEmpty()) {
                    chest.setItem(slot, LibraryBookStackFactory.createItemStack(book, level));
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isBankRuleBook(ItemStack stack) {
        if (!stack.is(Items.WRITTEN_BOOK)) {
            return false;
        }
        CustomData metadata = stack.get(DataComponents.CUSTOM_DATA);
        return metadata != null
                && LibraryBookRarity.BANK_RULE.id().equals(metadata.copyTag().getString("book_rarity"));
    }

    /**
     * Adds two guards after the vault is placed, using clear floor positions rather
     * than fixed coordinates.
     */
    private List<BlockPos> spawnVaultGolems(ServerLevel level, BlockPos vaultOrigin, Rotation rotation,
                                            BankBlockEntity bank) {
        List<BlockPos> spawnPositions = findVaultGolemSpawnPositions(level, vaultOrigin, rotation);
        if (spawnPositions.size() < 2) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Generated bank vault at {} has insufficient clear space for its golems",
                    vaultOrigin);
            return List.of();
        }

        EmeraldGolem emeraldGolem = spawnEmeraldGolem(level, spawnPositions.getFirst(), bank);
        if (emeraldGolem != null) {
            bank.registerEmeraldGolemEmployee(emeraldGolem.getUUID());
        }
        spawnIronGolem(level, spawnPositions.get(1));
        return spawnPositions;
    }

    private void spawnVaultVillagers(ServerLevel level, BlockPos vaultOrigin, Rotation rotation,
                                     List<BlockPos> occupiedPositions) {
        List<BlockPos> spawnPositions = findVaultVillagerSpawnPositions(
                level, vaultOrigin, rotation, occupiedPositions);
        if (spawnPositions.size() < VAULT_VILLAGER_COUNT) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Generated bank vault at {} has insufficient clear space for its villagers",
                    vaultOrigin);
            return;
        }

        for (BlockPos spawnPos : spawnPositions) {
            Villager villager = com.orangevillager61.emeraldcapitalism.util.EntityCreation.create(EntityType.VILLAGER, level);
            if (villager == null) {
                EmeraldCapitalism.LOGGER.warn(
                        "[ECAP] Failed to create vault villager at {}", spawnPos);
                continue;
            }

            villager.moveTo(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D,
                    level.getRandom().nextFloat() * 360.0F, 0.0F);
            SpawnReasonCompat.finalizeStructure(villager, level, level.getCurrentDifficultyAt(spawnPos), null);
            villager.setPersistenceRequired();
            if (!level.addFreshEntity(villager)) {
                EmeraldCapitalism.LOGGER.warn(
                        "[ECAP] Failed to add vault villager at {}", spawnPos);
            }
        }
    }

    private List<BlockPos> findVaultGolemSpawnPositions(ServerLevel level, BlockPos vaultOrigin,
                                                        Rotation rotation) {
        List<BlockPos> spawnPositions = new ArrayList<>(2);
        for (int y = 1; y < VAULT_HEIGHT - 2 && spawnPositions.size() < 2; y++) {
            for (int x = 1; x < VAULT_SIZE_X - 1 && spawnPositions.size() < 2; x++) {
                for (int z = 1; z < VAULT_SIZE_Z - 1 && spawnPositions.size() < 2; z++) {
                    BlockPos candidate = worldOffset(vaultOrigin, new BlockPos(x, y, z), rotation);
                    if (isClearGolemSpawnSpace(level, candidate)
                            && isSeparatedFromExistingSpawns(candidate, spawnPositions)) {
                        spawnPositions.add(candidate);
                    }
                }
            }
        }
        return spawnPositions;
    }

    private List<BlockPos> findVaultVillagerSpawnPositions(ServerLevel level, BlockPos vaultOrigin,
                                                            Rotation rotation,
                                                            List<BlockPos> occupiedPositions) {
        List<BlockPos> spawnPositions = new ArrayList<>(VAULT_VILLAGER_COUNT);
        for (int y = 1; y < VAULT_HEIGHT - 1 && spawnPositions.size() < VAULT_VILLAGER_COUNT; y++) {
            for (int x = 1; x < VAULT_SIZE_X - 1 && spawnPositions.size() < VAULT_VILLAGER_COUNT; x++) {
                for (int z = 1; z < VAULT_SIZE_Z - 1 && spawnPositions.size() < VAULT_VILLAGER_COUNT; z++) {
                    BlockPos candidate = worldOffset(vaultOrigin, new BlockPos(x, y, z), rotation);
                    if (isClearVillagerSpawnSpace(level, candidate)
                            && isSeparatedFromExistingSpawns(candidate, occupiedPositions)
                            && !spawnPositions.contains(candidate)) {
                        spawnPositions.add(candidate);
                    }
                }
            }
        }
        return spawnPositions;
    }

    private boolean isClearGolemSpawnSpace(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                && level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir()
                && level.getBlockState(pos.above(2)).isAir();
    }

    private boolean isClearVillagerSpawnSpace(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                && level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir();
    }

    private boolean isSeparatedFromExistingSpawns(BlockPos candidate, List<BlockPos> existingSpawns) {
        for (BlockPos existing : existingSpawns) {
            int deltaX = candidate.getX() - existing.getX();
            int deltaZ = candidate.getZ() - existing.getZ();
            if (deltaX * deltaX + deltaZ * deltaZ < 4) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private EmeraldGolem spawnEmeraldGolem(ServerLevel level, BlockPos spawnPos, BankBlockEntity bank) {
        EmeraldGolem golem = com.orangevillager61.emeraldcapitalism.util.EntityCreation.create(
                ECAPEntityTypes.EMERALD_GOLEM.get(), level);
        if (golem == null) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Failed to create emerald vault golem at {}", spawnPos);
            return null;
        }

        golem.moveTo(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D,
                level.getRandom().nextFloat() * 360.0F, 0.0F);
        SpawnReasonCompat.finalizeStructure(golem, level, level.getCurrentDifficultyAt(spawnPos), null);
        golem.setCustomName(Component.literal("Vault Golem"));
        golem.setBankEmployeePos(bank.getBlockPos());
        VaultGolemGoals.markAsVaultGuard(golem);
        golem.setPersistenceRequired();
        if (!level.addFreshEntity(golem)) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Failed to add emerald vault golem at {}", spawnPos);
            return null;
        }
        return golem;
    }

    private void spawnIronGolem(ServerLevel level, BlockPos spawnPos) {
        IronGolem golem = com.orangevillager61.emeraldcapitalism.util.EntityCreation.create(EntityType.IRON_GOLEM, level);
        if (golem == null) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Failed to create iron vault golem at {}", spawnPos);
            return;
        }

        golem.moveTo(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D,
                level.getRandom().nextFloat() * 360.0F, 0.0F);
        SpawnReasonCompat.finalizeStructure(golem, level, level.getCurrentDifficultyAt(spawnPos), null);
        golem.setCustomName(Component.literal("Vault Golem"));
        VaultGolemGoals.markAsVaultGuard(golem);
        golem.setPersistenceRequired();
        if (!level.addFreshEntity(golem)) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Failed to add iron vault golem at {}", spawnPos);
        }
    }

    private void levelTerrain(ServerLevel level, BlockPos origin) {
        for (int x = origin.getX(); x < origin.getX() + TOP_SIZE; x++) {
            for (int z = origin.getZ(); z < origin.getZ() + TOP_SIZE; z++) {
                gradeColumn(level, x, z, origin.getY(), false);
            }
        }
    }

    /**
     * Softens the square foundation cut/fill into short grass-topped terraces. The
     * building itself still has a stable, level footprint; only the surrounding ring
     * is blended back toward the original terrain height.
     */
    private void blendTerrainIntoSurroundings(ServerLevel level, BlockPos origin) {
        int minX = origin.getX();
        int maxX = minX + TOP_SIZE - 1;
        int minZ = origin.getZ();
        int maxZ = minZ + TOP_SIZE - 1;
        for (int x = minX - TERRAIN_BLEND_RADIUS; x <= maxX + TERRAIN_BLEND_RADIUS; x++) {
            for (int z = minZ - TERRAIN_BLEND_RADIUS; z <= maxZ + TERRAIN_BLEND_RADIUS; z++) {
                int dx = x < minX ? minX - x : Math.max(0, x - maxX);
                int dz = z < minZ ? minZ - z : Math.max(0, z - maxZ);
                int distanceFromFootprint = Math.max(dx, dz);
                if (distanceFromFootprint == 0) {
                    continue;
                }

                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                int gradeWeight = TERRAIN_BLEND_RADIUS + 1 - distanceFromFootprint;
                int targetY = surfaceY + Math.round(
                        (origin.getY() - surfaceY) * (gradeWeight / (float) (TERRAIN_BLEND_RADIUS + 1)));
                gradeColumn(level, x, z, surfaceY, targetY, true);
            }
        }
    }

    private void gradeColumn(ServerLevel level, int x, int z, int targetY, boolean grassTop) {
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        gradeColumn(level, x, z, surfaceY, targetY, grassTop);
    }

    private void gradeColumn(ServerLevel level, int x, int z, int surfaceY,
                             int targetY, boolean grassTop) {
        if (surfaceY > targetY) {
            for (int y = targetY + 1; y <= surfaceY; y++) {
                level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
        } else if (surfaceY < targetY) {
            for (int y = surfaceY + 1; y <= targetY; y++) {
                level.setBlock(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState(), 2);
            }
        }
        if (grassTop) {
            BlockPos topPos = new BlockPos(x, targetY, z);
            if (!level.getBlockState(topPos).is(Blocks.GRASS_BLOCK)) {
                level.setBlock(topPos, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            }
        }
    }

    /**
     * The bank's double doors are at local X=0, Z=4..5, and open toward local
     * negative X. The template cannot clear terrain outside its own bounds, so
     * grade and clear the rotated entrance apron before placing the structure.
     */
    private void gradeAndClearEntrance(ServerLevel level, BlockPos topPlacePos, Rotation rotation) {
        int groundY = topPlacePos.getY();
        for (int localX = -4; localX < 0; localX++) {
            for (int localZ = 3; localZ <= 6; localZ++) {
                BlockPos entrancePos = worldOffset(topPlacePos, new BlockPos(localX, 0, localZ), rotation);
                int x = entrancePos.getX();
                int z = entrancePos.getZ();
                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (surfaceY > groundY) {
                    for (int y = groundY + 1; y <= surfaceY; y++) {
                        level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                } else if (surfaceY < groundY) {
                    for (int y = surfaceY + 1; y <= groundY; y++) {
                        level.setBlock(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState(), 2);
                    }
                }

                // Keep the ground block at Y=0, but make the full three-block-high
                // doorway and approach air so hills and vegetation cannot seal it.
                for (int y = groundY + 1; y <= groundY + 3; y++) {
                    BlockPos clearPos = new BlockPos(x, y, z);
                    if (!level.getBlockState(clearPos).is(Blocks.AIR)) {
                        level.setBlock(clearPos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    /**
     * Structure templates explicitly contain air in their Y=0 layer. Placing that
     * air overwrites the terrain fill and exposes the space below the foundation.
     * Refill only those air cells; authored foundation blocks and ladders are left
     * unchanged.
     */
    private void restoreGroundPlane(ServerLevel level, BlockPos topOrigin) {
        for (int x = topOrigin.getX(); x < topOrigin.getX() + TOP_SIZE; x++) {
            for (int z = topOrigin.getZ(); z < topOrigin.getZ() + TOP_SIZE; z++) {
                BlockPos groundPos = new BlockPos(x, topOrigin.getY(), z);
                if (level.getBlockState(groundPos).isAir()) {
                    level.setBlock(groundPos, Blocks.DIRT.defaultBlockState(), 2);
                }
            }
        }
    }

    /**
     * Lets the exposed dirt foundation blend into adjacent terrain immediately,
     * rather than leaving a hard dirt edge until normal grass spread reaches it.
     */
    private void grassifyFoundationDirtAdjacentToGrass(ServerLevel level, BlockPos topOrigin) {
        List<BlockPos> grassPositions = new ArrayList<>();
        for (int x = topOrigin.getX(); x < topOrigin.getX() + TOP_SIZE; x++) {
            for (int z = topOrigin.getZ(); z < topOrigin.getZ() + TOP_SIZE; z++) {
                BlockPos foundationPos = new BlockPos(x, topOrigin.getY(), z);
                if (!level.getBlockState(foundationPos).is(Blocks.DIRT)) {
                    continue;
                }

                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    if (level.getBlockState(foundationPos.relative(direction)).is(Blocks.GRASS_BLOCK)) {
                        grassPositions.add(foundationPos);
                        break;
                    }
                }
            }
        }

        for (BlockPos grassPos : grassPositions) {
            level.setBlock(grassPos, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        }
    }

    private void clearVolume(ServerLevel level, BlockPos origin, int sizeX, int sizeY, int sizeZ) {
        for (int x = origin.getX(); x < origin.getX() + sizeX; x++) {
            for (int y = origin.getY(); y < origin.getY() + sizeY; y++) {
                for (int z = origin.getZ(); z < origin.getZ() + sizeZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).is(Blocks.AIR)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    private static boolean hasExpectedSize(StructureTemplate template, int x, int y, int z) {
        return template.getSize().getX() == x && template.getSize().getY() == y && template.getSize().getZ() == z;
    }

    private VillagePieceBounds preparePieceBounds(ServerLevel level, int originX, int originZ,
                                                    List<StructurePiece> fallbackPieces) {
        List<StructurePiece> pieces = new ArrayList<>(fallbackPieces);
        int minX = originX - TERRAIN_BLEND_RADIUS;
        int maxX = originX + TOP_SIZE - 1 + TERRAIN_BLEND_RADIUS;
        int minZ = originZ - TERRAIN_BLEND_RADIUS;
        int maxZ = originZ + TOP_SIZE - 1 + TERRAIN_BLEND_RADIUS;
        Registry<Structure> structureRegistry =
                com.orangevillager61.emeraldcapitalism.util.RegistryAccessCompat.get(
                        level.registryAccess(), Registries.STRUCTURE);
        Set<StructureStart> visitedStarts = new HashSet<>();

        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ);
                for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
                    collectVillageStructurePieces(structureRegistry, entry.getKey(), entry.getValue(),
                            pieces, visitedStarts);
                }
                for (Map.Entry<Structure, LongSet> entry : chunk.getAllReferences().entrySet()) {
                    if (!isVillageStructure(structureRegistry.getKey(entry.getKey()))) {
                        continue;
                    }
                    for (long startChunkLong : entry.getValue()) {
                        int startChunkX = ChunkPos.getX(startChunkLong);
                        int startChunkZ = ChunkPos.getZ(startChunkLong);
                        if (!level.hasChunk(startChunkX, startChunkZ)) {
                            continue;
                        }
                        StructureStart start = level.getChunk(startChunkX, startChunkZ)
                                .getStartForStructure(entry.getKey());
                        collectVillageStructurePieces(structureRegistry, entry.getKey(), start,
                                pieces, visitedStarts);
                    }
                }
            }
        }
        return preparePieceBounds(pieces);
    }

    private void collectVillageStructurePieces(Registry<Structure> structureRegistry, Structure structure,
                                                @Nullable StructureStart start,
                                                List<StructurePiece> pieces,
                                                Set<StructureStart> visitedStarts) {
        if (start == null || !start.isValid() || !visitedStarts.add(start)
                || !isVillageStructure(structureRegistry.getKey(structure))) {
            return;
        }
        pieces.addAll(start.getPieces());
    }

    private static boolean isVillageStructure(@Nullable ResourceLocation structureId) {
        if (structureId == null || !structureId.getNamespace().equals("minecraft")) {
            return false;
        }
        return switch (structureId.getPath()) {
            case "village_plains", "village_desert", "village_savanna",
                 "village_taiga", "village_snowy" -> true;
            default -> false;
        };
    }

    /** Bounded rollback state for the two-template bank placement transaction. */
    private static final class PlacementSnapshot {
        private final Map<BlockPos, BlockSnapshot> blocks;

        private PlacementSnapshot(Map<BlockPos, BlockSnapshot> blocks) {
            this.blocks = blocks;
        }

        private static PlacementSnapshot capture(ServerLevel level, BoundingBox bounds) {
            Map<BlockPos, BlockSnapshot> blocks = new HashMap<>();
            for (BlockPos pos : BlockPos.betweenClosed(
                    bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
                BlockPos immutablePos = pos.immutable();
                BlockEntity blockEntity = level.getBlockEntity(immutablePos);
                blocks.put(immutablePos, new BlockSnapshot(
                        level.getBlockState(immutablePos),
                        blockEntity == null ? null : blockEntity.saveWithId(level.registryAccess())));
            }
            return new PlacementSnapshot(blocks);
        }

        private void restore(ServerLevel level) {
            for (Map.Entry<BlockPos, BlockSnapshot> entry : blocks.entrySet()) {
                BlockPos pos = entry.getKey();
                level.removeBlockEntity(pos);
                level.setBlock(pos, entry.getValue().state(), 3);
            }
            for (Map.Entry<BlockPos, BlockSnapshot> entry : blocks.entrySet()) {
                CompoundTag blockEntityData = entry.getValue().blockEntityData();
                if (blockEntityData == null) {
                    continue;
                }
                BlockPos pos = entry.getKey();
                BlockEntity restored = BlockEntity.loadStatic(
                        pos, entry.getValue().state(), blockEntityData, level.registryAccess());
                if (restored != null) {
                    level.setBlockEntity(restored);
                }
            }
        }
    }

    private record BlockSnapshot(BlockState state, @Nullable CompoundTag blockEntityData) {
    }

    private static boolean isPathPiece(StructurePiece piece) {
        if (piece instanceof PoolElementStructurePiece poolPiece) {
            String element = poolPiece.getElement().toString().toLowerCase(Locale.ROOT);
            return element.contains("/streets/") || element.contains("/street/");
        }
        return false;
    }

    public record PlannedBank(StructureTemplate topTemplate, StructureTemplate vaultTemplate,
                              BlockPos origin, Rotation rotation,
                              BlockPos pathStart, BlockPos pathTarget, String biomeType) {
        public Direction entranceDirection() {
            return bankEntranceDirection(rotation);
        }

        public BlockPos predictedBankPos() {
            BlockPos topPlacePos = StructureTemplate.getZeroPositionWithTransform(
                    origin, Mirror.NONE, rotation, TOP_SIZE, TOP_SIZE);
            return topPlacePos.offset(rotateOffset(BANK_BLOCK_OFFSET, rotation));
        }

        /** Same bank footprint plus doorway apron historically protected from farms. */
        public BoundingBox reservationBox() {
            BlockPos bankPos = predictedBankPos();
            return new BoundingBox(
                    bankPos.getX() - 15, Integer.MIN_VALUE / 2, bankPos.getZ() - 13,
                    bankPos.getX() + 14, Integer.MAX_VALUE / 2, bankPos.getZ() + 16);
        }

        public BoundingBox placementBox() {
            return new BoundingBox(origin.getX(), Integer.MIN_VALUE / 2, origin.getZ(),
                    origin.getX() + TOP_SIZE - 1, Integer.MAX_VALUE / 2,
                    origin.getZ() + TOP_SIZE - 1);
        }
    }

    public record PlacedBank(BlockPos bankPos, BlockPos vaultOrigin, Rotation rotation) {
    }

    public record PlannedAbandonedVault(StructureTemplate template, BlockPos origin,
                                        int sizeX, int sizeY, int sizeZ, BlockPos bellPos) {
        public BoundingBox reservationBox() {
            return new BoundingBox(origin.getX(), Integer.MIN_VALUE / 2, origin.getZ(),
                    origin.getX() + sizeX - 1, Integer.MAX_VALUE / 2,
                    origin.getZ() + sizeZ - 1);
        }
    }

    private record TerrainProfile(int gradeY, int earthwork, int maxHeightDifference) {}

    private record BankOrientation(Rotation rotation, BlockPos pathStart, BlockPos pathTarget,
                                   int connectionCost) {}

    private record BankSite(BlockPos origin, int terrainCost, int maxHeightDifference,
                            Rotation rotation, BlockPos pathStart, BlockPos pathTarget,
                            int connectionCost) {}

    private record VillagePieceBounds(List<BoundingBox> pathBoxes,
                                      List<BoundingBox> buildingBoxes) {
        private static final int NO_OVERLAP = 0;
        private static final int PATH_OVERLAP = 1;
        private static final int BUILDING_OVERLAP = 2;

        private int overlapType(int originX, int originZ, int sizeX, int sizeZ) {
            BoundingBox footprint = new BoundingBox(originX, Integer.MIN_VALUE / 2, originZ,
                    originX + sizeX - 1, Integer.MAX_VALUE / 2, originZ + sizeZ - 1);
            for (BoundingBox building : buildingBoxes) {
                if (footprint.intersects(building)) {
                    return BUILDING_OVERLAP;
                }
            }
            for (BoundingBox path : pathBoxes) {
                if (footprint.intersects(path)) {
                    return PATH_OVERLAP;
                }
            }
            return NO_OVERLAP;
        }

        private boolean overlapsBuilding(BoundingBox footprint) {
            for (BoundingBox building : buildingBoxes) {
                if (footprint.intersects(building)) {
                    return true;
                }
            }
            return false;
        }
    }
}
