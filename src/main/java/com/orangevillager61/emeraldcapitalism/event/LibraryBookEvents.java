package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.network.OpenAuthoredBookPacket;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookRegistry;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookDefinition;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookStackFactory;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Resolves dynamic authored-book content at the server-authoritative use boundary. */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class LibraryBookEvents {

    private LibraryBookEvents() {
    }

    @SubscribeEvent
    public static void onRightClickWrittenBook(PlayerInteractEvent.RightClickItem event) {
        if (event.isCanceled()) {
            return;
        }

        ItemStack stack = event.getItemStack();
        if (!LibraryBookStackFactory.isAuthoredBook(stack)) {
            return;
        }

        if (event.getLevel().isClientSide()) {
            // Vanilla opens the client screen immediately, before the server can
            // replace stale dynamic pages. Let the server refresh and reopen it.
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            PacketDistributor.sendToServer(new OpenAuthoredBookPacket(event.getHand()));
            return;
        }

        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }

        CustomData metadata = stack.get(DataComponents.CUSTOM_DATA);
        if (metadata == null) {
            return;
        }

        String bookId = metadata.copyTag().getString("book_id");
        if (bookId.isEmpty()) {
            return;
        }
        LibraryBookDefinition definition = LibraryBookRegistry.get(bookId).orElse(null);
        if (definition == null || definition.type() == LibraryBookType.STATIC) {
            return;
        }

        SteveGraveEvents.ensureTargetResolved(level);
        if (LibraryBookStackFactory.refreshWorldData(definition, stack, level)
                && event.getEntity() instanceof ServerPlayer player) {
            OpenAuthoredBookPacket.syncHeldStack(player, event.getHand(), stack);
        }
    }
}
