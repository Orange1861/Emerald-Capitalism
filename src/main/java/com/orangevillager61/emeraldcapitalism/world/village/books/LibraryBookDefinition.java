package com.orangevillager61.emeraldcapitalism.world.village.books;

import com.orangevillager61.emeraldcapitalism.world.structure.SteveGraveSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable, resource-backed authored-book content. */
public record LibraryBookDefinition(
        String id,
        String title,
        String author,
        LibraryBookRarity rarity,
        LibraryBookType type,
        List<String> pages
) {
    public LibraryBookDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(rarity, "rarity");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(pages, "pages");
        pages = List.copyOf(pages);
    }

    /** Builds the actual vanilla written-book stack that can live in a shelf. */
    public ItemStack createItemStack() {
        return createItemStack(Optional.empty());
    }

    /** Builds a book with world-data tokens resolved from the server's overworld. */
    public ItemStack createItemStack(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        ServerLevel overworld = level.getServer().overworld();
        return createItemStack(Optional.ofNullable(SteveGraveSavedData.get(overworld).target()));
    }

    private ItemStack createItemStack(Optional<BlockPos> steveGraveTarget) {
        List<String> resolvedPages = type.resolvePages(pages, steveGraveTarget);
        List<Filterable<Component>> writtenPages = resolvedPages.stream()
                .map(page -> Filterable.<Component>passThrough(Component.literal(page)))
                .toList();
        WrittenBookContent content = new WrittenBookContent(
                Filterable.passThrough(title), author, 0, writtenPages, true);
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, content);

        // Preserve the source identity and rarity for placement and server-side rules.
        CompoundTag metadata = new CompoundTag();
        metadata.putString("book_id", id);
        metadata.putString("book_rarity", rarity.id());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(metadata));
        return stack;
    }
}
