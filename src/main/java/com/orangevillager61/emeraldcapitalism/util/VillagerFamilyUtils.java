package com.orangevillager61.emeraldcapitalism.util;

import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import net.minecraft.world.entity.npc.Villager;

import java.util.UUID;

/** Family relationship checks used by villager breeding and display code. */
public final class VillagerFamilyUtils {

    /** Fallback display name for an unnamed villager. */
    public static final String DEFAULT_VILLAGER_NAME = "Local Villager";

    private VillagerFamilyUtils() {
    }

    /** Returns the custom or stored display name, or {@link #DEFAULT_VILLAGER_NAME}. */
    public static String getVillagerDisplayName(Villager villager) {
        if (villager.hasCustomName()) {
            return villager.getCustomName().getString();
        }
        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        String storedName = stats.getVillagerName();
        if (storedName != null && !storedName.isEmpty()) {
            return storedName;
        }
        return DEFAULT_VILLAGER_NAME;
    }

    /** Returns whether the villagers share a tracked relationship that blocks breeding. */
    public static boolean areRelated(Villager villagerA, Villager villagerB) {
        return isParentChildRelationship(villagerA, villagerB)
                || areSiblings(villagerA, villagerB)
                || isGrandparentGrandchildRelationship(villagerA, villagerB);
    }

    /** Returns whether either villager is recorded as the other's parent. */
    public static boolean isParentChildRelationship(Villager villagerA, Villager villagerB) {
        VillagerStatsAttachment statsA = villagerA.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        VillagerStatsAttachment statsB = villagerB.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);

        UUID uuidA = villagerA.getUUID();
        UUID uuidB = villagerB.getUUID();

        if (uuidA.equals(statsB.getParent1UUID()) || uuidA.equals(statsB.getParent2UUID())) {
            return true;
        }

        if (uuidB.equals(statsA.getParent1UUID()) || uuidB.equals(statsA.getParent2UUID())) {
            return true;
        }

        return false;
    }

    /** Returns whether the villagers share at least one recorded parent. */
    public static boolean areSiblings(Villager villagerA, Villager villagerB) {
        VillagerStatsAttachment statsA = villagerA.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        VillagerStatsAttachment statsB = villagerB.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);

        return areSiblings(statsA, statsB);
    }

    /** Attachment-level sibling check used when stats have already been loaded. */
    public static boolean areSiblings(VillagerStatsAttachment statsA, VillagerStatsAttachment statsB) {
        if (!statsA.hasParents() || !statsB.hasParents()) {
            return false;
        }

        UUID parentA1 = statsA.getParent1UUID();
        UUID parentA2 = statsA.getParent2UUID();
        UUID parentB1 = statsB.getParent1UUID();
        UUID parentB2 = statsB.getParent2UUID();

        if (parentA1 != null) {
            if (parentA1.equals(parentB1) || parentA1.equals(parentB2)) {
                return true;
            }
        }
        if (parentA2 != null) {
            if (parentA2.equals(parentB1) || parentA2.equals(parentB2)) {
                return true;
            }
        }

        return false;
    }

    /** Returns whether either villager is recorded as the other's grandparent. */
    public static boolean isGrandparentGrandchildRelationship(Villager villagerA, Villager villagerB) {
        VillagerStatsAttachment statsA = villagerA.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        VillagerStatsAttachment statsB = villagerB.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);

        UUID uuidA = villagerA.getUUID();
        UUID uuidB = villagerB.getUUID();

        if (statsB.isGrandparent(uuidA)) {
            return true;
        }

        if (statsA.isGrandparent(uuidB)) {
            return true;
        }

        return false;
    }
}
