package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookDefinition;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookRegistry;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookRarity;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookType;
import com.orangevillager61.emeraldcapitalism.world.structure.SteveGraveSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Runtime checks for generated authored-book item stacks. */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class LibraryBookGameTests {

    private LibraryBookGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void villageManagerBookConvertsToARealWrittenBook(GameTestHelper helper) {
        LibraryBookDefinition definition = LibraryBookRegistry.entries().stream()
                .filter(book -> book.rarity().id().equals("village_manager"))
                .findFirst()
                .orElse(null);
        if (definition == null) {
            helper.fail("The converted Village Manager test book was not loaded");
            return;
        }

        var stack = definition.createItemStack();
        if (!stack.is(Items.WRITTEN_BOOK)) {
            helper.fail("Authored book did not create a vanilla written-book item");
            return;
        }
        WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null
                || !definition.title().equals(content.title().get(false))
                || !definition.author().equals(content.author())
                || content.pages().size() != definition.pages().size()) {
            helper.fail("Written-book content did not match the authored definition");
            return;
        }
        CustomData metadata = stack.get(DataComponents.CUSTOM_DATA);
        if (metadata == null
                || !definition.id().equals(metadata.copyTag().getString("book_id"))
                || !definition.rarity().id().equals(metadata.copyTag().getString("book_rarity"))) {
            helper.fail("Authored book metadata did not preserve its source identity");
            return;
        }
        if (definition.rarity().isRandomLibraryPool()) {
            helper.fail("Village Manager books must not enter the random library pool");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void graveLocationBookResolvesThePersistedTarget(GameTestHelper helper) {
        LibraryBookDefinition definition = LibraryBookRegistry.entries().stream()
                .filter(book -> book.type() == LibraryBookType.STEVE_GRAVE_LOCATION)
                .findFirst()
                .orElse(null);
        if (definition == null) {
            helper.fail("The Steve grave location book was not loaded");
            return;
        }
        if (definition.rarity() != LibraryBookRarity.LEGENDARY
                || !"Sairviv".equals(definition.author())) {
            helper.fail("Steve grave location book does not have the requested author or Legendary rarity");
            return;
        }

        BlockPos target = new BlockPos(20_000, 0, -30_000);
        SteveGraveSavedData.get(helper.getLevel()).setTarget(target);
        WrittenBookContent content = definition.createItemStack(helper.getLevel())
                .get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null || content.pages().isEmpty()
                || !content.pages().getFirst().get(false).getString()
                .contains("[20000, ~, -30000]")) {
            helper.fail("Steve grave location book did not resolve the persisted coordinates");
            return;
        }
        helper.succeed();
    }
}
