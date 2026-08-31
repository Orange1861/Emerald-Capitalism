package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldChestBlockEntity;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldSkrimisher;
import com.orangevillager61.emeraldcapitalism.entity.ai.EmeraldSmithGolemConstructionGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.EmeraldSkrimisherBankDepositGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.EmeraldSkrimisherCombatGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.EmeraldSkrimisherPickupGoal;
import com.orangevillager61.emeraldcapitalism.event.EmeraldGolemEvents;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.registry.ECAPEntityTypes;
import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Focused server-side tests for Emerald Skrimisher stats, storage, and pickup. */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class EmeraldSkrimisherGameTests {

    private EmeraldSkrimisherGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void emeraldSkrimisherHasConfiguredStatsAndPickupGoal(GameTestHelper helper) {
        EmeraldSkrimisher skrimisher = create(helper);
        if (skrimisher == null) {
            return;
        }

        helper.assertValueEqual(skrimisher.getMaxHealth(), 16.0F,
                "Emerald Skrimisher must have 16 maximum health");
        helper.assertValueEqual(skrimisher.getAttributeValue(Attributes.ATTACK_DAMAGE), 1.0D,
                "Emerald Skrimisher must have 1 attack damage");
        helper.assertValueEqual(skrimisher.getInventory().getContainerSize(), EmeraldSkrimisher.INVENTORY_SIZE,
                "Emerald Skrimisher must have nine inventory slots");
        helper.assertTrue(skrimisher.wantsToPickUp(new ItemStack(Items.EMERALD)),
                "Emerald Skrimisher must accept emeralds");
        helper.assertTrue(skrimisher.wantsToPickUp(new ItemStack(Items.CHARCOAL)),
                "Emerald Skrimisher must accept charcoal");
        helper.assertTrue(skrimisher.wantsToPickUp(new ItemStack(Items.OAK_DOOR)),
                "Emerald Skrimisher must accept doors");
        helper.assertTrue(skrimisher.wantsToPickUp(new ItemStack(Items.GOLDEN_APPLE)),
                "Emerald Skrimisher must accept golden apples");
        helper.assertTrue(skrimisher.wantsToPickUp(new ItemStack(Items.APPLE)),
                "Emerald Skrimisher must accept regular apples");
        helper.assertTrue(skrimisher.wantsToPickUp(new ItemStack(Items.IRON_AXE)),
                "Emerald Skrimisher must accept axes");
        helper.assertFalse(skrimisher.wantsToPickUp(new ItemStack(Items.DIAMOND)),
                "Emerald Skrimisher must reject unrelated items");
        helper.assertTrue(skrimisher.goalSelector.getAvailableGoals().stream()
                        .anyMatch(goal -> goal.getGoal() instanceof EmeraldSkrimisherBankDepositGoal),
                "Emerald Skrimisher must register its morning bank deposit task");
        helper.assertTrue(skrimisher.goalSelector.getAvailableGoals().stream()
                        .anyMatch(goal -> goal.getGoal() instanceof EmeraldSkrimisherPickupGoal),
                "Emerald Skrimisher must register its item pickup task");
        helper.assertTrue(skrimisher.goalSelector.getAvailableGoals().stream()
                        .anyMatch(goal -> goal.getGoal() instanceof EmeraldSkrimisherCombatGoal),
                "Emerald Skrimisher must replace the iron golem melee task");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void emeraldSkrimisherUsesGolemAssistedJumpAttack(GameTestHelper helper) {
        EmeraldSkrimisher skrimisher = create(helper);
        Pig target = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.PIG, 3, 1, 1);
        IronGolem nearbyGolem = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.IRON_GOLEM, 3, 1, 3);
        if (skrimisher == null) {
            return;
        }
        skrimisher.moveTo(2.0D, 1.0D, 1.0D, 0.0F, 0.0F);
        helper.assertTrue(skrimisher.hasNearbyGolem(target),
                "Skrimisher did not detect the nearby iron golem around its target");

        float healthBefore = target.getHealth();
        helper.assertTrue(skrimisher.performCombatAttack(target, true),
                "Skrimisher jump attack did not damage its target");
        helper.assertValueEqual(target.getHealth(), healthBefore - 1.0F,
                "Skrimisher jump attack must deal exactly one HP");
        helper.assertTrue(target.hasEffect(MobEffects.MOVEMENT_SLOWDOWN),
                "Skrimisher jump attack did not apply slowness");
        var slowness = target.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
        helper.assertValueEqual(slowness.getAmplifier(), 3,
                "Skrimisher jump attack must apply Slowness IV");
        helper.assertValueEqual(slowness.getDuration(), 120,
                "Skrimisher jump attack must apply six seconds of slowness");
        helper.assertTrue(nearbyGolem.isAlive(), "test golem unexpectedly died");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void emeraldSkrimisherKnocksNonGolemAttackAwayFromVillager(GameTestHelper helper) {
        EmeraldSkrimisher skrimisher = create(helper);
        Pig target = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.PIG, 3, 1, 1);
        Villager villager = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.VILLAGER, 4, 1, 1);
        if (skrimisher == null) {
            return;
        }
        skrimisher.moveTo(2.0D, 1.0D, 1.0D, 0.0F, 0.0F);

        float healthBefore = target.getHealth();
        helper.assertTrue(skrimisher.performCombatAttack(target, false),
                "Skrimisher non-golem attack did not damage its target");
        helper.assertValueEqual(target.getHealth(), healthBefore - 1.0F,
                "Skrimisher non-golem attack must use its one-damage attribute");
        helper.assertFalse(target.hasEffect(MobEffects.MOVEMENT_SLOWDOWN),
                "Skrimisher non-golem attack must not apply jump-attack slowness");
        helper.assertTrue(target.getDeltaMovement().x < 0.0D,
                "Skrimisher must avoid knocking its target toward a nearby villager");
        helper.assertTrue(villager.isAlive(), "test villager unexpectedly died");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void emeraldSkrimisherDepositsEntireInventoryAtBankEachMorning(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos bankPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos chestPos = helper.absolutePos(new BlockPos(1, 1, 2));
        helper.setBlock(new BlockPos(1, 1, 1), ECAPBlocks.BANK.get().defaultBlockState());
        helper.setBlock(new BlockPos(1, 1, 2), ECAPBlocks.EMERALD_CHEST.get().defaultBlockState());

        BankBlockEntity bank = (BankBlockEntity) level.getBlockEntity(bankPos);
        EmeraldChestBlockEntity chest = (EmeraldChestBlockEntity) level.getBlockEntity(chestPos);
        if (bank == null || chest == null) {
            helper.fail("bank or emerald chest block entity was not created");
            return;
        }

        UUID villageId = UUID.randomUUID();
        var registry = com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData.get(level);
        registry.getOrCreateVillage(villageId, bankPos, new AABB(
                bankPos.getX() - 4, bankPos.getY() - 2, bankPos.getZ() - 4,
                bankPos.getX() + 4, bankPos.getY() + 2, bankPos.getZ() + 4));
        registry.registerBankPosition(villageId, bankPos);
        bank.setVillageId(villageId);
        BankBlockEntity.serverTick(level, bankPos, level.getBlockState(bankPos), bank);

        EmeraldSkrimisher skrimisher = create(helper);
        if (skrimisher == null) {
            return;
        }
        skrimisher.moveTo(bankPos.getX() + 0.5D, bankPos.getY() + 0.5D, bankPos.getZ() - 1.5D,
                0.0F, 0.0F);
        skrimisher.getInventory().setItem(0, new ItemStack(Items.EMERALD, 4));
        skrimisher.getInventory().setItem(1, new ItemStack(Items.IRON_NUGGET, 7));
        skrimisher.getInventory().setItem(2, new ItemStack(Items.GOLDEN_APPLE));
        level.addFreshEntity(skrimisher);
        level.setDayTime(1_000L);

        EmeraldSkrimisherBankDepositGoal goal = new EmeraldSkrimisherBankDepositGoal(skrimisher);
        helper.assertTrue(goal.canUse(), "Skrimisher did not select the morning bank deposit task");
        goal.start();
        goal.tick();
        goal.stop();

        helper.assertValueEqual(countItem(skrimisher, Items.EMERALD), 0,
                "morning bank deposit left emeralds in the Skrimisher inventory");
        helper.assertValueEqual(countItem(skrimisher, Items.IRON_NUGGET), 0,
                "morning bank deposit left iron nuggets in the Skrimisher inventory");
        helper.assertValueEqual(countItem(skrimisher, Items.GOLDEN_APPLE), 0,
                "morning bank deposit left a golden apple in the Skrimisher inventory");
        helper.assertValueEqual(countItem(chest, Items.EMERALD), 4,
                "bank did not receive the Skrimisher's emeralds");
        helper.assertValueEqual(countItem(chest, Items.IRON_NUGGET), 7,
                "bank did not receive the Skrimisher's iron nuggets");
        helper.assertValueEqual(countItem(chest, Items.GOLDEN_APPLE), 1,
                "bank did not receive the Skrimisher's golden apple");
        helper.assertTrue(!goal.canUse(), "Skrimisher repeated its bank deposit during the same morning");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void villagersAndSkrimishersUseTheSameHoldablePool(GameTestHelper helper) {
        EmeraldSkrimisher skrimisher = create(helper);
        Villager villager = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.VILLAGER, 1, 1, 1);
        if (skrimisher == null) {
            return;
        }

        ItemStack[] sharedItems = {
                new ItemStack(Items.EMERALD),
                new ItemStack(Items.ROTTEN_FLESH),
                new ItemStack(Items.BREAD),
                new ItemStack(Items.SPIDER_EYE),
                new ItemStack(Items.EMERALD_BLOCK),
                new ItemStack(Items.STRING),
                new ItemStack(Items.WHITE_WOOL),
                new ItemStack(Items.CHEST),
                new ItemStack(Items.TRAPPED_CHEST),
                new ItemStack(Items.ENDER_CHEST),
                new ItemStack(Items.EMERALD_ORE),
                new ItemStack(Items.DEEPSLATE_EMERALD_ORE),
                new ItemStack(Items.BONE),
                new ItemStack(Items.ENDER_PEARL),
                new ItemStack(Items.IRON_INGOT),
                new ItemStack(Items.IRON_BLOCK),
                new ItemStack(Items.IRON_NUGGET),
                new ItemStack(Items.COAL),
                new ItemStack(Items.CHARCOAL),
                new ItemStack(Items.OAK_DOOR),
                new ItemStack(Items.GOLDEN_APPLE),
                new ItemStack(Items.APPLE),
                new ItemStack(Items.IRON_AXE),
                new ItemStack(Items.ROTTEN_FLESH),
                new ItemStack(ECAPItems.COMPACTED_ROTTEN_FLESH.get()),
                new ItemStack(ECAPItems.ROTTEN_FLESH_COVER.get())
        };
        for (ItemStack stack : sharedItems) {
            helper.assertTrue(villager.wantsToPickUp(stack),
                    "villager rejected a shared holdable item: " + stack.getHoverName().getString());
            helper.assertTrue(skrimisher.wantsToPickUp(stack),
                    "Skrimisher rejected a shared holdable item: " + stack.getHoverName().getString());
        }
        for (Item item : BuiltInRegistries.ITEM) {
            if (!com.orangevillager61.emeraldcapitalism.EmeraldCapitalism.MODID
                    .equals(BuiltInRegistries.ITEM.getKey(item).getNamespace())) {
                continue;
            }
            ItemStack stack = new ItemStack(item);
            helper.assertTrue(villager.wantsToPickUp(stack),
                    "villager rejected an Emerald Capitalism item: " + BuiltInRegistries.ITEM.getKey(item));
            helper.assertTrue(skrimisher.wantsToPickUp(stack),
                    "Skrimisher rejected an Emerald Capitalism item: " + BuiltInRegistries.ITEM.getKey(item));
        }
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        helper.assertTrue(villager.wantsToPickUp(new ItemStack(Items.BONE_MEAL)),
                "farmers should be able to hold bonemeal for profession work");
        villager.setVillagerData(villager.getVillagerData().setProfession(
                com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions.MAYOR.get()));
        helper.assertTrue(villager.wantsToPickUp(new ItemStack(Items.OAK_DOOR)),
                "mayors should be able to hold doors for repair work");
        helper.assertTrue(villager.wantsToPickUp(new ItemStack(Items.WHITE_BED)),
                "mayors should be able to hold beds for village work");
        for (VillagerProfession profession : BuiltInRegistries.VILLAGER_PROFESSION) {
            villager.setVillagerData(villager.getVillagerData().setProfession(profession));
            helper.assertTrue(villager.wantsToPickUp(new ItemStack(Items.IRON_INGOT)),
                    "villager profession rejected an iron ingot: " + profession.name());
        }
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void emeraldSkrimisherPickupTaskStoresWantedItems(GameTestHelper helper) {
        EmeraldSkrimisher skrimisher = create(helper);
        if (skrimisher == null) {
            return;
        }

        BlockPos position = helper.absolutePos(new BlockPos(1, 1, 1));
        skrimisher.moveTo(position, 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(skrimisher);
        ItemEntity item = new ItemEntity(helper.getLevel(), skrimisher.getX(), skrimisher.getY(), skrimisher.getZ(),
                new ItemStack(Items.IRON_INGOT, 4));
        item.setNoPickUpDelay();
        helper.getLevel().addFreshEntity(item);

        EmeraldSkrimisherPickupGoal goal = new EmeraldSkrimisherPickupGoal(skrimisher);
        helper.assertTrue(goal.canUse(), "pickup task did not find a nearby iron ingot");
        goal.start();
        goal.tick();
        goal.stop();

        helper.assertValueEqual(countItem(skrimisher, Items.IRON_INGOT), 4,
                "pickup task did not store the wanted item");
        helper.assertFalse(item.isAlive(), "pickup task left the collected item entity alive");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void emeraldSkrimisherInventorySurvivesSaveReload(GameTestHelper helper) {
        EmeraldSkrimisher source = create(helper);
        if (source == null) {
            return;
        }
        source.getInventory().addItem(new ItemStack(Items.EMERALD_BLOCK, 2));
        source.getInventory().addItem(new ItemStack(Items.ROTTEN_FLESH, 7));

        CompoundTag saved = source.saveWithoutId(new CompoundTag());
        EmeraldSkrimisher restored = create(helper);
        if (restored == null) {
            return;
        }
        restored.load(saved);

        helper.assertValueEqual(countItem(restored, Items.EMERALD_BLOCK), 2,
                "emerald blocks did not survive inventory save/reload");
        helper.assertValueEqual(countItem(restored, Items.ROTTEN_FLESH), 7,
                "rotten flesh did not survive inventory save/reload");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void emeraldSkrimisherDropsChestEmeraldsAndInventory(GameTestHelper helper) {
        var level = helper.getLevel();
        EmeraldSkrimisher skrimisher = create(helper);
        if (skrimisher == null) {
            return;
        }

        skrimisher.moveTo(1.5D, 1.0D, 1.5D, 0.0F, 0.0F);
        skrimisher.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        skrimisher.getInventory().setItem(1, new ItemStack(Items.ROTTEN_FLESH, 2));
        if (!level.addFreshEntity(skrimisher)) {
            helper.fail("Could not add the Emerald Skrimisher for the drop test");
            return;
        }
        skrimisher.hurt(level.damageSources().generic(), 100.0F);

        List<ItemEntity> drops = level.getEntitiesOfClass(
                ItemEntity.class, skrimisher.getBoundingBox().inflate(2.0D));
        helper.assertValueEqual(countDroppedItem(drops, ECAPItems.EMERALD_CHEST.get()), 1,
                "Emerald Skrimisher must drop one Emerald Chest");
        int emeraldCount = countDroppedItem(drops, Items.EMERALD);
        helper.assertTrue(emeraldCount >= 1 && emeraldCount <= 2,
                "Emerald Skrimisher must drop between one and two emeralds, got " + emeraldCount);
        helper.assertValueEqual(countDroppedItem(drops, Items.IRON_INGOT), 3,
                "Emerald Skrimisher must drop its iron inventory contents");
        helper.assertValueEqual(countDroppedItem(drops, Items.ROTTEN_FLESH), 2,
                "Emerald Skrimisher must drop its rotten flesh inventory contents");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void emeraldChestAndEmeraldBlockSummonSkrimisher(GameTestHelper helper) {
        BlockPos relativeBase = new BlockPos(1, 1, 1);
        BlockPos base = helper.absolutePos(relativeBase);
        BlockPos chest = base.above();
        BlockPos pumpkin = chest.above();
        helper.setBlock(relativeBase, Blocks.EMERALD_BLOCK.defaultBlockState());
        helper.setBlock(relativeBase.above(), ECAPBlocks.EMERALD_CHEST.get().defaultBlockState());
        helper.setBlock(relativeBase.above(2), Blocks.CARVED_PUMPKIN.defaultBlockState());

        helper.assertTrue(EmeraldGolemEvents.trySpawnEmeraldSkrimisher(
                        helper.getLevel(), pumpkin, null),
                "emerald block, emerald chest, and pumpkin did not summon a Skrimisher");
        helper.assertTrue(helper.getLevel().getBlockState(base).isAir(),
                "emerald block was not consumed by Skrimisher construction");
        helper.assertTrue(helper.getLevel().getBlockState(chest).isAir(),
                "emerald chest was not consumed by Skrimisher construction");
        helper.assertTrue(helper.getLevel().getBlockState(pumpkin).isAir(),
                "pumpkin was not consumed by Skrimisher construction");
        helper.assertTrue(helper.getLevel().getEntitiesOfClass(
                        EmeraldSkrimisher.class, new net.minecraft.world.phys.AABB(base).inflate(1.0D)).size() == 1,
                "Skrimisher was not spawned at the construction site");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void emeraldSmithCraftsChestDuringSkrimisherConstruction(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos bankPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos storagePos = helper.absolutePos(new BlockPos(1, 1, 2));
        BlockPos processorPos = helper.absolutePos(new BlockPos(1, 1, 0));
        BlockPos constructionPos = helper.absolutePos(new BlockPos(5, 1, 1));
        helper.setBlock(new BlockPos(1, 1, 1), ECAPBlocks.BANK.get().defaultBlockState());
        helper.setBlock(new BlockPos(1, 1, 2), ECAPBlocks.EMERALD_CHEST.get().defaultBlockState());
        helper.setBlock(new BlockPos(1, 1, 0), ECAPBlocks.EMERALD_ORE_PROCESSOR.get().defaultBlockState());

        BankBlockEntity bank = (BankBlockEntity) level.getBlockEntity(bankPos);
        EmeraldChestBlockEntity storage = (EmeraldChestBlockEntity) level.getBlockEntity(storagePos);
        if (bank == null || storage == null) {
            helper.fail("bank or emerald chest block entity was not created");
            return;
        }

        UUID villageId = UUID.randomUUID();
        VillageRegistryData registry = VillageRegistryData.get(level);
        registry.getOrCreateVillage(villageId, bankPos, new AABB(
                bankPos.getX() - 8, bankPos.getY() - 2, bankPos.getZ() - 8,
                bankPos.getX() + 8, bankPos.getY() + 2, bankPos.getZ() + 8));
        registry.registerBankPosition(villageId, bankPos);
        bank.setVillageId(villageId);
        bank.setGolemConstructionPos(constructionPos);
        storage.setItem(0, new ItemStack(Items.EMERALD, 17));
        storage.setItem(1, new ItemStack(Items.OAK_LOG, 2));
        storage.setItem(2, new ItemStack(Items.PUMPKIN));
        BankBlockEntity.serverTick(level, bankPos, level.getBlockState(bankPos), bank);
        helper.assertValueEqual(bank.getTotalPlankCount(), BankBlockEntity.PLANKS_PER_VANILLA_CHEST,
                "bank did not expose two oak logs as eight plank-equivalents");

        EmeraldGolem golem = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        if (golem == null) {
            helper.fail("could not create the completed emerald golem fixture");
            return;
        }
        golem.moveTo(bankPos.getX() + 2.5D, bankPos.getY(), bankPos.getZ() + 0.5D,
                0.0F, 0.0F);
        level.addFreshEntity(golem);
        bank.registerEmeraldGolemEmployee(golem.getUUID());

        Villager smith = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.VILLAGER,
                1, 1, 0);
        smith.setVillagerData(smith.getVillagerData().setProfession(
                ECAPVillagerProfessions.EMERALDSMITH.get()));
        EmeraldSmithGolemConstructionGoal goal = new EmeraldSmithGolemConstructionGoal(smith);
        helper.assertValueEqual(bank.getEmeraldSkrimisherLimit(level), 2,
                "bank formula did not allow two Skrimishers for one live emerald golem");
        helper.assertTrue(goal.canUse(),
                "emerald smith did not select the post-golem Skrimisher construction task");
        goal.start();
        goal.tick();
        goal.tick();

        helper.assertValueEqual(bank.getMarketStock(level, Items.CHEST), 0,
                "wood-based Skrimisher construction unexpectedly left a vanilla chest in storage");
        helper.assertValueEqual(bank.getTotalPlankCount(), 0,
                "emerald smith did not craft the vanilla chest from wood in the bank");
        helper.assertValueEqual(bank.getLiveEmeraldValue(level), 0,
                "emerald smith did not consume the emerald chest recipe inputs");
        helper.assertTrue(level.getBlockState(constructionPos).is(Blocks.EMERALD_BLOCK),
                "emerald smith did not place the Skrimisher's emerald base");
        goal.stop();
        helper.succeed();
    }

    private static EmeraldSkrimisher create(GameTestHelper helper) {
        EmeraldSkrimisher skrimisher = ECAPEntityTypes.EMERALD_SKRIMISHER.get().create(helper.getLevel());
        if (skrimisher == null) {
            helper.fail("Could not create the Emerald Skrimisher");
        }
        return skrimisher;
    }

    private static int countItem(EmeraldSkrimisher skrimisher, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < skrimisher.getInventory().getContainerSize(); slot++) {
            ItemStack stack = skrimisher.getInventory().getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int countItem(EmeraldChestBlockEntity chest, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int countDroppedItem(List<ItemEntity> drops, net.minecraft.world.item.Item item) {
        return drops.stream()
                .filter(drop -> drop.getItem().is(item))
                .mapToInt(drop -> drop.getItem().getCount())
                .sum();
    }
}
