package com.orangevillager61.emeraldcapitalism.behavior;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import com.orangevillager61.emeraldcapitalism.util.VillagerFoodSelection;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Optional;

/** Brain behavior that lets starving adult villagers seek nearby food donors. */
public class BegForFoodBehavior extends Behavior<Villager> {

    private static final int MAX_ITEMS_TO_SHARE = 5;

    private static final int DONOR_MIN_HUNGER = 10;

    private static final float MAX_DISTANCE = 16.0F;

    private static final float INTERACTION_DISTANCE = 3.0F;

    private static final float SPEED_MODIFIER = 0.6F;

    private static final int BEG_DURATION = 30;

    private static final int BEGGING_COOLDOWN = 600;

    public BegForFoodBehavior() {
        super(
            Map.of(
                MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES, MemoryStatus.VALUE_PRESENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED
            ),
            60,  // min duration
            120  // max duration
        );
    }

    @Override
    // @Nonnull annotations align with ParametersAreNonnullByDefault on the base Behavior methods.
    protected boolean checkExtraStartConditions(@Nonnull ServerLevel level, @Nonnull Villager villager) {
        if (VillagerBreedingSessions.shouldYieldCustomWork(villager)) {
            return false;
        }
        if (villager.isBaby()) {
            return false;
        }

        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);

        if (level.getGameTime() - stats.getLastBegTime() < BEGGING_COOLDOWN) {
            return false;
        }

        if (!stats.isStarving()) {
            return false;
        }

        // Let normal eating handle stocked villagers to avoid donor ping-pong.
        if (countTotalFood(villager.getInventory()) > 0) {
            return false;
        }

        Optional<Villager> donor = findDonorVillager(villager);
        if (donor.isEmpty()) {
            return false;
        }

        stats.setBegDonorUUID(donor.get().getUUID());
        return true;
    }

    @Override
    protected void start(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        stats.setBegTime(0);
        Villager donorVillager = resolveDonor(level, stats);
        if (donorVillager == null) {
            stats.resetBeggingState();
            this.doStop(level, villager, gameTime);
            return;
        }
        
        Brain<?> brain = villager.getBrain();
        brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(donorVillager, true));
        
        double distSq = villager.distanceToSqr(donorVillager);
        if (distSq > INTERACTION_DISTANCE * INTERACTION_DISTANCE) {
            BehaviorUtils.setWalkAndLookTargetMemories(villager, donorVillager, SPEED_MODIFIER, 1);
        }
    }

    @Override
    protected void tick(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        Villager donorVillager = resolveDonor(level, stats);
        if (donorVillager == null || !donorVillager.isAlive()) {
            stats.resetBeggingState();
            this.doStop(level, villager, gameTime);
            return;
        }

        Brain<?> brain = villager.getBrain();
        brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(donorVillager, true));

        if (!canDonate(donorVillager)) {
            this.doStop(level, villager, gameTime);
            return;
        }

        double distSq = villager.distanceToSqr(donorVillager);

        if (distSq <= INTERACTION_DISTANCE * INTERACTION_DISTANCE) {
            stats.setBegTime(stats.getBegTime() + 1);
            
            donorVillager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(villager, true));
            
            if (stats.getBegTime() >= BEG_DURATION) {
                transferFoodFromDonor(donorVillager, villager);
                stats.setLastBegTime(gameTime);
                this.doStop(level, villager, gameTime);
            }
        } else {
            stats.setBegTime(0);
            BehaviorUtils.setWalkAndLookTargetMemories(villager, donorVillager, SPEED_MODIFIER, 1);
        }
    }

    @Override
    protected void stop(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        stats.resetBeggingState();
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    }

    @Override
    protected boolean canStillUse(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        if (VillagerBreedingSessions.shouldYieldCustomWork(villager)) {
            return false;
        }
        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        if (!stats.isStarving()) {
            return false;
        }

        Villager donorVillager = resolveDonor(level, stats);
        return donorVillager != null 
            && donorVillager.isAlive() 
            && canDonate(donorVillager)
            && villager.distanceToSqr(donorVillager) < MAX_DISTANCE * MAX_DISTANCE;
    }

    /** Resolves a visible nearby donor within the configured search radius. */
    private Optional<Villager> findDonorVillager(Villager beggar) {
        Brain<?> brain = beggar.getBrain();
        Optional<NearestVisibleLivingEntities> nearby = brain.getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES);
        
        return nearby.flatMap(entities -> entities
            .findClosest(entity ->
                entity instanceof Villager v
                    && v != beggar
                    && !v.isBaby()
                    && canDonate(v)
                    && beggar.distanceToSqr(v) < MAX_DISTANCE * MAX_DISTANCE
            )
            .map(entity -> (Villager) entity));
    }

    private Villager resolveDonor(ServerLevel level, VillagerStatsAttachment stats) {
        if (stats.getBegDonorUUID() == null) {
            return null;
        }
        return level.getEntity(stats.getBegDonorUUID()) instanceof Villager villager ? villager : null;
    }

    /** Returns whether a villager can donate under the shared-food constraints. */
    private boolean canDonate(Villager villager) {
        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        if (stats.isStarving() || stats.getHungerLevel() < DONOR_MIN_HUNGER) {
            return false;
        }
        return countTotalFood(villager.getInventory()) >= 2;
    }

    /** Transfers the bounded share of the donor's highest-priority food. */
    private void transferFoodFromDonor(Villager donor, Villager beggar) {
        SimpleContainer inventory = donor.getInventory();
        
        int totalFoodCount = countTotalFood(inventory);
        if (totalFoodCount <= 0) {
            return;
        }
        
        int maxToGive = Math.min(MAX_ITEMS_TO_SHARE, totalFoodCount / 2);
        
        if (maxToGive == 0) {
            return;
        }
        
        int itemsGiven = 0;
        
        while (itemsGiven < maxToGive) {
            int foodSlot = findBestFoodSlot(inventory);
            if (foodSlot < 0) {
                break;
            }
            
            ItemStack foodStack = inventory.getItem(foodSlot);
            int remainingToGive = maxToGive - itemsGiven;
            int countFromStack = Math.min(remainingToGive, foodStack.getCount());
            
            ItemStack thrownItem = foodStack.copy();
            thrownItem.setCount(countFromStack);
            
            foodStack.shrink(countFromStack);
            if (foodStack.isEmpty()) {
                inventory.setItem(foodSlot, ItemStack.EMPTY);
            }
            
            BehaviorUtils.throwItem(donor, thrownItem, beggar.position());
            
            itemsGiven += countFromStack;
        }

        EmeraldCapitalism.LOGGER.debug("Villager {} donated {} food items to starving villager {}",
                donor.getUUID(), itemsGiven, beggar.getUUID());
    }

    /** Counts food items, rather than their nutrition, in an inventory. */
    private int countTotalFood(SimpleContainer inventory) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                FoodProperties foodProps = stack.get(DataComponents.FOOD);
                if (foodProps != null) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    /** Selects the next food stack according to the sharing preference order. */
    private int findBestFoodSlot(SimpleContainer inventory) {
        return VillagerFoodSelection.findBestFoodSlot(inventory);
    }
}
