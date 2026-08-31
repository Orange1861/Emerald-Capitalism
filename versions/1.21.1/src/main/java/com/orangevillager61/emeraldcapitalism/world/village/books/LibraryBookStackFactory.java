package com.orangevillager61.emeraldcapitalism.world.village.books;

import com.orangevillager61.emeraldcapitalism.world.structure.SteveGravePlacer;
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

/** Minecraft adapter that materializes core authored-book definitions as item stacks. */
public final class LibraryBookStackFactory {

    private LibraryBookStackFactory() {
    }

    public static ItemStack createItemStack(LibraryBookDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        return createItemStack(definition, Optional.empty());
    }

    public static ItemStack createItemStack(LibraryBookDefinition definition, ServerLevel level) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(level, "level");
        if (definition.type() == LibraryBookType.STATIC) {
            return createItemStack(definition);
        }

        ServerLevel overworld = level.getServer().overworld();
        SteveGraveSavedData graveData = SteveGraveSavedData.get(overworld);
        BlockPos coordinates = graveData.isPlaced()
                ? graveData.placedOrigin()
                : Optional.ofNullable(graveData.target())
                .map(target -> SteveGravePlacer.structureOrigin(overworld, target))
                .orElse(null);
        return createItemStack(definition, Optional.ofNullable(coordinates));
    }

    /** Refreshes a previously materialized dynamic book before it is opened. */
    public static boolean refreshWorldData(
            LibraryBookDefinition definition, ItemStack stack, ServerLevel level) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(level, "level");
        if (definition.type() == LibraryBookType.STATIC || !stack.is(Items.WRITTEN_BOOK)) {
            return false;
        }

        CustomData metadata = stack.get(DataComponents.CUSTOM_DATA);
        if (metadata == null || !definition.id().equals(metadata.copyTag().getString("book_id"))) {
            return false;
        }

        WrittenBookContent content = createItemStack(definition, level)
                .get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) {
            throw new IllegalStateException("Dynamic authored book did not produce written content");
        }
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, content);
        return true;
    }

    private static ItemStack createItemStack(
            LibraryBookDefinition definition, Optional<BlockPos> steveGraveTarget) {
        LibraryBookType.Coordinates coordinates = steveGraveTarget
                .map(target -> new LibraryBookType.Coordinates(
                        target.getX(), target.getY(), target.getZ()))
                .orElse(null);
        List<String> resolvedPages = definition.type().resolvePages(
                definition.pages(), Optional.ofNullable(coordinates));
        List<Filterable<Component>> writtenPages = resolvedPages.stream()
                .map(page -> Filterable.<Component>passThrough(Component.literal(page)))
                .toList();
        WrittenBookContent content = new WrittenBookContent(
                Filterable.passThrough(definition.title()), definition.author(), 0, writtenPages, true);
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, content);

        // Preserve the source identity and rarity for placement and server-side rules.
        CompoundTag metadata = new CompoundTag();
        metadata.putString("book_id", definition.id());
        metadata.putString("book_rarity", definition.rarity().id());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(metadata));
        return stack;
    }
}
