package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookDefinition;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookRegistry;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Resolves dynamic authored-book content at the server-authoritative use boundary. */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class LibraryBookEvents {

    private LibraryBookEvents() {
    }

    @SubscribeEvent
    public static void onRightClickWrittenBook(PlayerInteractEvent.RightClickItem event) {
        if (event.isCanceled() || event.getLevel().isClientSide()
                || !(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }

        ItemStack stack = event.getItemStack();
        if (!stack.is(Items.WRITTEN_BOOK)) {
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
        definition.refreshWorldData(stack, level);
    }
}
