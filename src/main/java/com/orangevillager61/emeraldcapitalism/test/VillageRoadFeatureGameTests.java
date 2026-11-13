package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.world.village.VillageBridgeBlocks;
import com.orangevillager61.emeraldcapitalism.world.village.VillageLumbermillStructurePlacer;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRoadPathGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * Connector paths grade 2+ block terrain steps into stair ramps and span
 * short water channels with railed bridges.
 */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class VillageRoadFeatureGameTests {
    private static final int CLIFF_X = 6;
    private static final int CHANNEL_MIN_X = 11;
    private static final int CHANNEL_MAX_X = 13;
    private static final int STREET_MIN_X = 17;
    private static final int STREET_MAX_X = 18;
    private static final int LOW_SURFACE_Y = 1;
    private static final int HIGH_SURFACE_Y = 4;
    private static final int WATER_Y = 3;
    private static final int ROUTE_Z = 10;

    private VillageRoadFeatureGameTests() {
    }

    @GameTest(template = "empty_20x3x20", timeoutTicks = 100, batch = "ecap_road_features")
    public static void connectorClimbsCliffWithStairsAndBridgesWater(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        List<int[]> forcedChunks = forceChunks(level, origin);
        try {
            buildTerrain(helper, CLIFF_X, CHANNEL_MIN_X, CHANNEL_MAX_X);
            VillageRoadPathGenerator generator = new VillageRoadPathGenerator();
            BlockPos streetMin = helper.absolutePos(new BlockPos(STREET_MIN_X, HIGH_SURFACE_Y, 0));
            BlockPos streetMax = helper.absolutePos(new BlockPos(STREET_MAX_X, HIGH_SURFACE_Y, 19));
            VillageRoadPathGenerator.PreparedVillageRoads roads = generator.prepareStreetArea(level,
                    BoundingBox.fromCorners(streetMin, streetMax));
            VillageRoadPathGenerator.PlannedPath path = generator.planLumbermillConnection(level,
                    helper.absolutePos(new BlockPos(1, LOW_SURFACE_Y + 1, ROUTE_Z)),
                    helper.absolutePos(new BlockPos(STREET_MIN_X, HIGH_SURFACE_Y, ROUTE_Z)),
                    List.of(), "PLAINS", Direction.EAST, roads, null);
            helper.assertTrue(path != null, "connector over a cliff and a channel was not planned");
            generator.place(level, path);

            for (int z = ROUTE_Z - 1; z <= ROUTE_Z + 1; z++) {
                // Ramp: the 3-block cliff is graded into stairs rising one block per column.
                assertStair(helper, CLIFF_X - 1, LOW_SURFACE_Y + 1, z);
                assertStair(helper, CLIFF_X, LOW_SURFACE_Y + 2, z);
                assertStair(helper, CLIFF_X + 1, LOW_SURFACE_Y + 3, z);
                assertBlock(helper, CLIFF_X - 1, LOW_SURFACE_Y, z, Blocks.DIRT,
                        "dirt below the first stair");
                assertBlock(helper, CLIFF_X, LOW_SURFACE_Y + 1, z, Blocks.DIRT,
                        "dirt below the graded second stair");
                assertBlock(helper, CLIFF_X, HIGH_SURFACE_Y, z, Blocks.AIR, "ramp headroom cut");
                assertBlock(helper, CLIFF_X + 2, HIGH_SURFACE_Y, z, Blocks.DIRT_PATH,
                        "path at the top of the ramp");

                // Bridge: a deck level with the banks over the water channel.
                for (int x = CHANNEL_MIN_X; x <= CHANNEL_MAX_X; x++) {
                    assertBlock(helper, x, HIGH_SURFACE_Y, z, Blocks.OAK_PLANKS, "bridge deck");
                }
                assertBlock(helper, CHANNEL_MIN_X - 1, HIGH_SURFACE_Y, z, Blocks.DIRT_PATH,
                        "path before the bridge");
                assertBlock(helper, CHANNEL_MAX_X + 1, HIGH_SURFACE_Y, z, Blocks.DIRT_PATH,
                        "path after the bridge");
            }
            for (int x = CHANNEL_MIN_X; x <= CHANNEL_MAX_X; x++) {
                assertBlock(helper, x, HIGH_SURFACE_Y + 1, ROUTE_Z - 2, Blocks.OAK_FENCE, "north railing");
                assertBlock(helper, x, HIGH_SURFACE_Y + 1, ROUTE_Z + 2, Blocks.OAK_FENCE, "south railing");
            }
            for (int x : new int[] {CHANNEL_MIN_X, CHANNEL_MAX_X}) {
                assertBlock(helper, x, WATER_Y, ROUTE_Z - 2, Blocks.OAK_LOG, "bridge end post");
                assertBlock(helper, x, WATER_Y, ROUTE_Z + 2, Blocks.OAK_LOG, "bridge end post");
            }
            assertBlock(helper, CHANNEL_MIN_X + 1, WATER_Y, ROUTE_Z, Blocks.WATER,
                    "water under the deck");

            // A later connector walks over the existing stairs and deck and never repaves them.
            VillageRoadPathGenerator.PlannedPath second = generator.planLumbermillConnection(level,
                    helper.absolutePos(new BlockPos(1, LOW_SURFACE_Y + 1, ROUTE_Z)),
                    helper.absolutePos(new BlockPos(STREET_MIN_X, HIGH_SURFACE_Y, ROUTE_Z)),
                    List.of(), "PLAINS", Direction.EAST, roads, null);
            helper.assertTrue(second != null, "a second connector could not cross the first one's stairs and deck");
            for (BlockPos cell : second.reservedSurfaceCells()) {
                BlockState state = level.getBlockState(cell);
                helper.assertTrue(!state.is(ECAPBlocks.DIRT_PATH_STAIRS.get()) && !state.is(Blocks.OAK_PLANKS),
                        "second connector would repave " + state + " at " + cell);
            }

        } catch (RuntimeException exception) {
            releaseChunks(level, forcedChunks);
            throw exception;
        }

        // The cliff is too high to jump, so a villager can reach the top only by
        // the stairs. Navigation plans only once the villager has landed.
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, LOW_SURFACE_Y + 1, ROUTE_Z));
        helper.runAfterDelay(10, () -> {
            try {
                Path route = villager.getNavigation().createPath(
                        helper.absolutePos(new BlockPos(CLIFF_X + 3, HIGH_SURFACE_Y + 1, ROUTE_Z)), 0);
                helper.assertTrue(route != null && route.canReach(),
                        "villager pathfinding cannot climb the generated stair ramp");
                helper.succeed();
            } finally {
                releaseChunks(level, forcedChunks);
            }
        });
    }

    private static void releaseChunks(ServerLevel level, List<int[]> forcedChunks) {
        for (int[] chunk : forcedChunks) {
            level.setChunkForced(chunk[0], chunk[1], false);
        }
    }

    @GameTest(template = "empty_3x3x3", batch = "ecap_road_features")
    public static void bridgeMaterialsFollowVillageType(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        assertBridge(helper, VillageBridgeBlocks.forBiome("SAVANNA"), Blocks.ACACIA_PLANKS, Blocks.ACACIA_LOG);
        assertBridge(helper, VillageBridgeBlocks.forBiome("DESERT"), Blocks.SMOOTH_SANDSTONE,
                Blocks.CUT_SANDSTONE);
        assertBridge(helper, VillageBridgeBlocks.forBiome("PLAINS"), Blocks.OAK_PLANKS, Blocks.OAK_LOG);
        boolean sawBirch = false;
        boolean sawOak = false;
        for (int index = 0; index < 64; index++) {
            BlockPos bell = new BlockPos(index * 97, 64, index * -53);
            boolean variantTwo = VillageLumbermillStructurePlacer.isPlainsVariantTwoSelected(level.getSeed(), bell);
            VillageBridgeBlocks blocks = VillageBridgeBlocks.forVillage(level, bell, "PLAINS");
            assertBridge(helper, blocks, variantTwo ? Blocks.BIRCH_PLANKS : Blocks.OAK_PLANKS,
                    variantTwo ? Blocks.BIRCH_LOG : Blocks.OAK_LOG);
            sawBirch |= variantTwo;
            sawOak |= !variantTwo;
        }
        helper.assertTrue(sawBirch && sawOak, "plains bell sample did not cover both lumbermill variants");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_road_features")
    public static void wideRiverBridgeHasEvenlySpacedPosts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<int[]> forcedChunks = forceChunks(level, helper.absolutePos(BlockPos.ZERO));
        int channelMinX = 3;
        int channelMaxX = 10;
        try {
            // No cliff: the whole fixture stands at the high surface.
            buildTerrain(helper, Integer.MIN_VALUE, channelMinX, channelMaxX);
            VillageRoadPathGenerator generator = new VillageRoadPathGenerator();
            VillageRoadPathGenerator.PreparedVillageRoads roads = generator.prepareStreetArea(level,
                    BoundingBox.fromCorners(helper.absolutePos(new BlockPos(STREET_MIN_X, HIGH_SURFACE_Y, 0)),
                            helper.absolutePos(new BlockPos(STREET_MAX_X, HIGH_SURFACE_Y, 19))));
            VillageRoadPathGenerator.PlannedPath path = generator.planLumbermillConnection(level,
                    helper.absolutePos(new BlockPos(1, HIGH_SURFACE_Y, ROUTE_Z)),
                    helper.absolutePos(new BlockPos(STREET_MIN_X, HIGH_SURFACE_Y, ROUTE_Z)),
                    List.of(), "SAVANNA", Direction.EAST, roads, null);
            helper.assertTrue(path != null, "connector across an 8-wide river was not planned");
            generator.place(level, path);

            for (int x = channelMinX; x <= channelMaxX; x++) {
                assertBlock(helper, x, HIGH_SURFACE_Y, ROUTE_Z, Blocks.ACACIA_PLANKS, "savanna bridge deck");
                assertBlock(helper, x, HIGH_SURFACE_Y + 1, ROUTE_Z - 2, Blocks.ACACIA_FENCE, "railing");
            }
            // An 8-row span gets end posts plus two interior posts at most 3 rows apart.
            for (int x : new int[] {3, 5, 8, 10}) {
                assertBlock(helper, x, WATER_Y, ROUTE_Z - 2, Blocks.ACACIA_LOG, "bridge post");
                assertBlock(helper, x, WATER_Y, ROUTE_Z + 2, Blocks.ACACIA_LOG, "bridge post");
            }
            assertBlock(helper, 4, WATER_Y, ROUTE_Z - 2, Blocks.WATER, "open water between posts");
            helper.succeed();
        } finally {
            releaseChunks(level, forcedChunks);
        }
    }

    private static void buildTerrain(GameTestHelper helper, int cliffX, int channelMinX, int channelMaxX) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        for (int x = -2; x <= 21; x++) {
            for (int z = -2; z <= 21; z++) {
                boolean channel = x >= channelMinX && x <= channelMaxX && z >= 0 && z <= 19;
                int surfaceY = x < cliffX ? LOW_SURFACE_Y : HIGH_SURFACE_Y;
                for (int y = 0; y <= 8; y++) {
                    BlockState state;
                    if (channel) {
                        state = y < WATER_Y ? stone : y == WATER_Y ? Blocks.WATER.defaultBlockState()
                                : Blocks.AIR.defaultBlockState();
                    } else if (y < surfaceY) {
                        state = stone;
                    } else if (y == surfaceY) {
                        state = x >= STREET_MIN_X && x <= STREET_MAX_X && z >= 0 && z <= 19
                                ? Blocks.DIRT_PATH.defaultBlockState() : grass;
                    } else {
                        state = Blocks.AIR.defaultBlockState();
                    }
                    helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, y, z)), state, 2);
                }
            }
        }
    }

    private static List<int[]> forceChunks(ServerLevel level, BlockPos origin) {
        List<int[]> chunks = new java.util.ArrayList<>();
        for (int chunkX = (origin.getX() - 24) >> 4; chunkX <= (origin.getX() + 44) >> 4; chunkX++) {
            for (int chunkZ = (origin.getZ() - 24) >> 4; chunkZ <= (origin.getZ() + 44) >> 4; chunkZ++) {
                if (level.setChunkForced(chunkX, chunkZ, true)) {
                    chunks.add(new int[] {chunkX, chunkZ});
                }
            }
        }
        return chunks;
    }

    private static void assertStair(GameTestHelper helper, int x, int y, int z) {
        BlockState state = helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(x, y, z)));
        helper.assertTrue(state.is(ECAPBlocks.DIRT_PATH_STAIRS.get()),
                "expected dirt path stairs at " + x + "," + y + "," + z + " but found " + state);
        helper.assertTrue(state.getValue(StairBlock.FACING) == Direction.EAST,
                "ramp stair at " + x + "," + y + "," + z + " does not face uphill: " + state);
    }

    private static void assertBlock(GameTestHelper helper, int x, int y, int z, Block block, String what) {
        BlockState state = helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(x, y, z)));
        helper.assertTrue(state.is(block), what + " at " + x + "," + y + "," + z + " is " + state);
    }

    private static void assertBridge(GameTestHelper helper, VillageBridgeBlocks blocks, Block deck, Block post) {
        helper.assertTrue(blocks.deck().is(deck), "unexpected bridge deck " + blocks.deck());
        helper.assertTrue(blocks.post().is(post), "unexpected bridge post " + blocks.post());
    }
}
