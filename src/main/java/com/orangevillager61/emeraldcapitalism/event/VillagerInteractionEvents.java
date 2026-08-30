package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.menu.VillagerStatsMenu;
import com.orangevillager61.emeraldcapitalism.network.ProtocolStringLimits;
import com.orangevillager61.emeraldcapitalism.util.BankEmployeeLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.SimpleMenuProvider;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public class VillagerInteractionEvents {

    @SubscribeEvent
    public static void onPlayerInteractWithVillager(PlayerInteractEvent.EntityInteract event) {
        // Only on server side
        if (event.isCanceled() || event.getLevel().isClientSide) {
            return;
        }

        if (!(event.getTarget() instanceof AbstractVillager villager)) {
            return;
        }

        // Employee villagers are infrastructure workers, not vanilla merchants.
        // Keep sneaking available for the mod's villager-stats UI, but consume a
        // normal interaction before Villager.mobInteract can open MerchantMenu.
        if (event.getLevel() instanceof ServerLevel serverLevel
                && villager instanceof Villager regularVillager
                && BankEmployeeLookup.isEmployee(serverLevel, regularVillager)
                && !event.getEntity().isShiftKeyDown()) {
            event.setCanceled(true);
            return;
        }

        // Check if player is sneaking and interacting with a villager
        if (Config.enableVillagerStatsShiftClick && event.getEntity().isShiftKeyDown()) {
            if (event.getEntity() instanceof ServerPlayer serverPlayer) {
                VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
                
                // Open the custom GUI
                serverPlayer.openMenu(new SimpleMenuProvider(
                        (id, playerInventory, player) ->
                                new VillagerStatsMenu(id, playerInventory, villager),
                        villager.getDisplayName()
                ), buf -> {
                    buf.writeInt(villager.getId());
                    buf.writeUtf(ProtocolStringLimits.clamp(
                            VillagerStatsMenu.getProfessionLabel(villager),
                            ProtocolStringLimits.MAX_PROFESSION_LABEL_LENGTH),
                            ProtocolStringLimits.MAX_PROFESSION_LABEL_LENGTH);

                    buf.writeUtf(ProtocolStringLimits.clamp(
                            VillagerStatsMenu.getFirstNameLabel(villager),
                            ProtocolStringLimits.MAX_PARENT_NAME_LENGTH),
                            ProtocolStringLimits.MAX_PARENT_NAME_LENGTH);
                    
                    // Write cached parent names
                    String parent1Name = stats.getParent1Name();
                    buf.writeBoolean(parent1Name != null);
                    if (parent1Name != null) {
                        buf.writeUtf(ProtocolStringLimits.clamp(parent1Name,
                                ProtocolStringLimits.MAX_PARENT_NAME_LENGTH),
                                ProtocolStringLimits.MAX_PARENT_NAME_LENGTH);
                    }
                    
                    String parent2Name = stats.getParent2Name();
                    buf.writeBoolean(parent2Name != null);
                    if (parent2Name != null) {
                        buf.writeUtf(ProtocolStringLimits.clamp(parent2Name,
                                ProtocolStringLimits.MAX_PARENT_NAME_LENGTH),
                                ProtocolStringLimits.MAX_PARENT_NAME_LENGTH);
                    }
                });

                event.setCanceled(true); // Prevent normal villager trading GUI
            }
        }
    }

}
