package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.item.EmeraldLeadItem;
import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Prevents vanilla entity interaction logic from making Emerald Lead affect invalid entities. */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class EmeraldLeadEvents {

    private EmeraldLeadEvents() {}

    @SubscribeEvent
    public static void onSpecificEntityInteract(PlayerInteractEvent.EntityInteractSpecific event) {
        if (shouldCancel(event, event.getTarget())) {
            event.setCancellationResult(InteractionResult.PASS);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (shouldCancel(event, event.getTarget())) {
            event.setCancellationResult(InteractionResult.PASS);
            event.setCanceled(true);
        }
    }

    private static boolean shouldCancel(PlayerInteractEvent event, Entity target) {
        ItemStack stack = event.getItemStack();
        return stack.is(ECAPItems.EMERALD_LEAD.get())
                && !(target instanceof LivingEntity livingEntity
                        && EmeraldLeadItem.isValidTarget(livingEntity));
    }
}
