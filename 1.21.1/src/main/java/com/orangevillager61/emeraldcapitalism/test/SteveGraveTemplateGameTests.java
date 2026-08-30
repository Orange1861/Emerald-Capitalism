package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.util.ModIds;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Verifies that the user-provided NBT is packaged as a loadable template. */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class SteveGraveTemplateGameTests {
    private SteveGraveTemplateGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void steveGraveTemplateIsLoadable(GameTestHelper helper) {
        StructureTemplate template = helper.getLevel().getStructureManager()
                .get(ModIds.id("steve_grave"))
                .orElseThrow(() -> new AssertionError("Missing Steve grave template"));
        helper.assertTrue(template.getSize().getX() > 0
                        && template.getSize().getY() > 0
                        && template.getSize().getZ() > 0,
                "Steve grave template has an invalid size");
        helper.succeed();
    }
}
