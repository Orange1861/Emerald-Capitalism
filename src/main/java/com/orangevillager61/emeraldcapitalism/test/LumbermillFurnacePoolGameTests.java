package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.world.forestry.FurnacePool;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Component tests for how a village fills and maintains its lumbermill furnace pool:
 * the membership rule, the reconciliation, the hand-off when a lumberjack leaves, and
 * the saved lumbermill footprint the rule depends on.
 */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class LumbermillFurnacePoolGameTests {

    private LumbermillFurnacePoolGameTests() {
    }

    private record Site(VillageRecord village, BlockPos inside, BlockPos second) {
    }

    /** Village bounds cover local x and z 0 to 9; the lumbermill footprint covers 4 to 12. */
    private static Site site(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        VillageRegistryData registry = VillageRegistryData.get(level);
        BlockPos min = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos max = helper.absolutePos(new BlockPos(9, 3, 9));
        VillageRecord village = registry.getOrCreateVillage(UUID.randomUUID(),
                helper.absolutePos(new BlockPos(2, 1, 2)),
                new AABB(min.getX(), min.getY(), min.getZ(),
                        max.getX() + 1.0D, max.getY() + 1.0D, max.getZ() + 1.0D));
        BlockPos footMin = helper.absolutePos(new BlockPos(4, 0, 4));
        BlockPos footMax = helper.absolutePos(new BlockPos(12, 3, 12));
        registry.addLumbermillFootprint(new BoundingBox(footMin.getX(), footMin.getY(), footMin.getZ(),
                footMax.getX(), footMax.getY(), footMax.getZ()));
        return new Site(village, helper.absolutePos(new BlockPos(6, 1, 6)),
                helper.absolutePos(new BlockPos(7, 1, 6)));
    }

    @GameTest(template = "empty_20x3x20")
    public static void onlyAFurnaceInsideBothTheVillageAndALumbermillFootprintIsAccepted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site site = site(helper);
        BlockPos inFootprintAndVillage = site.inside();
        BlockPos outsideFootprint = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos outsideVillage = helper.absolutePos(new BlockPos(11, 1, 11));
        BlockPos notAFurnace = site.second();
        level.setBlock(inFootprintAndVillage, Blocks.FURNACE.defaultBlockState(), 3);
        level.setBlock(outsideFootprint, Blocks.FURNACE.defaultBlockState(), 3);
        level.setBlock(outsideVillage, Blocks.FURNACE.defaultBlockState(), 3);
        level.setBlock(notAFurnace, Blocks.BLAST_FURNACE.defaultBlockState(), 3);

        helper.assertTrue(site.village().acceptFurnaceSighting(level, inFootprintAndVillage),
                "a furnace inside the village and a lumbermill footprint was refused");
        helper.assertFalse(site.village().acceptFurnaceSighting(level, outsideFootprint),
                "a furnace outside every lumbermill footprint was accepted");
        helper.assertFalse(site.village().acceptFurnaceSighting(level, outsideVillage),
                "a furnace outside the village bounds was accepted");
        helper.assertFalse(site.village().acceptFurnaceSighting(level, notAFurnace),
                "a blast furnace was accepted as a pool furnace");
        helper.assertValueEqual(site.village().getFurnacePool(level.getGameTime()).furnaces().size(), 1,
                "only the one valid furnace belongs in the pool");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20", timeoutTicks = 260)
    public static void reconciliationFindsLumbermillFurnacesAndDropsOnesThatAreGone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site site = site(helper);
        level.setBlock(site.inside(), Blocks.FURNACE.defaultBlockState(), 3);
        level.setBlock(site.second(), Blocks.FURNACE.defaultBlockState(), 3);

        site.village().reconcileFurnacePool(level);

        FurnacePool pool = site.village().getFurnacePool(level.getGameTime());
        helper.assertTrue(pool.containsFurnace(site.inside().asLong())
                        && pool.containsFurnace(site.second().asLong()),
                "reconciliation did not find both lumbermill furnaces");

        level.setBlock(site.inside(), Blocks.AIR.defaultBlockState(), 3);
        helper.runAfterDelay(101, () -> {
            site.village().reconcileFurnacePool(level);
            helper.assertFalse(pool.containsFurnace(site.inside().asLong()),
                    "a furnace that is gone from a loaded chunk stayed in the pool");
            helper.assertTrue(pool.containsFurnace(site.second().asLong()),
                    "the surviving furnace was dropped from the pool");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_20x3x20")
    public static void aLumberjackThatLeavesHandsItsFurnaceToTheVillage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site site = site(helper);
        level.setBlock(site.inside(), Blocks.FURNACE.defaultBlockState(), 3);
        helper.assertTrue(site.village().acceptFurnaceSighting(level, site.inside()),
                "the furnace was not accepted");
        FurnacePool pool = site.village().getFurnacePool(level.getGameTime());
        UUID lumberjack = UUID.randomUUID();
        long later = level.getGameTime() + FurnacePool.RELOAD_GRACE_TICKS + 1L;
        helper.assertTrue(pool.request(lumberjack, later).isPresent(), "the lumberjack was not given the furnace");

        site.village().releaseLumberjackClaims(lumberjack);

        helper.assertTrue(pool.isOrphaned(site.inside().asLong()),
                "the furnace was not handed to the village");
        helper.assertTrue(pool.claimOf(lumberjack).isEmpty(), "the departed lumberjack still holds a claim");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void aBrokenPoolFurnaceDropsItsClaimAndItsOwnerRejoinsTheQueue(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site site = site(helper);
        level.setBlock(site.inside(), Blocks.FURNACE.defaultBlockState(), 3);
        site.village().acceptFurnaceSighting(level, site.inside());
        FurnacePool pool = site.village().getFurnacePool(level.getGameTime());
        UUID lumberjack = UUID.randomUUID();
        long later = level.getGameTime() + FurnacePool.RELOAD_GRACE_TICKS + 1L;
        pool.request(lumberjack, later);

        site.village().onFurnaceRemoved(level, site.inside());

        helper.assertFalse(pool.containsFurnace(site.inside().asLong()), "the broken furnace stayed in the pool");
        helper.assertTrue(pool.claimOf(lumberjack).isEmpty(), "the broken furnace's claim survived");
        helper.assertTrue(pool.queueOrder().contains(lumberjack), "the owner did not rejoin the queue");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void lumbermillFootprintsSurviveSavingAndReloadingTheRegistry(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        VillageRegistryData registry = new VillageRegistryData();
        BoundingBox footprint = new BoundingBox(10, 60, 10, 30, 70, 24);
        registry.addLumbermillFootprint(footprint);
        registry.addLumbermillFootprint(footprint);

        CompoundTag saved = registry.save(new CompoundTag(), level.registryAccess());
        VillageRegistryData reloaded = VillageRegistryData.load(saved, level.registryAccess());

        helper.assertValueEqual(reloaded.getLumbermillFootprints().size(), 1,
                "the footprint was stored twice or lost");
        helper.assertTrue(reloaded.getLumbermillFootprints().contains(footprint),
                "the footprint changed across a save");
        helper.assertTrue(reloaded.isInLumbermillFootprint(new BlockPos(20, 65, 15)),
                "a position inside the footprint was not recognised after a reload");
        helper.assertFalse(reloaded.isInLumbermillFootprint(new BlockPos(9, 65, 15)),
                "a position outside the footprint was recognised after a reload");
        helper.succeed();
    }
}
