package com.orangevillager61.emeraldcapitalism.test;

import com.mojang.authlib.GameProfile;
import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldChestBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldOreProcessorBlockEntity;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import com.orangevillager61.emeraldcapitalism.entity.ai.BankMorningTradeGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.LumberjackGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.VillagerInventoryBankGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.VaultGolemGoals;
import com.orangevillager61.emeraldcapitalism.event.VillagerSpawnEvents;
import com.orangevillager61.emeraldcapitalism.market.MarketItem;
import com.orangevillager61.emeraldcapitalism.market.MarketRegistry;
import com.orangevillager61.emeraldcapitalism.market.MarketTradeService;
import com.orangevillager61.emeraldcapitalism.market.TradeSide;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.registry.ECAPEntityTypes;
import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.world.bank.BankAccountData;
import com.orangevillager61.emeraldcapitalism.world.bank.BankReputationData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class BankGameplayGameTests {

    private static final UUID VILLAGE_ID = UUID.fromString("abcdefab-cdef-abcd-efab-cdefabcdefab");
    private static final UUID GOLEM_ONE = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID GOLEM_TWO = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID CONSTRUCTION_VILLAGER = UUID.fromString("20000000-0000-0000-0000-000000000001");

    private BankGameplayGameTests() {
    }

    @GameTest(template = "empty_20x3x20")
    public static void villagerBuysBreadToTwicePersonalTargetUsingAccount(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BankBlockEntity bank = setupBreadBank(helper, 45);
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
        BankAccountData.get(level).openAccount(villager.getUUID());

        BankMorningTradeGoal goal = new BankMorningTradeGoal(villager);
        helper.assertTrue(goal.canUse(), "understocked villager did not find its village bank trade");
        goal.start();
        helper.assertTrue(goal.canContinueToUse(), "bread trade goal did not reach its active state");
        goal.tick();
        goal.stop();

        helper.assertValueEqual(countItem(villager, Items.BREAD), 30,
                "villager did not buy bread up to twice its personal target");
        helper.assertValueEqual(bank.getMarketStock(level, Items.BREAD), 15,
                "bank bread stock did not decrease by the villager purchase");
        helper.assertTrue(BankAccountData.get(level).getBalance(villager.getUUID()) < 0,
                "villager bread purchase did not debit its account");
        helper.assertValueEqual(countItem(villager, Items.EMERALD), 0,
                "villager bread purchase created tangible emeralds");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void villagerSellsExcessBreadForAccountCredit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BankBlockEntity bank = setupBreadBank(helper, 15);
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
        villager.getInventory().setItem(0, new ItemStack(Items.BREAD, 38));
        BankAccountData.get(level).openAccount(villager.getUUID());

        BankMorningTradeGoal goal = new BankMorningTradeGoal(villager);
        helper.assertTrue(goal.canUse(), "overstocked villager did not find its village bank trade");
        goal.start();
        helper.assertTrue(goal.canContinueToUse(), "bread sale goal did not reach its active state");
        goal.tick();
        goal.stop();

        helper.assertValueEqual(countItem(villager, Items.BREAD), 30,
                "villager did not sell excess bread down to twice its target");
        helper.assertValueEqual(bank.getMarketStock(level, Items.BREAD), 23,
                "bank bread stock did not increase by the villager sale");
        helper.assertTrue(BankAccountData.get(level).getBalance(villager.getUUID()) > 0,
                "villager bread sale did not credit its account");
        helper.assertValueEqual(countItem(villager, Items.EMERALD), 0,
                "villager bread sale created tangible emeralds");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void farmerSellsExcessWheatForAccountCredit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(8_000L);
        BankBlockEntity bank = setupMarketBank(helper, Items.WHEAT, 0);
        Villager farmer = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        farmer.getInventory().setItem(0, new ItemStack(Items.WHEAT, 64));
        BankAccountData.get(level).openAccount(farmer.getUUID());

        BankMorningTradeGoal goal = new BankMorningTradeGoal(farmer);
        helper.assertTrue(goal.canUse(), "farmer with excess wheat did not select a daytime bank sale");
        goal.start();
        helper.assertTrue(goal.canContinueToUse(), "wheat sale goal did not reach its active state");
        goal.tick();
        goal.stop();

        helper.assertValueEqual(countItem(farmer, Items.WHEAT), 24,
                "farmer did not retain one wheat trade batch after selling its surplus");
        helper.assertValueEqual(bank.getMarketStock(level, Items.WHEAT), 40,
                "bank did not receive the farmer's complete wheat sale batches");
        helper.assertValueEqual(BankAccountData.get(level).getBalance(farmer.getUUID()), 2,
                "wheat sale did not credit one emerald per twenty wheat");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20", timeoutTicks = 300)
    public static void lumberjackGoalSelectorDeliversFromSawmillBeforeStartingMoreWork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(1_000L);
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true, level.getServer());
        setupMarketBank(helper, Items.STICK, 0);
        EmeraldChestBlockEntity chest = (EmeraldChestBlockEntity) level.getBlockEntity(
                helper.absolutePos(new BlockPos(1, 1, 2)));
        if (chest == null) {
            helper.fail("lumberjack scheduler chest was not created");
            return;
        }

        BlockPos sawmillPos = helper.absolutePos(new BlockPos(5, 1, 1));
        helper.setBlock(new BlockPos(5, 1, 1), ECAPBlocks.SAWMILL.get().defaultBlockState());
        BlockPos treeBase = new BlockPos(8, 1, 2);
        helper.setBlock(treeBase.below(), Blocks.DIRT.defaultBlockState());
        helper.setBlock(treeBase, Blocks.OAK_LOG.defaultBlockState());
        helper.setBlock(treeBase.above(), Blocks.OAK_LOG.defaultBlockState());
        helper.setBlock(treeBase.above(2), Blocks.OAK_LOG.defaultBlockState());
        helper.setBlock(treeBase.above(3), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(treeBase.above(2).north(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(treeBase.above(2).south(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(treeBase.above(2).east(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(treeBase.above(2).west(), Blocks.OAK_LEAVES.defaultBlockState());
        Villager lumberjack = helper.spawn(EntityType.VILLAGER, 5, 1, 2);
        lumberjack.setVillagerData(lumberjack.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));
        lumberjack.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                GlobalPos.of(level.dimension(), sawmillPos));

        var deliveryEntry = lumberjack.goalSelector.getAvailableGoals().stream()
                .filter(entry -> entry.getGoal() instanceof VillagerInventoryBankGoal)
                .findFirst().orElse(null);
        var workEntry = lumberjack.goalSelector.getAvailableGoals().stream()
                .filter(entry -> entry.getGoal() instanceof LumberjackGoal)
                .findFirst().orElse(null);
        helper.assertTrue(deliveryEntry != null && workEntry != null,
                "lumberjack did not receive both delivery and profession-work goals");
        if (deliveryEntry != null && workEntry != null) {
            helper.assertValueEqual(deliveryEntry.getPriority(), VillagerInventoryBankGoal.GOAL_PRIORITY,
                    "lumberjack delivery goal was registered at the wrong priority");
            helper.assertTrue(deliveryEntry.getPriority() < workEntry.getPriority(),
                    "lumberjack profession work still outranked pending bank delivery");
        }

        helper.runAfterDelay(5, () -> {
            helper.assertTrue(workEntry != null && workEntry.isRunning(),
                    "lumberjack work was not active before the delivery became pending");
            lumberjack.getInventory().setItem(0, new ItemStack(Items.STICK, 8));
        });

        helper.succeedWhen(() -> {
            helper.assertValueEqual(countItem(lumberjack, Items.STICK), 0,
                    "lumberjack delivery goal did not win after the sawmill work cycle");
            helper.assertValueEqual(countItem(chest, Items.STICK), 8,
                    "lumberjack did not deliver its sticks through the real goal selector");
        });
    }

    @GameTest(template = "empty_20x3x20")
    public static void playerCanDonateMarketItemsForBankOpinion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BankBlockEntity bank = setupMarketBank(helper, Items.EMERALD_ORE, 0);
        ServerPlayer player = new ServerPlayer(
                level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "ecap-donation-test"),
                ClientInformation.createDefault());
        player.getInventory().setItem(0, new ItemStack(Items.EMERALD_ORE));

        MarketItem marketItem = MarketRegistry.get("emerald_ore").orElse(null);
        if (marketItem == null) {
            helper.fail("emerald ore market definition was not loaded");
            return;
        }
        MarketTradeService.Result result = MarketTradeService.execute(
                player, bank, marketItem, 1, TradeSide.SELL, true);

        helper.assertTrue(result.success(), "fixed-price market donation was rejected: " + result.message());
        helper.assertValueEqual(countPlayerItem(player, Items.EMERALD_ORE), 0,
                "donated item remained in the player's inventory");
        helper.assertValueEqual(bank.getMarketStock(level, Items.EMERALD_ORE), 1,
                "donated item was not stored in the bank");
        helper.assertValueEqual(countPlayerItem(player, Items.EMERALD), 0,
                "donation paid tangible emeralds");
        helper.assertValueEqual(BankReputationData.get(level).getReputation(player.getUUID()), 4,
                "donation did not grant one bank-opinion point per emerald payout");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void mapSaleUsesBankOpinionInsteadOfVillageOpinion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BankBlockEntity bank = setupMarketBank(helper, ECAPItems.ABANDONED_VAULT_MAP.get(), 1);
        ServerPlayer player = new ServerPlayer(
                level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "ecap-map-sale-test"),
                ClientInformation.createDefault());
        player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 128));

        MarketItem marketItem = MarketRegistry.get("emeraldcapitalism:abandoned_vault_map").orElse(null);
        if (marketItem == null) {
            helper.fail("abandoned vault map market definition was not loaded");
            return;
        }

        MarketTradeService.Result denied = MarketTradeService.execute(
                player, bank, marketItem, 1, TradeSide.BUY);
        helper.assertTrue(!denied.success() && denied.message().contains("bank opinion"),
                "map sale did not reject low bank opinion: " + denied.message());

        BankReputationData.get(level).adjustReputation(
                player.getUUID(), MarketTradeService.MAP_SALE_BANK_OPINION_THRESHOLD);
        MarketTradeService.Result approved = MarketTradeService.execute(
                player, bank, marketItem, 1, TradeSide.BUY);
        helper.assertTrue(approved.success(),
                "map sale remained blocked despite sufficient bank opinion: " + approved.message());
        helper.assertValueEqual(countPlayerItem(player, ECAPItems.ABANDONED_VAULT_MAP.get()), 1,
                "approved map sale did not give the player the map");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void emeraldProcessorTurnsEmeraldChestIntoEightEmeralds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos processorPos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.setBlock(new BlockPos(1, 1, 1), ECAPBlocks.EMERALD_ORE_PROCESSOR.get().defaultBlockState());

        if (!(level.getBlockEntity(processorPos) instanceof EmeraldOreProcessorBlockEntity processor)) {
            helper.fail("emerald processor block entity was not created");
            return;
        }
        processor.setItem(EmeraldOreProcessorBlockEntity.SLOT_INPUT,
                new ItemStack(ECAPItems.EMERALD_CHEST.get()));
        processor.setItem(EmeraldOreProcessorBlockEntity.SLOT_FUEL, new ItemStack(Items.COAL));

        for (int tick = 0; tick < EmeraldOreProcessorBlockEntity.SMELT_DURATION; tick++) {
            EmeraldOreProcessorBlockEntity.serverTick(
                    level, processorPos, level.getBlockState(processorPos), processor);
        }

        helper.assertValueEqual(processor.getItem(EmeraldOreProcessorBlockEntity.SLOT_OUTPUT).getCount(),
                EmeraldOreProcessorBlockEntity.EMERALDS_PER_CHEST,
                "emerald processor did not convert one emerald chest into eight emeralds");
        helper.assertTrue(processor.getItem(EmeraldOreProcessorBlockEntity.SLOT_INPUT).isEmpty(),
                "emerald chest remained in the processor after conversion");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void bankFeedsSurplusEmeraldChestsToProcessor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos bankPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos storagePos = helper.absolutePos(new BlockPos(1, 1, 2));
        BlockPos processorPos = helper.absolutePos(new BlockPos(1, 1, 0));
        helper.setBlock(new BlockPos(1, 1, 1), ECAPBlocks.BANK.get().defaultBlockState());
        helper.setBlock(new BlockPos(1, 1, 2), ECAPBlocks.EMERALD_CHEST.get().defaultBlockState());
        helper.setBlock(new BlockPos(1, 1, 0), ECAPBlocks.EMERALD_ORE_PROCESSOR.get().defaultBlockState());

        BankBlockEntity bank = bankAt(helper, bankPos);
        EmeraldChestBlockEntity storage = (EmeraldChestBlockEntity) level.getBlockEntity(storagePos);
        BlockEntity blockEntity = level.getBlockEntity(processorPos);
        if (bank == null || storage == null
                || !(blockEntity instanceof EmeraldOreProcessorBlockEntity processor)) {
            helper.fail("bank, storage chest, or emerald processor was not created");
            return;
        }
        storage.setItem(0, new ItemStack(ECAPItems.EMERALD_CHEST.get(), 2));
        processor.setItem(EmeraldOreProcessorBlockEntity.SLOT_FUEL, new ItemStack(Items.COAL, 2));
        BankBlockEntity.serverTick(level, bankPos, level.getBlockState(bankPos), bank);

        helper.assertValueEqual(bank.getSurplusEmeraldChestCount(level), 2,
                "bank did not identify stored emerald chests as surplus");
        ItemStack surplus = bank.withdrawSurplusEmeraldChests(level, processor.getMaxStackSize());
        processor.setItem(EmeraldOreProcessorBlockEntity.SLOT_INPUT, surplus);
        bank.markInventoryChanged(level);
        helper.assertValueEqual(surplus.getCount(), 2,
                "bank did not transfer its surplus emerald chests to the processor");

        for (int tick = 0; tick < EmeraldOreProcessorBlockEntity.SMELT_DURATION * 2; tick++) {
            EmeraldOreProcessorBlockEntity.serverTick(
                    level, processorPos, level.getBlockState(processorPos), processor);
        }

        helper.assertValueEqual(processor.getItem(EmeraldOreProcessorBlockEntity.SLOT_OUTPUT).getCount(), 16,
                "processor did not smelt both surplus emerald chests into emeralds");
        helper.assertTrue(processor.getItem(EmeraldOreProcessorBlockEntity.SLOT_INPUT).isEmpty(),
                "surplus emerald chests remained in the processor");
        helper.assertValueEqual(bank.getSurplusEmeraldChestCount(level), 0,
                "bank still reported smelted emerald chests as surplus");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void bankBuysEmeraldChestsForSixAndSellsThemForNine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BankBlockEntity bank = setupMarketBank(helper, ECAPItems.EMERALD_CHEST.get(), 1);
        EmeraldChestBlockEntity chest = (EmeraldChestBlockEntity) level.getBlockEntity(
                helper.absolutePos(new BlockPos(1, 1, 2)));
        if (chest == null) {
            helper.fail("emerald chest storage block entity was not created");
            return;
        }
        chest.setItem(1, new ItemStack(Items.EMERALD, 9));

        ServerPlayer player = new ServerPlayer(
                level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "ecap-emerald-chest-market-test"),
                ClientInformation.createDefault());
        player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 9));
        MarketItem marketItem = MarketRegistry.get("emeraldcapitalism:emerald_chest").orElse(null);
        if (marketItem == null) {
            helper.fail("emerald chest market definition was not loaded");
            return;
        }

        MarketTradeService.Result sale = MarketTradeService.execute(
                player, bank, marketItem, 1, TradeSide.BUY);
        helper.assertTrue(sale.success(), "bank could not sell an emerald chest for nine emeralds: "
                + sale.message());
        helper.assertValueEqual(countPlayerItem(player, ECAPItems.EMERALD_CHEST.get()), 1,
                "bank sale did not give the player an emerald chest");

        MarketTradeService.Result purchase = MarketTradeService.execute(
                player, bank, marketItem, 1, TradeSide.SELL);
        helper.assertTrue(purchase.success(), "bank could not buy an emerald chest for six emeralds: "
                + purchase.message());
        helper.assertValueEqual(countPlayerItem(player, Items.EMERALD), 6,
                "bank purchase did not pay six emeralds");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void bankRepairsMissingTrackedChestsThroughSmithAndBankerWork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos bankPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos storagePos = helper.absolutePos(new BlockPos(1, 1, 2));
        BlockPos missingPos = helper.absolutePos(new BlockPos(1, 1, 0));
        helper.setBlock(new BlockPos(1, 1, 1), ECAPBlocks.BANK.get().defaultBlockState());
        helper.setBlock(new BlockPos(1, 1, 2), ECAPBlocks.EMERALD_CHEST.get().defaultBlockState());
        helper.setBlock(new BlockPos(1, 1, 0), ECAPBlocks.EMERALD_CHEST.get().defaultBlockState());

        BankBlockEntity bank = bankAt(helper, bankPos);
        EmeraldChestBlockEntity storage = (EmeraldChestBlockEntity) level.getBlockEntity(storagePos);
        if (bank == null || storage == null) {
            helper.fail("bank or storage emerald chest block entity was not created");
            return;
        }
        storage.setItem(0, new ItemStack(Items.CHEST, 2));
        storage.setItem(1, new ItemStack(Items.EMERALD, 16));
        BankBlockEntity.serverTick(level, bankPos, level.getBlockState(bankPos), bank);
        level.setBlock(missingPos, Blocks.AIR.defaultBlockState(), 3);
        BankBlockEntity.markChestCachesDirtyNear(level, missingPos);
        BankBlockEntity.serverTick(level, bankPos, level.getBlockState(bankPos), bank);

        helper.assertValueEqual(bank.getTrackedChestPositions().size(), 2,
                "bank did not retain the missing chest location");
        helper.assertValueEqual(bank.getMissingChestCount(level), 1,
                "bank did not identify the missing tracked chest");
        helper.assertValueEqual(bank.craftMissingEmeraldChests(level), 1,
                "emeraldsmith repair path did not craft one missing emerald chest");
        helper.assertValueEqual(bank.getPreparedEmeraldChestCount(), 1,
                "crafted repair chest was not held for the banker");
        helper.assertTrue(bank.replaceMissingEmeraldChest(level),
                "banker repair path did not replace the missing chest");
        helper.assertValueEqual(bank.getMissingChestCount(level), 0,
                "replaced chest remained marked missing");
        helper.assertValueEqual(bank.getPreparedEmeraldChestCount(), 0,
                "banker did not consume the prepared repair chest");
        helper.assertTrue(level.getBlockState(missingPos).is(ECAPBlocks.EMERALD_CHEST.get()),
                "banker did not place an emerald chest at the tracked location");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void villagerBreadSaleRefillsBankBelowOneVillageDay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(8_000L);
        BankBlockEntity bank = setupBreadBank(helper, 2);
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
        villager.getInventory().setItem(0, new ItemStack(Items.BREAD, 38));
        BankAccountData.get(level).openAccount(villager.getUUID());

        BankMorningTradeGoal goal = new BankMorningTradeGoal(villager);
        helper.assertTrue(goal.canUse(), "bank below its reserve rejected an incoming bread sale");
        goal.start();
        helper.assertTrue(goal.canContinueToUse(), "bread refill goal did not reach its active state");
        goal.tick();
        goal.stop();

        helper.assertValueEqual(countItem(villager, Items.BREAD), 30,
                "villager did not sell excess bread to the low-stock bank");
        helper.assertValueEqual(bank.getMarketStock(level, Items.BREAD), 10,
                "low-stock bank did not accept the villager's bread sale");
        helper.assertTrue(BankAccountData.get(level).getBalance(villager.getUUID()) > 0,
                "bread sale to the low-stock bank did not credit the villager account");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void villagerSellsAllPumpkinsOnceInMorning(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BankBlockEntity bank = setupMarketBank(helper, Items.PUMPKIN, 0);
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
        villager.getInventory().setItem(0, new ItemStack(Items.PUMPKIN, 2));
        BankAccountData.get(level).openAccount(villager.getUUID());

        BankMorningTradeGoal goal = new BankMorningTradeGoal(villager);
        helper.assertTrue(goal.canUse(), "villager did not select the morning pumpkin sale task");
        goal.start();
        helper.assertTrue(goal.canContinueToUse(), "morning sale goal did not reach its active state");
        goal.tick();
        goal.stop();

        helper.assertValueEqual(countItem(villager, Items.PUMPKIN), 0,
                "morning sale did not sell all pumpkins held by the villager");
        helper.assertValueEqual(bank.getMarketStock(level, Items.PUMPKIN), 2,
                "morning pumpkin sale did not add all pumpkins to the bank");
        helper.assertTrue(BankAccountData.get(level).getBalance(villager.getUUID()) > 0,
                "morning pumpkin sale did not credit the villager account");
        helper.assertTrue(!goal.canUse(), "morning sale task ran more than once during the same morning");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void morningSaleCheckDoesNotRepeatAfterEmptyCheck(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        setupMarketBank(helper, Items.PUMPKIN, 0);
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
        BankAccountData.get(level).openAccount(villager.getUUID());

        BankMorningTradeGoal goal = new BankMorningTradeGoal(villager);
        helper.assertTrue(!goal.canUse(), "empty morning sale check unexpectedly started a sale");
        villager.getInventory().setItem(0, new ItemStack(Items.PUMPKIN));
        helper.assertTrue(!goal.canUse(), "morning sale check repeated after finding no items");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void generatedVillagerInitialEmeraldsGoStraightToBank(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BankBlockEntity bank = setupMarketBank(helper, Items.BREAD, 0);
        EmeraldChestBlockEntity chest = (EmeraldChestBlockEntity) level.getBlockEntity(
                helper.absolutePos(new BlockPos(1, 1, 2)));
        if (chest == null) {
            helper.fail("initial-deposit bank chest was not created");
            return;
        }

        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
        VillagerSpawnEvents.addStructureSpawnSupplies(villager);
        int initialEmeralds = VillagerSpawnEvents.getPendingInitialEmeralds(villager);
        bank.queueDepositIfEligible(villager);
        bank.depositInitialEmeralds(level, villager, initialEmeralds);
        VillagerSpawnEvents.clearPendingInitialEmeralds(villager);

        helper.assertValueEqual(countItem(villager, Items.EMERALD), 0,
                "generated villager kept its initial emeralds");
        helper.assertTrue(initialEmeralds >= 32 && initialEmeralds <= 96,
                "generated villager did not receive the expected initial emerald range");
        helper.assertValueEqual(BankAccountData.get(level).getBalance(villager.getUUID()), initialEmeralds,
                "generated villager initial emeralds were not credited to its account");
        helper.assertValueEqual(countItem(chest, Items.EMERALD), initialEmeralds,
                "bank did not receive generated villager initial emeralds");
        helper.assertTrue(!bank.isQueued(villager.getUUID()),
                "completed initial transfer left the villager in the regular deposit queue");

        villager.getInventory().addItem(new ItemStack(Items.EMERALD, 20));
        bank.queueDepositIfEligible(villager);
        helper.assertTrue(bank.isQueued(villager.getUUID()),
                "regular deposits were disabled after the initial settlement");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void fullVillagerInventorySellsPricedItemsAndDonatesUnpricedItems(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        setupMarketBank(helper, Items.EMERALD_ORE, 0);
        EmeraldChestBlockEntity chest = (EmeraldChestBlockEntity) level.getBlockEntity(
                helper.absolutePos(new BlockPos(1, 1, 2)));
        if (chest == null) {
            helper.fail("bank cleanup chest was not created");
            return;
        }
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
        villager.getInventory().setItem(0, new ItemStack(Items.EMERALD_ORE, 2));
        villager.getInventory().setItem(1, new ItemStack(Items.STRING, 4));
        villager.getInventory().setItem(2, new ItemStack(Items.BONE, 3));
        for (int slot = 3; slot < villager.getInventory().getContainerSize(); slot++) {
            villager.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
        }

        VillagerInventoryBankGoal goal = new VillagerInventoryBankGoal(villager);
        helper.assertTrue(goal.canUse(), "full villager inventory did not select its bank cleanup task");
        goal.start();
        goal.tick();
        goal.stop();

        helper.assertValueEqual(countItem(villager, Items.EMERALD_ORE), 0,
                "priced emerald ore remained in the full villager inventory");
        helper.assertValueEqual(countItem(villager, Items.STRING), 0,
                "unpriced string remained in the full villager inventory");
        helper.assertValueEqual(countItem(villager, Items.BONE), 0,
                "unpriced bone remained in the full villager inventory");
        helper.assertValueEqual(BankAccountData.get(level).getBalance(villager.getUUID()), 8,
                "the emerald ore sale did not credit its fixed bank price");
        helper.assertValueEqual(countItem(chest, Items.EMERALD_ORE), 2,
                "the bank did not store the priced emerald ore");
        helper.assertValueEqual(countItem(chest, Items.STRING), 4,
                "the bank did not accept unpriced string as a donation");
        helper.assertValueEqual(countItem(chest, Items.BONE), 3,
                "the bank did not accept unpriced bone as a donation");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void fullInventoryVillagerUsesOnlyTheBankFront(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        setupMarketBank(helper, Items.EMERALD_ORE, 0);
        EmeraldChestBlockEntity chest = (EmeraldChestBlockEntity) level.getBlockEntity(
                helper.absolutePos(new BlockPos(1, 1, 2)));
        if (chest == null) {
            helper.fail("bank access-side chest was not created");
            return;
        }

        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 0, 1, 1);
        villager.getInventory().setItem(0, new ItemStack(Items.STRING, 4));
        for (int slot = 1; slot < villager.getInventory().getContainerSize(); slot++) {
            villager.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
        }

        VillagerInventoryBankGoal goal = new VillagerInventoryBankGoal(villager);
        helper.assertTrue(goal.canUse(), "back-side villager did not select its bank cleanup task");
        goal.start();
        goal.tick();
        helper.assertValueEqual(countItem(villager, Items.STRING), 4,
                "villager transferred inventory from the banker-only back side");

        BlockPos front = helper.absolutePos(new BlockPos(2, 1, 1));
        villager.moveTo(front.getX() + 0.5D, front.getY(), front.getZ() + 0.5D,
                0.0F, 0.0F);
        goal.tick();
        goal.stop();

        helper.assertValueEqual(countItem(villager, Items.STRING), 0,
                "villager did not transfer inventory from the bank front");
        helper.assertValueEqual(countItem(chest, Items.STRING), 4,
                "front-side bank access did not store the villager's items");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void lumberjackDonatesSticksToBank(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        setupMarketBank(helper, Items.EMERALD_ORE, 0);
        EmeraldChestBlockEntity chest = (EmeraldChestBlockEntity) level.getBlockEntity(
                helper.absolutePos(new BlockPos(1, 1, 2)));
        if (chest == null) {
            helper.fail("lumberjack donation chest was not created");
            return;
        }

        Villager lumberjack = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
        lumberjack.setVillagerData(lumberjack.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));
        lumberjack.getInventory().setItem(0, new ItemStack(Items.STICK, 8));
        for (int slot = 1; slot < lumberjack.getInventory().getContainerSize(); slot++) {
            lumberjack.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
        }

        VillagerInventoryBankGoal goal = new VillagerInventoryBankGoal(lumberjack);
        helper.assertTrue(lumberjack.wantsToPickUp(new ItemStack(Items.STICK)),
                "lumberjack pickup policy rejected sticks");
        helper.assertTrue(goal.canUse(), "lumberjack with sticks did not select bank cleanup");
        goal.start();
        goal.tick();
        goal.stop();

        helper.assertValueEqual(countItem(lumberjack, Items.STICK), 0,
                "lumberjack kept sticks instead of donating them");
        helper.assertValueEqual(countItem(chest, Items.STICK), 8,
                "bank did not store donated lumberjack sticks");
        helper.assertValueEqual(BankAccountData.get(level).getBalance(lumberjack.getUUID()), 0,
                "stick donation incorrectly credited the lumberjack account");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void fullVillagerInventoryPreservesFoodAndProfessionInputs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        setupMarketBank(helper, Items.EMERALD_ORE, 0);
        EmeraldChestBlockEntity chest = (EmeraldChestBlockEntity) level.getBlockEntity(
                helper.absolutePos(new BlockPos(1, 1, 2)));
        if (chest == null) {
            helper.fail("bank cleanup chest was not created");
            return;
        }
        Villager farmer = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        farmer.getInventory().setItem(0, new ItemStack(Items.BREAD, 12));
        farmer.getInventory().setItem(1, new ItemStack(Items.WHEAT_SEEDS, 16));
        farmer.getInventory().setItem(2, new ItemStack(Items.BONE_MEAL, 8));
        farmer.getInventory().setItem(3, new ItemStack(Items.STRING, 4));
        for (int slot = 4; slot < farmer.getInventory().getContainerSize(); slot++) {
            farmer.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
        }

        VillagerInventoryBankGoal goal = new VillagerInventoryBankGoal(farmer);
        helper.assertTrue(goal.canUse(), "farmer with a full inventory did not select bank cleanup");
        goal.start();
        goal.tick();
        goal.stop();

        helper.assertValueEqual(countItem(farmer, Items.BREAD), 12,
                "full-inventory cleanup consumed the farmer's food reserve");
        helper.assertValueEqual(countItem(farmer, Items.WHEAT_SEEDS), 16,
                "full-inventory cleanup consumed the farmer's seeds");
        helper.assertValueEqual(countItem(farmer, Items.BONE_MEAL), 8,
                "full-inventory cleanup consumed the farmer's bonemeal");
        helper.assertValueEqual(countItem(farmer, Items.STRING), 0,
                "full-inventory cleanup did not remove the farmer's loose string");
        helper.assertValueEqual(countItem(chest, Items.STRING), 4,
                "farmer's unreserved string was not donated to the bank");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void linkedChestMutationsUpdateBankTotalsImmediately(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos bankPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos chestPos = helper.absolutePos(new BlockPos(1, 1, 2));
        helper.setBlock(new BlockPos(1, 1, 1), ECAPBlocks.BANK.get().defaultBlockState());
        helper.setBlock(new BlockPos(1, 1, 2), ECAPBlocks.EMERALD_CHEST.get().defaultBlockState());

        BankBlockEntity bank = bankAt(helper, bankPos);
        EmeraldChestBlockEntity chest = (EmeraldChestBlockEntity) level.getBlockEntity(chestPos);
        if (bank == null || chest == null) {
            helper.fail("bank or emerald chest block entity was not created");
            return;
        }

        BankBlockEntity.serverTick(level, bankPos, level.getBlockState(bankPos), bank);
        helper.assertTrue(!bank.hasUnverifiedChestCache(),
                "bank chest cache remained unverified after its initial scan");
        chest.setItem(0, new ItemStack(Items.EMERALD_BLOCK, 2));
        chest.setItem(1, new ItemStack(Items.PUMPKIN, 3));

        helper.assertValueEqual(bank.getTotalEmeraldCount(), 18,
                "linked chest mutation did not update bank emerald totals immediately");
        helper.assertValueEqual(bank.getTotalPumpkinCount(), 3,
                "linked chest mutation did not update bank pumpkin totals immediately");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void durableBankStateSurvivesReloadAndCachesRebuild(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos bankPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos chestPos = helper.absolutePos(new BlockPos(1, 1, 2));
        BlockPos composterPos = helper.absolutePos(new BlockPos(1, 1, 0));
        BlockPos constructionPos = helper.absolutePos(new BlockPos(-1, 1, -1));
        BlockState bankState = ECAPBlocks.BANK.get().defaultBlockState();

        helper.setBlock(new BlockPos(1, 1, 1), bankState);
        helper.setBlock(new BlockPos(1, 1, 2), ECAPBlocks.EMERALD_CHEST.get().defaultBlockState());
        helper.setBlock(new BlockPos(1, 1, 0), Blocks.COMPOSTER.defaultBlockState());

        BankBlockEntity original = bankAt(helper, bankPos);
        EmeraldChestBlockEntity chest = (EmeraldChestBlockEntity) level.getBlockEntity(chestPos);
        if (original == null || chest == null) {
            return;
        }

        chest.setItem(0, new ItemStack(Items.EMERALD_BLOCK, 16));
        chest.setItem(1, new ItemStack(Items.PUMPKIN));

        VillageRegistryData registry = VillageRegistryData.get(level);
        registry.getOrCreateVillage(VILLAGE_ID, helper.absolutePos(new BlockPos(0, 1, 0)), new AABB(
                bankPos.getX() - 8, bankPos.getY() - 4, bankPos.getZ() - 8,
                bankPos.getX() + 8, bankPos.getY() + 4, bankPos.getZ() + 8));
        original.setVillageId(VILLAGE_ID);
        registry.registerBankPosition(VILLAGE_ID, bankPos);
        original.setBankName("Vault North");
        original.setGolemConstructionPos(constructionPos);
        original.registerEmeraldGolemEmployee(GOLEM_ONE);
        original.registerEmeraldGolemEmployee(GOLEM_ONE);
        original.registerEmeraldGolemEmployee(GOLEM_TWO);

        Villager employee = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
        // The join-level event may have already registered this villager before
        // the explicit assertion path runs; duplicate registration is a no-op.
        original.registerSpawnedEmployee(employee);
        if (!original.isEmployee(employee.getUUID())
                || !original.registerEmployeeFromJob(level, employee, bankPos)
                || !original.registerEmployeeFromJob(level, employee, composterPos)) {
            helper.fail("bank employee/job/composter assignment could not be established");
            return;
        }
        BankAccountData.get(level).openAccount(employee.getUUID());
        BankAccountData.get(level).deposit(employee.getUUID(), 7);
        original.enqueue(employee.getUUID());

        BankBlockEntity.serverTick(level, bankPos, bankState, original);
        if (!original.beginGolemConstruction(CONSTRUCTION_VILLAGER)) {
            helper.fail("test bank could not establish a transient construction reservation");
            return;
        }
        helper.assertValueEqual(original.getTotalEmeraldCount(), 144,
                "initial full scan did not count the chest's emerald reserve");

        CompoundTag saved = original.saveWithId(level.registryAccess());
        helper.assertTrue(saved.contains("village_id") && saved.contains("employee_ids"),
                "durable bank identity and employee assignments were not encoded");
        helper.assertTrue(saved.contains("composter_pos") && saved.contains("golem_construction_pos"),
                "durable bank positions were not encoded");
        helper.assertTrue(!saved.contains("cached_chests") && !saved.contains("total_emerald_count")
                        && !saved.contains("deposit_queue")
                        && !saved.contains("active_golem_construction_villager"),
                "derived caches or transient work state were persisted");

        BlockEntity loadedEntity = BlockEntity.loadStatic(bankPos, bankState, saved, level.registryAccess());
        if (!(loadedEntity instanceof BankBlockEntity restored)) {
            helper.fail("real block-entity save/reload did not recreate the bank");
            return;
        }
        restored.setLevel(level);
        restored.onLoad();

        helper.assertTrue(VILLAGE_ID.equals(restored.getVillageId()),
                "village identity did not survive bank save/reload");
        helper.assertTrue("Vault North".equals(restored.getBankName()),
                "bank name did not survive bank save/reload");
        helper.assertTrue(restored.getEmployeeIds().contains(employee.getUUID())
                        && restored.getEmployeeCount() == 1,
                "employee assignment did not survive bank save/reload");
        helper.assertTrue(restored.getEmeraldGolemEmployeeIds().equals(java.util.List.of(GOLEM_ONE, GOLEM_TWO)),
                "golem assignments did not preserve insertion order and duplicate behavior");
        helper.assertTrue(composterPos.equals(restored.getComposterPos())
                        && constructionPos.equals(restored.getGolemConstructionPos()),
                "durable bank positions did not survive bank save/reload");
        helper.assertTrue(restored.getCachedChestPositions().isEmpty()
                        && restored.getTotalEmeraldCount() == 0
                        && restored.getClosestEmeraldProcessorPos() == null,
                "derived caches were not reset before the next normal scan");
        helper.assertTrue(restored.hasUnverifiedChestCache(),
                "reloaded bank was allowed to trust totals before its first chest scan");
        helper.assertTrue(!restored.isQueued(employee.getUUID())
                        && restored.getActiveGolemConstructionVillager() == null,
                "transient queue or construction reservation survived reload");
        helper.assertTrue(bankPos.equals(registry.getBankPos(VILLAGE_ID)),
                "reloaded bank did not restore the village registry lookup");
        helper.assertTrue(BankAccountData.get(level).hasAccount(employee.getUUID())
                        && BankAccountData.get(level).getBalance(employee.getUUID()) == 7,
                "bank-linked account interaction changed during block-entity reload");

        BankBlockEntity.serverTick(level, bankPos, bankState, restored);
        helper.assertValueEqual(restored.getChestCount(), 1,
                "next normal bank scan did not rebuild the chest-position cache");
        helper.assertValueEqual(restored.getTotalEmeraldCount(), 144,
                "next normal bank scan did not rebuild the resource-count cache");
        helper.assertValueEqual(restored.getTotalPumpkinCount(), 1,
                "next normal bank scan did not rebuild the pumpkin-count cache");
        helper.assertTrue(restored.beginGolemConstruction(CONSTRUCTION_VILLAGER),
                "a fresh construction reservation could not be queued after reload");
        restored.enqueue(employee.getUUID());
        helper.assertTrue(restored.isQueued(employee.getUUID()),
                "post-reload deposit queue could not accept a new server-side task");
        helper.succeed();
    }

    private static BankBlockEntity setupBreadBank(GameTestHelper helper, int bread) {
        return setupMarketBank(helper, Items.BREAD, bread);
    }

    private static BankBlockEntity setupMarketBank(GameTestHelper helper, Item item, int amount) {
        ServerLevel level = helper.getLevel();
        BlockPos bankPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos chestPos = helper.absolutePos(new BlockPos(1, 1, 2));
        helper.setBlock(new BlockPos(1, 1, 1), ECAPBlocks.BANK.get().defaultBlockState()
                .setValue(BankBlock.FACING, Direction.EAST));
        helper.setBlock(new BlockPos(1, 1, 2), ECAPBlocks.EMERALD_CHEST.get().defaultBlockState());

        BankBlockEntity bank = bankAt(helper, bankPos);
        EmeraldChestBlockEntity chest = (EmeraldChestBlockEntity) level.getBlockEntity(chestPos);
        if (bank == null || chest == null) {
            throw new IllegalStateException("bread trade bank fixture was not created");
        }

        UUID villageId = UUID.randomUUID();
        VillageRegistryData registry = VillageRegistryData.get(level);
        registry.getOrCreateVillage(villageId, bankPos, new AABB(
                bankPos.getX() - 4, bankPos.getY() - 2, bankPos.getZ() - 4,
                bankPos.getX() + 4, bankPos.getY() + 2, bankPos.getZ() + 4));
        registry.registerBankPosition(villageId, bankPos);
        bank.setVillageId(villageId);
        chest.setItem(0, new ItemStack(item, amount));
        BankBlockEntity.serverTick(level, bankPos, level.getBlockState(bankPos), bank);
        return bank;
    }

    private static int countItem(Villager villager, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countItem(EmeraldChestBlockEntity chest, Item item) {
        int total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countPlayerItem(ServerPlayer player, Item item) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @GameTest(template = "empty_20x3x20")
    public static void bankTakeoverRequiresVacantEmployeesAndDeadVaultGolems(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos bankPos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.setBlock(new BlockPos(1, 1, 1), ECAPBlocks.BANK.get().defaultBlockState());
        BankBlockEntity bank = bankAt(helper, bankPos);

        Villager employeeOne = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 0, 1, 1);
        Villager employeeTwo = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 0);
        Villager employeeThree = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
        bank.registerSpawnedEmployee(employeeOne);
        bank.registerSpawnedEmployee(employeeTwo);
        bank.registerSpawnedEmployee(employeeThree);
        helper.assertValueEqual(bank.getEmployeeCount(), 3,
                "bank did not track all three takeover-blocking employees");
        helper.assertTrue(!bank.meetsTakeoverRequirements(level),
                "bank could be claimed while employee jobs were occupied");

        bank.getEmployeeIds().forEach(bank::removeEmployee);

        EmeraldGolem emeraldGolem = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        IronGolem ironGolem = EntityType.IRON_GOLEM.create(level);
        if (emeraldGolem == null || ironGolem == null) {
            helper.fail("could not create both vault golem types");
            return;
        }
        emeraldGolem.setBankEmployeePos(bankPos);
        emeraldGolem.moveTo(bankPos.getX() + 2.5D, bankPos.getY(), bankPos.getZ() + 0.5D,
                0.0F, 0.0F);
        ironGolem.setCustomName(Component.literal("Vault Golem"));
        VaultGolemGoals.markAsVaultGuard(ironGolem);
        ironGolem.moveTo(bankPos.getX() + 3.5D, bankPos.getY(), bankPos.getZ() + 0.5D,
                0.0F, 0.0F);
        level.addFreshEntity(emeraldGolem);
        level.addFreshEntity(ironGolem);

        helper.assertTrue(!bank.meetsTakeoverRequirements(level),
                "bank could be claimed while vault golems were alive");
        emeraldGolem.discard();
        ironGolem.discard();
        helper.assertTrue(bank.meetsTakeoverRequirements(level),
                "bank could not be claimed after all employees and vault golems were gone");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void resourceScanIncludesNearestEmeraldProcessorInventory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos bankPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos processorPos = helper.absolutePos(new BlockPos(1, 1, 2));
        helper.setBlock(new BlockPos(1, 1, 1), ECAPBlocks.BANK.get().defaultBlockState());
        helper.setBlock(new BlockPos(1, 1, 2), ECAPBlocks.EMERALD_ORE_PROCESSOR.get().defaultBlockState());

        BankBlockEntity bank = bankAt(helper, bankPos);
        BlockEntity blockEntity = level.getBlockEntity(processorPos);
        if (bank == null || !(blockEntity instanceof EmeraldOreProcessorBlockEntity processor)) {
            helper.fail("bank or emerald processor block entity was not created");
            return;
        }

        processor.setItem(EmeraldOreProcessorBlockEntity.SLOT_INPUT, new ItemStack(Items.EMERALD, 4));
        processor.setItem(EmeraldOreProcessorBlockEntity.SLOT_FUEL, new ItemStack(Items.COAL, 3));
        processor.setItem(EmeraldOreProcessorBlockEntity.SLOT_OUTPUT, new ItemStack(Items.EMERALD_BLOCK, 2));
        BankBlockEntity.serverTick(level, bankPos, level.getBlockState(bankPos), bank);

        helper.assertValueEqual(bank.getTotalEmeraldCount(), 22,
                "resource scan did not include emerald input and block output from the processor");
        helper.assertValueEqual(bank.getTotalEmeraldBlockCount(), 2,
                "resource scan did not include processor emerald blocks");
        helper.assertValueEqual(bank.getTotalCoalCount(), 3,
                "resource scan did not include processor fuel");

        processor.setItem(EmeraldOreProcessorBlockEntity.SLOT_INPUT, new ItemStack(Items.EMERALD_ORE, 2));
        processor.setItem(EmeraldOreProcessorBlockEntity.SLOT_OUTPUT,
                new ItemStack(ECAPItems.EMERALD_GREEN_DYE.get(), 5));
        BankBlockEntity.serverTick(level, bankPos, level.getBlockState(bankPos), bank);

        helper.assertValueEqual(bank.getTotalEmeraldOreCount(), 2,
                "resource scan did not include processor emerald ore input");
        helper.assertValueEqual(bank.getTotalEmeraldGreenDyeCount(), 5,
                "resource scan did not include processor output dye");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void lastEmployeeKillerGetsExclusiveBankTakeoverForThirtyMinutes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos bankPos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.setBlock(new BlockPos(1, 1, 1), ECAPBlocks.BANK.get().defaultBlockState());
        BankBlockEntity bank = bankAt(helper, bankPos);
        Villager employee = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 2);
        bank.getEmployeeIds().forEach(bank::removeEmployee);
        bank.registerSpawnedEmployee(employee);

        UUID killer = UUID.fromString("30000000-0000-0000-0000-000000000001");
        UUID otherPlayer = UUID.fromString("30000000-0000-0000-0000-000000000002");
        long now = level.getGameTime();
        helper.assertTrue(bank.recordLastEmployeeDirectKiller(employee.getUUID(), killer, now),
                "bank did not record the direct killer of its last employee");
        bank.removeEmployee(employee.getUUID());

        helper.assertTrue(bank.canPlayerTakeControl(level, killer),
                "last employee's killer could not claim the vacant bank");
        helper.assertTrue(!bank.canPlayerTakeControl(level, otherPlayer),
                "another player could claim the bank during the killer-exclusive window");

        bank.registerSpawnedEmployee(employee);
        bank.recordLastEmployeeDirectKiller(employee.getUUID(), killer,
                now - BankBlockEntity.DIRECT_KILL_TAKEOVER_LOCK_TICKS - 1L);
        bank.removeEmployee(employee.getUUID());
        helper.assertTrue(bank.canPlayerTakeControl(level, otherPlayer),
                "expired killer-exclusive bank takeover window did not reopen control");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void durableBankMutationsMarkChunkChanged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos bankPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockState state = ECAPBlocks.BANK.get().defaultBlockState();
        helper.setBlock(new BlockPos(1, 1, 1), state);
        BankBlockEntity bank = bankAt(helper, bankPos);
        if (bank == null) {
            return;
        }

        UUID villageId = UUID.fromString("30000000-0000-0000-0000-000000000001");
        level.getChunkAt(bankPos).setUnsaved(false);
        bank.setVillageId(villageId);
        helper.assertTrue(level.getChunkAt(bankPos).isUnsaved(), "village link did not call setChanged");

        level.getChunkAt(bankPos).setUnsaved(false);
        bank.setVillageId(villageId);
        helper.assertTrue(level.getChunkAt(bankPos).isUnsaved(),
                "documented no-op village-link mutation changed its dirty behavior");

        level.getChunkAt(bankPos).setUnsaved(false);
        bank.setBankName("Bank");
        helper.assertTrue(level.getChunkAt(bankPos).isUnsaved(), "bank naming did not call setChanged");

        level.getChunkAt(bankPos).setUnsaved(false);
        bank.setGolemConstructionPos(new BlockPos(-5, -64, 7));
        helper.assertTrue(level.getChunkAt(bankPos).isUnsaved(),
                "construction-position mutation did not call setChanged");

        level.getChunkAt(bankPos).setUnsaved(false);
        helper.assertTrue(bank.registerEmeraldGolemEmployee(GOLEM_ONE),
                "golem employee registration unexpectedly failed");
        helper.assertTrue(level.getChunkAt(bankPos).isUnsaved(),
                "golem employee registration did not call setChanged");

        level.getChunkAt(bankPos).setUnsaved(false);
        helper.assertTrue(!bank.registerEmeraldGolemEmployee(GOLEM_ONE),
                "duplicate golem employee registration unexpectedly succeeded");
        helper.assertTrue(!level.getChunkAt(bankPos).isUnsaved(),
                "duplicate golem employee registration changed its documented dirty behavior");

        level.getChunkAt(bankPos).setUnsaved(false);
        helper.assertTrue(bank.removeEmeraldGolemEmployee(GOLEM_ONE),
                "golem employee removal unexpectedly failed");
        helper.assertTrue(level.getChunkAt(bankPos).isUnsaved(),
                "golem employee removal did not call setChanged");
        helper.succeed();
    }

    private static BankBlockEntity bankAt(GameTestHelper helper, BlockPos pos) {
        BlockEntity blockEntity = helper.getLevel().getBlockEntity(pos);
        if (blockEntity instanceof BankBlockEntity bank) {
            return bank;
        }
        helper.fail("bank block entity was not created");
        return null;
    }
}
