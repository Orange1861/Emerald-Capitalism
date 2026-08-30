package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.util.VillagerFamilyUtils;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import com.orangevillager61.emeraldcapitalism.util.VillagerFoodSelection;
import com.orangevillager61.emeraldcapitalism.util.VillagerNameManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Applies family, hunger, and food-transfer side effects after successful villager births.
 * Vanilla villager breeding calls this class directly because it does not emit
 * {@link BabyEntitySpawnEvent} on this target.
 */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public class VillagerBreedingEvents {

    @SubscribeEvent
    public static void onServerLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            VillagerBreedingSessions.tick(level);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            VillagerBreedingSessions.clearLevel(level);
        }
    }

    /** Hunger charged to each parent after a successful birth. */
    private static final int BREEDING_HUNGER_COST = 10;

    /** Hunger assigned to newborns. */
    private static final int MAX_HUNGER = 20;

    /** Handles successful baby-entity spawn events for villager births. */
    @SubscribeEvent
    public static void onBabySpawn(BabyEntitySpawnEvent event) {
        EmeraldCapitalism.LOGGER.debug(
                "BabyEntitySpawnEvent fired: parentAType={} parentBType={} childType={} canceled={}",
                event.getParentA().getType().toShortString(),
                event.getParentB().getType().toShortString(),
                event.getChild() == null ? "null" : event.getChild().getType().toShortString(),
                event.isCanceled());

        // Canceled spawns must not commit family or hunger side effects.
        if (event.isCanceled()) {
            EmeraldCapitalism.LOGGER.debug("Skipping villager breeding side effects because BabyEntitySpawnEvent was canceled");
            return;
        }

        if (!(event.getChild() instanceof Villager childVillager)) {
            return;
        }

        Mob parentA = event.getParentA();
        Mob parentB = event.getParentB();

        if (!(parentA instanceof Villager parent1) || !(parentB instanceof Villager parent2)) {
            EmeraldCapitalism.LOGGER.debug(
                    "Skipping BabyEntitySpawnEvent bookkeeping because parents are not both villagers: parentA={} parentB={}",
                    parentA.getType().toShortString(),
                    parentB.getType().toShortString());
            return;
        }

        applySuccessfulVillagerBirth(parent1, parent2, childVillager);
    }

    /** Applies family metadata and post-birth side effects for a successful birth. */
    public static void applySuccessfulVillagerBirth(Villager parent1, Villager parent2, Villager childVillager) {
        java.util.UUID childUUID = childVillager.getUUID();

        EmeraldCapitalism.LOGGER.debug(
                "Recording successful villager birth: parent1={} parent2={} child={} childAge={} childIsBaby={}",
                parent1.getUUID(), parent2.getUUID(), childUUID, childVillager.getAge(), childVillager.isBaby());

        VillagerStatsAttachment parent1Stats = parent1.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        VillagerStatsAttachment parent2Stats = parent2.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        VillagerStatsAttachment childStats = childVillager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);

        if (ZombieVirusEvents.getPhase(parent1) != 0 || ZombieVirusEvents.getPhase(parent2) != 0) {
            ZombieVirusEvents.infectPhaseOne(childVillager);
        }

        if (childStats.hasParents()) {
            EmeraldCapitalism.LOGGER.debug(
                    "Skipping villager birth bookkeeping for child {} because parent metadata already exists",
                    childUUID);
            return;
        }

        parent1Stats.addChild(childUUID);
        parent2Stats.addChild(childUUID);

        childStats.setParent1UUID(parent1.getUUID());
        childStats.setParent2UUID(parent2.getUUID());

        // Spawn-egg births can bypass VillagerMakeLoveMixin, so resolve parent names here.
        VillagerNameManager.assignNameIfNeeded(parent1);
        VillagerNameManager.assignNameIfNeeded(parent2);

        // Parent names are durable display data for the child.
        childStats.setParent1Name(VillagerFamilyUtils.getVillagerDisplayName(parent1));
        childStats.setParent2Name(VillagerFamilyUtils.getVillagerDisplayName(parent2));

        if (parent1Stats.getParent1UUID() != null) {
            childStats.addGrandparent(parent1Stats.getParent1UUID());
        }
        if (parent1Stats.getParent2UUID() != null) {
            childStats.addGrandparent(parent1Stats.getParent2UUID());
        }
        if (parent2Stats.getParent1UUID() != null) {
            childStats.addGrandparent(parent2Stats.getParent1UUID());
        }
        if (parent2Stats.getParent2UUID() != null) {
            childStats.addGrandparent(parent2Stats.getParent2UUID());
        }

        childStats.setHungerLevel(MAX_HUNGER);

        transferBestFoodToChild(parent1, childVillager);
        transferBestFoodToChild(parent2, childVillager);

        // Hunger cost replaces vanilla inventory-food consumption.
        parent1Stats.decreaseHunger(BREEDING_HUNGER_COST);
        parent2Stats.decreaseHunger(BREEDING_HUNGER_COST);

        EmeraldCapitalism.LOGGER.debug("Villager born! Parents: {} (hunger now {}) and {} (hunger now {}), Child: {}",
                parent1.getUUID(), parent1Stats.getHungerLevel(),
                parent2.getUUID(), parent2Stats.getHungerLevel(), childUUID);
    }

    /** Transfers one selected food item when the child's inventory can accept it. */
    private static void transferBestFoodToChild(Villager parent, Villager child) {
        SimpleContainer parentInventory = parent.getInventory();
        SimpleContainer childInventory = child.getInventory();

        int bestSlot = VillagerFoodSelection.findBestFoodSlot(parentInventory);
        int bestNutrition = bestSlot < 0 ? -1
                : parentInventory.getItem(bestSlot).get(DataComponents.FOOD).nutrition();

        if (bestSlot >= 0) {
            ItemStack foodStack = parentInventory.getItem(bestSlot);
            
            ItemStack transferItem = foodStack.copy();
            transferItem.setCount(1);
            
            ItemStack remainder = childInventory.addItem(transferItem);

            if (remainder.isEmpty()) {
                // Remove from the parent only after the child accepted the item.
                foodStack.shrink(1);
                if (foodStack.isEmpty()) {
                    parentInventory.setItem(bestSlot, ItemStack.EMPTY);
                }

                EmeraldCapitalism.LOGGER.debug("Transferred {} (nutrition: {}) from parent {} to child",
                        transferItem.getItem().getDescriptionId(), bestNutrition, parent.getUUID());
            } else {
                EmeraldCapitalism.LOGGER.debug("Skipped food transfer from parent {} to child due to full inventory",
                        parent.getUUID());
            }
        }
    }

}
