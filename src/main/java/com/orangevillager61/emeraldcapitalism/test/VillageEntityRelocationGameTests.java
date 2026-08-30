package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.world.village.VillageEntityRelocation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.List;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class VillageEntityRelocationGameTests {

    private VillageEntityRelocationGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void buildingOccupantsMoveToClearSupportedPositions(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = -2; x <= 7; x++) {
            for (int z = -2; z <= 7; z++) {
                level.setBlock(helper.absolutePos(new BlockPos(x, 0, z)),
                        Blocks.STONE.defaultBlockState(), 3);
            }
        }

        BlockPos buildingMin = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos buildingMax = helper.absolutePos(new BlockPos(3, 3, 3));
        BoundingBox building = new BoundingBox(
                buildingMin.getX(), buildingMin.getY(), buildingMin.getZ(),
                buildingMax.getX(), buildingMax.getY(), buildingMax.getZ());
        Villager first = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 2);
        Villager second = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 3);

        VillageEntityRelocation.relocateFromBuilding(level, List.of(building));

        assertRelocated(helper, level, first, building);
        assertRelocated(helper, level, second, building);
        helper.succeed();
    }

    private static void assertRelocated(GameTestHelper helper, ServerLevel level, Entity entity,
                                        BoundingBox building) {
        helper.assertTrue(entity.isAlive(), "building occupant was unexpectedly removed");
        helper.assertTrue(!intersects(entity.getBoundingBox(), building),
                "building occupant remained inside the placed building volume");
        helper.assertTrue(level.noCollision(entity, entity.getBoundingBox()),
                "building occupant was moved into a colliding position");
        helper.assertTrue(level.getBlockState(entity.blockPosition().below())
                        .isFaceSturdy(level, entity.blockPosition().below(), net.minecraft.core.Direction.UP),
                "building occupant was not moved onto a supported floor");
    }

    private static boolean intersects(AABB entityBox, BoundingBox building) {
        return entityBox.maxX > building.minX() && entityBox.minX < building.maxX() + 1.0D
                && entityBox.maxY > building.minY() && entityBox.minY < building.maxY() + 1.0D
                && entityBox.maxZ > building.minZ() && entityBox.minZ < building.maxZ() + 1.0D;
    }
}
