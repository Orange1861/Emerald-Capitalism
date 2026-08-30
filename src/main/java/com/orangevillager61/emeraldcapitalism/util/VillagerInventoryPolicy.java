package com.orangevillager61.emeraldcapitalism.util;

import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Shared rules for inventory items that a villager must retain for survival or
 * profession work. Vanilla exposes the profession's requested items, so those
 * declarations remain the primary source of truth for vanilla and datapack-
 * supplied professions. Small tag-based additions cover work implemented by
 * this mod where vanilla has no requested-item declaration.
 */
public final class VillagerInventoryPolicy {

    private VillagerInventoryPolicy() {
    }

    /**
     * Returns whether an inventory stack is protected from full-inventory bank
     * liquidation. Food and emerald value are protected for every villager;
     * profession inputs are protected only for the profession that can use
     * them.
     */
    public static boolean isReservedForVillager(Villager villager, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        if (stack.get(DataComponents.FOOD) != null
                || stack.is(Items.EMERALD)
                || stack.is(Items.EMERALD_BLOCK)) {
            return true;
        }

        VillagerProfession profession = villager.getVillagerData().getProfession();
        if (profession.requestedItems().contains(stack.getItem())) {
            return true;
        }

        if (profession == VillagerProfession.FARMER) {
            // The tag also covers NeoForge SpecialPlantable items that are not
            // present in the vanilla requestedItems set.
            return stack.is(ItemTags.VILLAGER_PLANTABLE_SEEDS)
                    || stack.is(Items.BONE_MEAL);
        }

        if (profession == ECAPVillagerProfessions.MAYOR.get()) {
            return stack.is(ItemTags.DOORS) || stack.is(ItemTags.BEDS);
        }

        if (profession == ECAPVillagerProfessions.LUMBERJACK.get()) {
            // Saplings are replanted by the lumberjack; coal/charcoal is a
            // temporary furnace input used by its production loop.
            return stack.is(ItemTags.SAPLINGS) || stack.is(ItemTags.COALS);
        }

        return false;
    }
}
