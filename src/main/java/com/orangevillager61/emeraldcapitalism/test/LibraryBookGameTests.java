package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookDefinition;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookRegistry;
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
}
