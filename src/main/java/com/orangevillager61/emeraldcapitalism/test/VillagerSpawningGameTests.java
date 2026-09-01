package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.event.VillagerSpawnEvents;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;
import java.util.UUID;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class VillagerSpawningGameTests {

    private VillagerSpawningGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void structureSpawnReceivesSixToNineDaysOfBread(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, 1, 1, 1);

        VillagerSpawnEvents.addStructureSpawnSupplies(villager);

        int breadCount = 0;
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.is(Items.BREAD)) {
                breadCount += stack.getCount();
            }
        }
        helper.assertTrue(breadCount >= 18 && breadCount <= 27 && breadCount % 3 == 0,
                "structure-spawned villager did not receive between six and nine days of bread");
        helper.succeed();
    }

    /**
     * Exercises the exact SpawnEggItem return hook with a parent that is not in
     * the level. EntityJoinLevelEvent therefore cannot infer the caller, so a
     * passing test proves that the mixin still carries the authoritative parent
     * reference through the offspring path.
     */
    @GameTest(template = "empty_3x3x3")
    public static void villagerOffspringReceivesNamesAndExactParentOnce(GameTestHelper helper) {
        // Keep this fixture away from neighboring GameTest structures: the
        // join fallback searches 32 blocks for orphan babies.
        BlockPos localOrigin = new BlockPos(64, 1, 1);
        installTestVillage(helper, localOrigin);

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Villager parent = com.orangevillager61.emeraldcapitalism.util.EntityCreation.create(
                EntityType.VILLAGER, helper.getLevel());
        if (parent == null) {
            helper.fail("Could not create the villager spawn-egg parent");
            return;
        }
        parent.moveTo(helper.absolutePos(localOrigin), 0.0F, 0.0F);

        SpawnEggItem egg = (SpawnEggItem) Items.VILLAGER_SPAWN_EGG;
        ItemStack stack = new ItemStack(egg);
        Optional<Mob> spawned = egg.spawnOffspringFromSpawnEgg(
                player,
                parent,
                EntityType.VILLAGER,
                helper.getLevel(),
                parent.position(),
                stack
        );

        helper.assertTrue(spawned.isPresent(), "villager spawn egg did not create an offspring");
        helper.assertTrue(spawned.get() instanceof Villager, "villager spawn egg created a non-villager child");
        Villager child = (Villager) spawned.get();
        VillagerStatsAttachment parentStats = parent.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        VillagerStatsAttachment childStats = child.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);

        helper.assertTrue(parentStats.hasAssignedFirstName(),
                "the exact spawn-egg parent did not receive a server-assigned name");
        helper.assertTrue(childStats.hasAssignedFirstName(),
                "the spawn-egg child did not receive a server-assigned name");
        helper.assertValueEqual(childStats.getParent1UUID(), parent.getUUID(),
                "spawn-egg child did not retain the exact parent UUID");
        helper.assertValueEqual(childStats.getParent1Name(), parentStats.getVillagerName(),
                "spawn-egg child did not cache the exact parent name");
        helper.assertValueEqual(parentStats.getChildCount(), 1,
                "spawn-egg parent received duplicate child metadata");
        helper.assertTrue(parentStats.getChildrenUUIDs().contains(child.getUUID()),
                "spawn-egg parent did not receive the child UUID");
        helper.assertTrue(stack.isEmpty(), "successful spawn-egg offspring did not consume one egg");

        // The assignment helper is intentionally idempotent. A later join or
        // retry must not add another child record or overwrite the exact parent.
        String parentName = childStats.getParent1Name();
        VillagerSpawnEvents.assignParentsFromSpawnEgg(child, parent);
        helper.assertValueEqual(parentStats.getChildCount(), 1,
                "repeated spawn-egg parent assignment duplicated the child");
        helper.assertValueEqual(childStats.getParent1UUID(), parent.getUUID(),
                "repeated spawn-egg parent assignment changed the parent UUID");
        helper.assertValueEqual(childStats.getParent1Name(), parentName,
                "repeated spawn-egg parent assignment changed the parent name");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void failedAndNonVillagerSpawnEggPathsDoNothing(GameTestHelper helper) {
        installTestVillage(helper, new BlockPos(64, 1, 1));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);

        Villager failedParent = com.orangevillager61.emeraldcapitalism.util.EntityCreation.create(
                EntityType.VILLAGER, helper.getLevel());
        if (failedParent == null) {
            helper.fail("Could not create the failed-spawn parent");
            return;
        }
        failedParent.moveTo(helper.absolutePos(new BlockPos(64, 1, 1)), 0.0F, 0.0F);
        VillagerStatsAttachment failedParentStats =
                failedParent.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        SpawnEggItem cowEgg = (SpawnEggItem) Items.COW_SPAWN_EGG;
        ItemStack mismatchedStack = new ItemStack(cowEgg);
        Optional<Mob> failed = cowEgg.spawnOffspringFromSpawnEgg(
                player,
                failedParent,
                EntityType.VILLAGER,
                helper.getLevel(),
                failedParent.position(),
                mismatchedStack
        );
        helper.assertTrue(failed.isEmpty(), "a mismatched spawn egg unexpectedly created a child");
        helper.assertTrue(failedParentStats.getChildrenUUIDs().isEmpty(),
                "failed spawn armed villager parent metadata");
        helper.assertValueEqual(mismatchedStack.getCount(), 1,
                "failed spawn consumed a spawn egg");

        Mob nonVillagerParent = com.orangevillager61.emeraldcapitalism.util.EntityCreation.create(
                EntityType.ZOMBIE, helper.getLevel());
        if (nonVillagerParent == null) {
            helper.fail("Could not create the non-villager spawn-egg parent");
            return;
        }
        nonVillagerParent.moveTo(helper.absolutePos(new BlockPos(84, 1, 1)), 0.0F, 0.0F);
        SpawnEggItem villagerEgg = (SpawnEggItem) Items.VILLAGER_SPAWN_EGG;
        ItemStack villagerChildStack = new ItemStack(Items.VILLAGER_SPAWN_EGG);
        Optional<Mob> villagerChildFromNonVillagerParent = villagerEgg.spawnOffspringFromSpawnEgg(
                player,
                nonVillagerParent,
                EntityType.VILLAGER,
                helper.getLevel(),
                nonVillagerParent.position(),
                villagerChildStack
        );
        helper.assertTrue(villagerChildFromNonVillagerParent.isPresent(),
                "villager spawn egg did not create a child for the non-villager parent path");
        helper.assertTrue(villagerChildFromNonVillagerParent.get() instanceof Villager,
                "non-villager parent path created the wrong child type");
        VillagerStatsAttachment villagerChildStats =
                villagerChildFromNonVillagerParent.get().getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        helper.assertTrue(villagerChildStats.getParent1UUID() == null,
                "non-villager parent path assigned villager parent metadata");
        helper.assertValueEqual(villagerChildStack.getCount(), 0,
                "successful non-villager parent path did not consume one egg");

        Cow nonVillagerChildParent = com.orangevillager61.emeraldcapitalism.util.EntityCreation.create(
                EntityType.COW, helper.getLevel());
        if (nonVillagerChildParent == null) {
            helper.fail("Could not create the non-villager child parent");
            return;
        }
        nonVillagerChildParent.moveTo(helper.absolutePos(new BlockPos(104, 1, 1)), 0.0F, 0.0F);
        ItemStack nonVillagerStack = new ItemStack(cowEgg);
        Optional<Mob> nonVillagerSpawned = cowEgg.spawnOffspringFromSpawnEgg(
                player,
                nonVillagerChildParent,
                EntityType.COW,
                helper.getLevel(),
                nonVillagerChildParent.position(),
                nonVillagerStack
        );
        helper.assertTrue(nonVillagerSpawned.isPresent(),
                "matching non-villager spawn egg did not create its vanilla child");
        helper.assertTrue(nonVillagerSpawned.get() instanceof Cow,
                "matching cow spawn egg created the wrong child type");
        helper.assertTrue(((Cow) nonVillagerSpawned.get()).isBaby(),
                "matching cow spawn egg did not create a baby");
        helper.assertValueEqual(nonVillagerStack.getCount(), 0,
                "successful non-villager child path did not consume one egg");
        helper.succeed();
    }

    private static void installTestVillage(GameTestHelper helper, BlockPos localOrigin) {
        BlockPos center = helper.absolutePos(localOrigin);
        VillageRegistryData.get(helper.getLevel()).getOrCreateVillage(
                UUID.randomUUID(),
                center,
                new net.minecraft.world.phys.AABB(
                        center.getX() - 16.0,
                        center.getY() - 16.0,
                        center.getZ() - 16.0,
                        center.getX() + 16.0,
                        center.getY() + 16.0,
                        center.getZ() + 16.0
                )
        );
    }
}
