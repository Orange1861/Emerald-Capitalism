package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.util.VillagerNameManager;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Focused invariants for event-driven derived villager names. */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class VillagerNameRefreshGameTests {

    private VillagerNameRefreshGameTests() {
    }

    @GameTest(template = "empty_3x3x3", batch = "ecap_name_profession")
    public static void professionMutationRefreshesDerivedName(GameTestHelper helper) {
        VillageRecord village = createVillage(helper, "Profession Test Village");
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        VillagerNameManager.assignNameIfNeeded(villager, village);

        VillagerStatsAttachment stats = stats(villager);
        String unemployedName = stats.getVillagerName();
        helper.assertTrue(unemployedName != null && !unemployedName.isBlank(),
                "villager did not receive its initial derived name");

        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        helper.runAfterDelay(2, () -> {
            helper.assertValueEqual(stats.getLastRenderedProfession(), "farmer",
                    "profession mutation did not refresh the cached name input");
            helper.assertFalse(unemployedName.equals(stats.getVillagerName()),
                    "profession mutation did not rebuild the displayed name");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_3x3x3", batch = "ecap_name_village_rename")
    public static void villageRenameRefreshesOnlyThroughOriginIndex(GameTestHelper helper) {
        VillageRecord village = createVillage(helper, "Old Test Village");
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        VillagerNameManager.assignNameIfNeeded(villager, village);

        VillagerStatsAttachment stats = stats(villager);
        helper.assertValueEqual(stats.getNamingVillageId(), village.getVillageId(),
                "villager was not indexed under its naming village");

        VillageRegistryData data = VillageRegistryData.get(helper.getLevel());
        data.renameVillage(helper.getLevel(), village, "Renamed Test Village");
        helper.runAfterDelay(2, () -> {
            helper.assertValueEqual(stats.getLastRenderedOriginVillageName(), "Renamed Test Village",
                    "village rename did not refresh its loaded origin villager");
            helper.assertTrue(stats.getVillagerName().contains("Renamed Test Village"),
                    "derived display name did not contain the renamed origin");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_3x3x3", batch = "ecap_name_age_boundary")
    public static void ageBoundaryRefreshesDerivedName(GameTestHelper helper) {
        VillageRecord village = createVillage(helper, "Age Test Village");
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        villager.setAge(-100);
        VillagerNameManager.assignNameIfNeeded(villager, village);

        VillagerStatsAttachment stats = stats(villager);
        String babyName = stats.getVillagerName();
        helper.assertValueEqual(stats.getLastRenderedAgeStage(), 0,
                "baby name did not record the child age stage");

        villager.setAge(0);
        helper.runAfterDelay(2, () -> {
            helper.assertValueEqual(stats.getLastRenderedAgeStage(), 1,
                    "baby/adult boundary did not refresh the cached age stage");
            helper.assertFalse(babyName.equals(stats.getVillagerName()),
                    "baby/adult boundary did not rebuild the displayed name");
            helper.succeed();
        });
    }

    private static VillageRecord createVillage(GameTestHelper helper, String name) {
        BlockPos bellPos = helper.absolutePos(new BlockPos(1, 1, 1));
        AABB bounds = new AABB(
                bellPos.getX() - 8, bellPos.getY() - 4, bellPos.getZ() - 8,
                bellPos.getX() + 9, bellPos.getY() + 5, bellPos.getZ() + 9);
        VillageRegistryData data = VillageRegistryData.get(helper.getLevel());
        VillageRecord village = data.getOrCreateVillage(UUID.randomUUID(), bellPos, bounds);
        data.renameVillage(helper.getLevel(), village, name);
        return village;
    }

    private static VillagerStatsAttachment stats(Villager villager) {
        return villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
    }
}
