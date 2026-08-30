package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class VillagerMapTradeGameTests {

    private VillagerMapTradeGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void cartographerJourneymanOffersVillageMapForEightEmeralds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = EntityType.VILLAGER.create(level);
        if (villager == null) {
            helper.fail("Could not create a villager to inspect the Cartographer trade");
            return;
        }

        VillagerTrades.ItemListing[] listings = VillagerTrades.TRADES
                .get(VillagerProfession.CARTOGRAPHER)
                .get(2);
        MerchantOffer villageMapOffer = null;
        for (VillagerTrades.ItemListing listing : listings) {
            MerchantOffer offer = listing.getOffer(villager, level.getRandom());
            if (offer != null && offer.getResult().is(ECAPItems.VILLAGE_MAP.get())) {
                villageMapOffer = offer;
                break;
            }
        }

        if (villageMapOffer == null) {
            helper.fail("Cartographer journeyman trades did not include the Village Map");
            return;
        }

        ItemStack cost = villageMapOffer.getCostA();
        helper.assertTrue(cost.is(Items.EMERALD) && cost.getCount() == 8,
                "Village Map trade did not cost exactly 8 emeralds");
        helper.assertTrue(villageMapOffer.getResult().getCount() == 1,
                "Village Map trade did not offer exactly one map");
        helper.succeed();
    }
}
