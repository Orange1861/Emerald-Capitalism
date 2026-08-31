package com.orangevillager61.emeraldcapitalism.item;

import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class VillageMapItemGameTests {

    private VillageMapItemGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void successfulUsePutsFilledMapInUsedHand(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack ticket = new ItemStack(ECAPItems.VILLAGE_MAP.get());
        ItemStack map = new ItemStack(Items.FILLED_MAP);
        player.setItemInHand(InteractionHand.MAIN_HAND, ticket);

        VillageMapItem.replaceTicketWithMap(player, InteractionHand.MAIN_HAND, ticket, map);

        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND) == map,
                "successful Village Map use did not replace the ticket in the used hand");
        helper.succeed();
    }
}
