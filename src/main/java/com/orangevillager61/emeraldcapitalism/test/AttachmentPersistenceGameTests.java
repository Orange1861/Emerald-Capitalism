package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.LumberjackProductionAttachment;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class AttachmentPersistenceGameTests {

    private AttachmentPersistenceGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void registeredAttachmentSurvivesEntitySaveLoad(GameTestHelper helper) {
        AttachmentType<VillagerStatsAttachment> type = EmeraldCapitalismAttachments.VILLAGER_STATS.get();
        AttachmentType<LumberjackProductionAttachment> productionType =
                EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION.get();
        Villager original = com.orangevillager61.emeraldcapitalism.util.EntityCreation.create(
                EntityType.VILLAGER, helper.getLevel());
        if (original == null) {
            helper.fail("Could not create a villager for the attachment lifecycle test");
            return;
        }

        original.moveTo(helper.absolutePos(new BlockPos(1, 1, 1)), 0.0F, 0.0F);
        VillagerStatsAttachment originalStats = original.getData(type);
        UUID child = UUID.randomUUID();
        UUID namingVillage = UUID.randomUUID();
        originalStats.setHungerLevel(6);
        originalStats.setPersonalFirstElement("bem");
        originalStats.setPersonalSecondElement("mun");
        originalStats.setNamingVillageId(namingVillage);
        originalStats.addChild(child);
        originalStats.setEmeraldBalance(-3);
        LumberjackProductionAttachment originalProduction = original.getData(productionType);
        originalProduction.setCharcoalQuota(3.5D);
        GlobalPos pendingFurnace = GlobalPos.of(helper.getLevel().dimension(),
                helper.absolutePos(new BlockPos(2, 1, 1)));
        originalProduction.setPendingCharcoalFurnace(pendingFurnace);

        CompoundTag saved = original.saveWithoutId(new CompoundTag());
        Villager restored = com.orangevillager61.emeraldcapitalism.util.EntityCreation.create(
                EntityType.VILLAGER, helper.getLevel());
        if (restored == null) {
            helper.fail("Could not create the restored villager for the attachment lifecycle test");
            return;
        }
        restored.load(saved);

        VillagerStatsAttachment restoredStats = restored.getData(type);
        helper.assertValueEqual(restoredStats.getHungerLevel(), 6, "hunger did not survive entity save/load");
        helper.assertTrue(restoredStats.getVillagerName() == null,
                "derived villager name should not be persisted");
        helper.assertValueEqual(restoredStats.getPersonalFirstElement(), "bem",
                "personal first element did not survive entity save/load");
        helper.assertValueEqual(restoredStats.getPersonalSecondElement(), "mun",
                "personal second element did not survive entity save/load");
        helper.assertValueEqual(restoredStats.getNamingVillageId(), namingVillage,
                "naming village identity did not survive entity save/load");
        helper.assertValueEqual(restoredStats.getChildCount(), 1,
                "family collection did not survive entity save/load");
        helper.assertTrue(restoredStats.getChildrenUUIDs().contains(child),
                "child UUID did not survive entity save/load");
        helper.assertValueEqual(restoredStats.getEmeraldBalance(), -3,
                "emerald balance did not survive entity save/load");
        LumberjackProductionAttachment restoredProduction = restored.getData(productionType);
        helper.assertValueEqual(restoredProduction.getCharcoalQuota(), 3.5D,
                "lumberjack charcoal quota did not survive entity save/load");
        helper.assertValueEqual(restoredProduction.getPendingCharcoalFurnace().orElseThrow(), pendingFurnace,
                "lumberjack in-flight furnace did not survive entity save/load");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void attachmentBackedBehaviorReadsAndWritesDurableState(GameTestHelper helper) {
        Villager villager = com.orangevillager61.emeraldcapitalism.util.EntityCreation.create(
                EntityType.VILLAGER, helper.getLevel());
        if (villager == null) {
            helper.fail("Could not create a villager for attachment behavior integration");
            return;
        }

        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        stats.setHungerLevel(4);
        stats.setEmeraldBalance(-1);
        UUID parent = UUID.randomUUID();
        stats.setParent1UUID(parent);
        stats.setParent1Name("Parent");

        helper.assertTrue(stats.isHungry(), "hunger behavior did not read attachment state");
        helper.assertTrue(stats.isInDebt(), "emerald balance behavior did not read attachment state");
        helper.assertTrue(stats.hasParents(), "family behavior did not read attachment state");

        stats.increaseHunger(4);
        stats.addChild(UUID.randomUUID());
        helper.assertValueEqual(stats.getHungerLevel(), 8, "hunger behavior did not write attachment state");
        helper.assertValueEqual(stats.getChildCount(), 1, "family behavior did not write attachment state");
        helper.succeed();
    }
}
