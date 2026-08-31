package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.worldgen.BankVaultRuinsProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class BankVaultRuinsGameTests {

    private BankVaultRuinsGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void designatedEmeraldChestReceivesSecondVaultMapLootTable(GameTestHelper helper) {
        StructureTemplate template = helper.getLevel().getStructureManager()
                .get(ModIds.id("bank_vault_ruins"))
                .orElseThrow(() -> new AssertionError("Missing bank vault ruins template"));
        StructurePlaceSettings settings = new StructurePlaceSettings();
        StructureTemplate.StructureBlockInfo chest = template
                .filterBlocks(BlockPos.ZERO, settings, ECAPBlocks.EMERALD_CHEST.get())
                .stream()
                .min((left, right) -> left.pos().compareTo(right.pos()))
                .orElseThrow(() -> new AssertionError("Bank vault ruins template has no emerald chest"));

        StructureTemplate.StructureBlockInfo processed = new BankVaultRuinsProcessor().process(
                helper.getLevel(), BlockPos.ZERO, BlockPos.ZERO, chest, chest, settings, template);

        helper.assertTrue(processed.nbt() != null
                        && "emeraldcapitalism:chests/bank_vault_ruins_map".equals(
                        processed.nbt().getString("LootTable")),
                "The designated emerald chest did not receive the second-vault-map loot table");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void survivingOrdinaryEmeraldChestsReceiveVaultLootTable(GameTestHelper helper) {
        StructureTemplate template = helper.getLevel().getStructureManager()
                .get(ModIds.id("bank_vault_ruins"))
                .orElseThrow(() -> new AssertionError("Missing bank vault ruins template"));
        StructurePlaceSettings settings = new StructurePlaceSettings();
        var chests = template.filterBlocks(BlockPos.ZERO, settings, ECAPBlocks.EMERALD_CHEST.get());
        BlockPos designatedMapChest = chests.stream()
                .map(StructureTemplate.StructureBlockInfo::pos)
                .min((left, right) -> left.compareTo(right))
                .orElseThrow(() -> new AssertionError("Bank vault ruins template has no emerald chest"));
        boolean foundSurvivingOrdinaryChest = false;

        for (StructureTemplate.StructureBlockInfo chest : chests) {
            if (chest.pos().equals(designatedMapChest)) {
                continue;
            }
            StructureTemplate.StructureBlockInfo processed = new BankVaultRuinsProcessor().process(
                    helper.getLevel(), BlockPos.ZERO, BlockPos.ZERO, chest, chest, settings, template);
            if (!processed.state().is(ECAPBlocks.EMERALD_CHEST.get())) {
                continue;
            }
            foundSurvivingOrdinaryChest = true;
            helper.assertTrue(processed.nbt() != null
                            && "emeraldcapitalism:chests/bank_vault_ruins".equals(
                            processed.nbt().getString("LootTable")),
                    "A surviving ordinary emerald chest did not receive the vault loot table");
        }

        helper.assertTrue(foundSurvivingOrdinaryChest,
                "Bank vault ruins template has no surviving ordinary emerald chest to verify");
        helper.succeed();
    }
}
