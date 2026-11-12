package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.world.village.StagedPlacement;
import com.orangevillager61.emeraldcapitalism.world.village.VillageBankStructurePlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Building placement spread over several steps must match single-call placement. */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class StagedBuildingPlacementGameTests {

    private StagedBuildingPlacementGameTests() {
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_staged_placement")
    public static void slicedTemplateMatchesSinglePlacement(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Optional<StructureTemplate> found = level.getStructureManager()
                .get(ModIds.id("village/plains/houses/lumbermill_plains_1"));
        helper.assertTrue(found.isPresent(), "lumbermill template is missing");
        StructureTemplate template = found.get();
        List<int[]> forced = forceChunks(level, helper.absolutePos(BlockPos.ZERO), 64);
        try {
            BlockPos whole = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos sliced = helper.absolutePos(new BlockPos(0, 1, 40));
            long seed = 12345L;
            StructurePlaceSettings wholeSettings = settings();
            template.placeInWorld(level, whole, whole, wholeSettings, RandomSource.create(seed), 2);

            StructurePlaceSettings slicedSettings = settings();
            BoundingBox slicedBox = template.getBoundingBox(slicedSettings, sliced);
            StagedPlacement.Builder builder = StagedPlacement.builder("test lumbermill slices");
            int[] firstSliceCalls = new int[1];
            StagedPlacement.addTemplateSlices(builder, level, template, sliced, slicedSettings, seed,
                    slicedBox, () -> firstSliceCalls[0]++);
            StagedPlacement placement = builder.build();
            int steps = 0;
            while (!placement.processStep()) {
                steps++;
            }
            helper.assertTrue(placement.succeeded(), "sliced template placement failed");
            helper.assertTrue(steps >= 1, "a lumbermill spanning several chunks was placed in one step");
            helper.assertTrue(firstSliceCalls[0] == 1, "first-slice callback ran " + firstSliceCalls[0] + " times");

            BoundingBox wholeBox = template.getBoundingBox(wholeSettings, whole);
            int nonAir = 0;
            for (int x = 0; x < wholeBox.getXSpan(); x++) {
                for (int y = 0; y < wholeBox.getYSpan(); y++) {
                    for (int z = 0; z < wholeBox.getZSpan(); z++) {
                        BlockState expected = level.getBlockState(new BlockPos(
                                wholeBox.minX() + x, wholeBox.minY() + y, wholeBox.minZ() + z));
                        BlockState actual = level.getBlockState(new BlockPos(
                                slicedBox.minX() + x, slicedBox.minY() + y, slicedBox.minZ() + z));
                        helper.assertTrue(expected.equals(actual), "sliced block " + x + "," + y + "," + z
                                + " is " + actual + " but single placement made " + expected);
                        if (!actual.isAir()) {
                            nonAir++;
                        }
                    }
                }
            }
            helper.assertTrue(nonAir > 100, "lumbermill template placed almost nothing: " + nonAir);
            helper.succeed();
        } finally {
            releaseChunks(level, forced);
        }
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_staged_placement")
    public static void stagedBankPlacementCommitsAWorkingBank(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Optional<StructureTemplate> top = level.getStructureManager().get(ModIds.id("bank_top"));
        Optional<StructureTemplate> vault = level.getStructureManager().get(ModIds.id("bank_vault"));
        helper.assertTrue(top.isPresent() && vault.isPresent(), "bank templates are missing");
        List<int[]> forced = forceChunks(level, helper.absolutePos(BlockPos.ZERO), 48);
        try {
            int groundY = 14;
            for (int x = -6; x <= 25; x++) {
                for (int z = -6; z <= 25; z++) {
                    for (int y = 0; y <= groundY + 16; y++) {
                        BlockState state = y < groundY ? Blocks.STONE.defaultBlockState()
                                : y == groundY ? Blocks.GRASS_BLOCK.defaultBlockState()
                                : Blocks.AIR.defaultBlockState();
                        level.setBlock(helper.absolutePos(new BlockPos(x, y, z)), state, 2);
                    }
                }
            }
            BlockPos origin = helper.absolutePos(new BlockPos(2, groundY, 2));
            VillageBankStructurePlacer.PlannedBank plan = new VillageBankStructurePlacer.PlannedBank(
                    top.get(), vault.get(), origin, Rotation.NONE,
                    origin.relative(Direction.WEST, 2), origin.relative(Direction.WEST, 10), "PLAINS");
            List<VillageBankStructurePlacer.PlacedBank> committed = new ArrayList<>();
            StagedPlacement placement = new VillageBankStructurePlacer()
                    .startPlacement(level, plan, committed::add);
            int steps = 1;
            while (!placement.processStep()) {
                steps++;
                helper.assertTrue(committed.isEmpty(), "bank committed before its last step");
            }
            helper.assertTrue(placement.succeeded(), "staged bank placement failed");
            helper.assertTrue(steps >= 5, "bank placement was not split into steps: " + steps);
            helper.assertTrue(committed.size() == 1, "bank commit ran " + committed.size() + " times");
            BlockPos bankPos = committed.getFirst().bankPos();
            helper.assertTrue(bankPos.equals(plan.predictedBankPos()),
                    "bank block at " + bankPos + " instead of the planned " + plan.predictedBankPos());
            helper.assertTrue(level.getBlockEntity(bankPos) instanceof BankBlockEntity,
                    "staged placement left no bank block entity at " + bankPos);
            helper.succeed();
        } finally {
            releaseChunks(level, forced);
        }
    }

    private static StructurePlaceSettings settings() {
        // Production slices use a known shape; compare against a single placement
        // with the same setting so only the slicing differs.
        return new StructurePlaceSettings().setRotation(Rotation.NONE).setMirror(Mirror.NONE)
                .setIgnoreEntities(true).setKnownShape(true);
    }

    private static List<int[]> forceChunks(ServerLevel level, BlockPos origin, int reach) {
        List<int[]> chunks = new ArrayList<>();
        for (int chunkX = (origin.getX() - 16) >> 4; chunkX <= (origin.getX() + reach) >> 4; chunkX++) {
            for (int chunkZ = (origin.getZ() - 16) >> 4; chunkZ <= (origin.getZ() + reach) >> 4; chunkZ++) {
                if (level.setChunkForced(chunkX, chunkZ, true)) {
                    chunks.add(new int[] {chunkX, chunkZ});
                }
            }
        }
        return chunks;
    }

    private static void releaseChunks(ServerLevel level, List<int[]> chunks) {
        for (int[] chunk : chunks) {
            level.setChunkForced(chunk[0], chunk[1], false);
        }
    }
}
