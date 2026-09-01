package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldChestBlockEntity;
import com.orangevillager61.emeraldcapitalism.menu.VillagerStatsMenu;
import com.orangevillager61.emeraldcapitalism.util.EmeraldConsolidationUtils;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.world.bank.BankAccountData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class VillagerTradeAccountingGameTests {

    private VillagerTradeAccountingGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void playerBuysCostBAndEmeraldBlocksUpdateBalanceAndInventory(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        MerchantOffer offer = new MerchantOffer(
                new ItemCost(Items.EMERALD, 2),
                Optional.of(new ItemCost(Items.EMERALD_BLOCK)),
                new ItemStack(Items.BREAD),
                10,
                0,
                1.0F
        );

        completeTrade(villager, player, offer, offer.getCostA(), offer.getCostB());

        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        helper.assertValueEqual(stats.getEmeraldBalance(), 11,
                "cost A and cost B were not counted at their exact emerald value");
        helper.assertValueEqual(emeraldValue(villager.getInventory()), 11,
                "physical villager emerald inventory did not match the trade balance");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void playerSellsEmeraldBlocksCanCreateDebt(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        villager.getInventory().setItem(0, new ItemStack(Items.EMERALD_BLOCK));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        MerchantOffer offer = new MerchantOffer(
                new ItemCost(Items.WHEAT),
                Optional.of(new ItemCost(Items.BREAD)),
                new ItemStack(Items.EMERALD_BLOCK),
                10,
                0,
                1.0F
        );

        completeTrade(villager, player, offer, offer.getCostA(), offer.getCostB());

        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        helper.assertValueEqual(stats.getEmeraldBalance(), -9,
                "an emerald-block result did not create the exact negative balance");
        helper.assertValueEqual(emeraldValue(villager.getInventory()), 0,
                "the physical emerald block was not removed for a nine-emerald result");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void emeraldBlocksCountTowardVillagerBankEligibility(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        villager.getInventory().setItem(0, new ItemStack(Items.EMERALD_BLOCK, 2));

        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        stats.refreshInventoryCounts(villager.getInventory());

        helper.assertValueEqual(stats.getCachedEmeraldCount(), 18,
                "emerald blocks were not counted at nine emeralds each for bank eligibility");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void bankDepositsEmeraldBlocksAsTheirFullValue(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos bankRelativePos = new BlockPos(1, 1, 1);
        BlockPos chestRelativePos = new BlockPos(1, 1, 2);
        BlockPos bankPos = helper.absolutePos(bankRelativePos);
        BlockPos chestPos = helper.absolutePos(chestRelativePos);
        helper.setBlock(bankRelativePos, ECAPBlocks.BANK.get().defaultBlockState());
        helper.setBlock(chestRelativePos, ECAPBlocks.EMERALD_CHEST.get().defaultBlockState());

        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        villager.getInventory().setItem(0, new ItemStack(Items.EMERALD_BLOCK, 2));
        BankAccountData.get(level).openAccount(villager.getUUID());

        BankBlockEntity bank = (BankBlockEntity) level.getBlockEntity(bankPos);
        if (bank == null) {
            helper.fail("bank block entity was not created");
            return;
        }
        BankBlockEntity.serverTick(level, bankPos, level.getBlockState(bankPos), bank);
        bank.handleDepositorArrival(level, villager);

        helper.assertValueEqual(emeraldValue(villager.getInventory()), 0,
                "emerald blocks remained in the villager after deposit");
        helper.assertValueEqual(BankAccountData.get(level).getBalance(villager.getUUID()), 18,
                "emerald blocks were not deposited at nine emeralds each");
        Player menuPlayer = helper.makeMockPlayer(GameType.SURVIVAL);
        VillagerStatsMenu statsMenu = new VillagerStatsMenu(0, menuPlayer.getInventory(), villager);
        helper.assertValueEqual(statsMenu.getEmeraldBalance(), 18,
                "villager stats balance did not reflect the initial bank deposit");
        helper.assertValueEqual(statsMenu.getEmeraldInventoryCount(), 0,
                "villager stats inventory count did not reflect the completed bank deposit");
        EmeraldChestBlockEntity chest = (EmeraldChestBlockEntity) level.getBlockEntity(chestPos);
        if (chest == null) {
            helper.fail("emerald chest block entity was not created");
            return;
        }
        helper.assertValueEqual(chest.getEmeraldCount(), 18,
                "the bank chest did not receive the emerald-block value");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void eachCompletedTradeAppliesOnceForMultiplePlayers(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        Player firstPlayer = helper.makeMockPlayer(GameType.SURVIVAL);
        Player secondPlayer = helper.makeMockPlayer(GameType.SURVIVAL);
        MerchantOffer offer = new MerchantOffer(
                new ItemCost(Items.EMERALD, 3),
                new ItemStack(Items.BREAD),
                10,
                0,
                1.0F
        );

        completeTrade(villager, firstPlayer, offer, offer.getCostA(), ItemStack.EMPTY);
        completeTrade(villager, secondPlayer, offer, offer.getCostA(), ItemStack.EMPTY);

        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        helper.assertValueEqual(stats.getEmeraldBalance(), 6,
                "a completed trade was not applied exactly once for each player identity");
        helper.assertValueEqual(emeraldValue(villager.getInventory()), 6,
                "physical emerald inventory was not changed exactly once per completed trade");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void breakingAnEmeraldBlockMakesExactChange(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        villager.getInventory().setItem(0, new ItemStack(Items.EMERALD_BLOCK));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        MerchantOffer offer = new MerchantOffer(
                new ItemCost(Items.WHEAT),
                new ItemStack(Items.EMERALD),
                10,
                0,
                1.0F
        );

        completeTrade(villager, player, offer, offer.getCostA(), ItemStack.EMPTY);

        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        helper.assertValueEqual(stats.getEmeraldBalance(), -1,
                "a one-emerald result did not create the expected debt");
        helper.assertValueEqual(EmeraldConsolidationUtils.countItem(villager.getInventory(), Items.EMERALD), 8,
                "breaking an emerald block did not return eight emeralds as change");
        helper.assertValueEqual(EmeraldConsolidationUtils.countItem(villager.getInventory(), Items.EMERALD_BLOCK), 0,
                "the source emerald block was not broken for exact change");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void bankWithdrawalRejectsUnrepresentableEmeraldChange(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos bankRelativePos = new BlockPos(1, 1, 1);
        BlockPos chestRelativePos = new BlockPos(1, 1, 2);
        BlockPos bankPos = helper.absolutePos(bankRelativePos);
        BlockPos chestPos = helper.absolutePos(chestRelativePos);
        helper.setBlock(bankRelativePos, ECAPBlocks.BANK.get().defaultBlockState());
        helper.setBlock(chestRelativePos, ECAPBlocks.EMERALD_CHEST.get().defaultBlockState());

        BankBlockEntity bank = (BankBlockEntity) level.getBlockEntity(bankPos);
        EmeraldChestBlockEntity chest = (EmeraldChestBlockEntity) level.getBlockEntity(chestPos);
        if (bank == null || chest == null) {
            helper.fail("bank or emerald chest block entity was not created");
            return;
        }

        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.EMERALD_BLOCK, 2));
        }
        BankBlockEntity.serverTick(level, bankPos, level.getBlockState(bankPos), bank);

        int valueBefore = chest.getEmeraldCount();
        helper.assertFalse(bank.withdrawFromLinkedChests(level, 1),
                "bank withdrew emeralds without room to store the block change");
        helper.assertValueEqual(chest.getEmeraldCount(), valueBefore,
                "failed bank withdrawal destroyed the block's fractional change");
        helper.assertValueEqual(bank.getLiveEmeraldValue(level), valueBefore,
                "failed bank withdrawal changed the bank's physical emerald value");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void bankWithdrawalDoesNotOverpayFromAnInsufficientBlockStack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos bankRelativePos = new BlockPos(1, 1, 1);
        BlockPos chestRelativePos = new BlockPos(1, 1, 2);
        BlockPos bankPos = helper.absolutePos(bankRelativePos);
        BlockPos chestPos = helper.absolutePos(chestRelativePos);
        helper.setBlock(bankRelativePos, ECAPBlocks.BANK.get().defaultBlockState());
        helper.setBlock(chestRelativePos, ECAPBlocks.EMERALD_CHEST.get().defaultBlockState());

        BankBlockEntity bank = (BankBlockEntity) level.getBlockEntity(bankPos);
        EmeraldChestBlockEntity chest = (EmeraldChestBlockEntity) level.getBlockEntity(chestPos);
        if (bank == null || chest == null) {
            helper.fail("bank or emerald chest block entity was not created");
            return;
        }

        chest.setItem(0, new ItemStack(Items.EMERALD_BLOCK));
        BankBlockEntity.serverTick(level, bankPos, level.getBlockState(bankPos), bank);

        int valueBefore = chest.getEmeraldCount();
        helper.assertFalse(bank.withdrawFromLinkedChests(level, 10),
                "bank paid ten emeralds from a chest containing only one block");
        helper.assertValueEqual(chest.getEmeraldCount(), valueBefore,
                "failed bank withdrawal consumed an insufficient emerald block");
        helper.assertValueEqual(bank.getLiveEmeraldValue(level), valueBefore,
                "failed bank withdrawal changed the bank's physical emerald value");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void villagerEmeraldDropDebitsBalanceOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        stats.setEmeraldBalance(20);

        ItemEntity dropped = com.orangevillager61.emeraldcapitalism.util.EntityDropUtils.spawn(
                villager, level, new ItemStack(Items.EMERALD_BLOCK, 2));

        helper.assertTrue(dropped != null, "villager emerald drop did not create an item entity");
        helper.assertValueEqual(stats.getEmeraldBalance(), 2,
                "villager emerald drop was not debited exactly once at block value");
        helper.succeed();
    }

    private static void completeTrade(
            Villager villager, Player player, MerchantOffer offer, ItemStack costA, ItemStack costB) {
        MerchantOffers offers = new MerchantOffers();
        offers.add(offer);
        villager.setOffers(offers);
        villager.setTradingPlayer(player);

        MerchantContainer container = new MerchantContainer(villager);
        MerchantResultSlot resultSlot = new MerchantResultSlot(player, villager, container, 2, 0, 0);
        container.setItem(0, costA.copy());
        container.setItem(1, costB.copy());
        ItemStack result = container.getItem(2).copy();
        if (result.isEmpty()) {
            throw new IllegalStateException("test offer did not produce a merchant result");
        }
        resultSlot.onTake(player, result);
    }

    private static int emeraldValue(SimpleContainer inventory) {
        return EmeraldConsolidationUtils.countItem(inventory, Items.EMERALD)
                + 9 * EmeraldConsolidationUtils.countItem(inventory, Items.EMERALD_BLOCK);
    }
}
