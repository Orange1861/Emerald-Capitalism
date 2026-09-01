package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import com.orangevillager61.emeraldcapitalism.util.VillagerFoodSelection;
import com.orangevillager61.emeraldcapitalism.villager.HungerPolicy;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Server-side villager hunger, eating, healing, and starvation handling. */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public class VillagerHungerEvents {

    private static final int EATING_PARTICLE_COUNT = 2;

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }

        if (villager.level().isClientSide) {
            return;
        }

        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);

        if (stats.isEating()) {
            boolean hasBreedTarget = villager.getBrain().hasMemoryValue(MemoryModuleType.BREED_TARGET);

            // Use the responsive stagger for bed wakeups while an eating animation is active.
            if ((villager.tickCount + villager.getId()) % HungerPolicy.RESPONSIVE_UPDATE_INTERVAL == 0
                    && villager.isSleeping()) {
                villager.stopSleeping();
            }

            // Vanilla breeding still checks inventory food; do not consume food while courting.
            if (hasBreedTarget) {
                stats.resetEating();
                return;
            }
            tickEatingAnimation((ServerLevel) villager.level(), villager, stats);
            return;
        }

        // A preflight-approved breeding session owns hunger until birth commits.
        if (VillagerBreedingSessions.isActive(villager)) {
            return;
        }

        // Stagger responsive non-eating updates by entity ID to distribute work.
        if ((villager.tickCount + villager.getId()) % HungerPolicy.RESPONSIVE_UPDATE_INTERVAL != 0) {
            return;
        }

        boolean hasBreedTarget = villager.getBrain().hasMemoryValue(MemoryModuleType.BREED_TARGET);
        boolean isWounded = villager.getHealth() < villager.getMaxHealth();
        boolean hungryEnoughToEat = HungerPolicy.shouldEat(
                stats.getHungerLevel(), isWounded, hasBreedTarget);

        // Wake hungry villagers so the next AI tick can route them to food.
        if (villager.isSleeping() && hungryEnoughToEat) {
            villager.stopSleeping();
        }

        if (hungryEnoughToEat) {
            tryStartEating(villager, stats);
        }

        if (HungerPolicy.shouldHeal(stats.getHungerLevel(), isWounded)) {
            int healTicks = stats.getTicksSinceLastHeal() + HungerPolicy.RESPONSIVE_UPDATE_INTERVAL;
            
            if (healTicks >= HungerPolicy.TICKS_PER_HEAL) {
                villager.heal(1.0F);
                stats.decreaseHunger(1); // Healing consumes hunger
                stats.setTicksSinceLastHeal(0);
            } else {
                stats.setTicksSinceLastHeal(healTicks);
            }
        } else {
            stats.setTicksSinceLastHeal(0);
        }

        if ((villager.tickCount + villager.getId())
                % HungerPolicy.HUNGER_DECREASE_UPDATE_INTERVAL == 0) {
            int currentTicks = stats.getTicksSinceLastHungerDecrease()
                    + HungerPolicy.HUNGER_DECREASE_UPDATE_INTERVAL;

            if (currentTicks >= HungerPolicy.TICKS_PER_HUNGER_DECREASE) {
                stats.decreaseHunger(1);
                stats.setTicksSinceLastHungerDecrease(0);
            } else {
                stats.setTicksSinceLastHungerDecrease(currentTicks);
            }
        }

        if (stats.isStarving()) {
            boolean canTakeDamage = Config.villagersCanStarveToDeath || villager.getHealth() > 2.0F;
            if (canTakeDamage) {
                int starvationTicks = stats.getTicksSinceLastStarvationDamage()
                        + HungerPolicy.RESPONSIVE_UPDATE_INTERVAL;
                
                if (starvationTicks >= HungerPolicy.TICKS_PER_STARVATION_DAMAGE) {
                    com.orangevillager61.emeraldcapitalism.util.EntityDamageUtils.hurt(
                            villager, villager.damageSources().starve(), 1.0F);
                    stats.setTicksSinceLastStarvationDamage(0);
                } else {
                    stats.setTicksSinceLastStarvationDamage(starvationTicks);
                }
            }
        } else {
            stats.setTicksSinceLastStarvationDamage(0);
        }
    }

    /** Starts eating without consuming the item until the animation completes. */
    private static void tryStartEating(Villager villager, VillagerStatsAttachment stats) {
        var inventory = villager.getInventory();

        int cachedSlot = stats.getCachedFoodSlot();
        if (cachedSlot >= 0 && cachedSlot < inventory.getContainerSize()) {
            ItemStack cachedStack = inventory.getItem(cachedSlot);
            FoodProperties cachedFood = cachedStack.get(DataComponents.FOOD);
            if (cachedFood != null && !cachedStack.isEmpty()
                    && !VillagerFoodSelection.isLastChoice(cachedStack)) {
                stats.startEating(cachedStack, cachedSlot, cachedFood.nutrition(),
                        HungerPolicy.EATING_DURATION_TICKS);
                return;
            }
            stats.setCachedFoodSlot(-1);
        }

        int foodSlot = VillagerFoodSelection.findBestFoodSlot(inventory);
        if (foodSlot >= 0) {
            ItemStack stack = inventory.getItem(foodSlot);
            FoodProperties foodProperties = stack.get(DataComponents.FOOD);
            stats.startEating(stack, foodSlot, foodProperties.nutrition(),
                    HungerPolicy.EATING_DURATION_TICKS);
            stats.setCachedFoodSlot(foodSlot);
        }
    }

    /** Advances eating and commits nutrition when the animation completes. */
    private static void tickEatingAnimation(
            ServerLevel serverLevel, Villager villager, VillagerStatsAttachment stats) {
        int ticksRemaining = stats.getEatingTicksRemaining();
        ItemStack eatingItem = stats.getEatingItem();

        if (ticksRemaining % HungerPolicy.EATING_EFFECT_INTERVAL == 0 && !eatingItem.isEmpty()) {
            playEatingSound(villager);
            spawnEatingParticles(serverLevel, villager, eatingItem);
        }

        boolean finished = stats.tickEating();

        if (finished) {
            finishEating(villager, stats);
        }
    }

    /** Plays the server-side eating sound. */
    private static void playEatingSound(Villager villager) {
        RandomSource random = villager.getRandom();
        float pitch = 0.8F + random.nextFloat() * 0.4F;
        
        villager.playSound(
//? if >=1.21.4 {
                SoundEvents.GENERIC_EAT.value(),
//?} else {
/*                SoundEvents.GENERIC_EAT,
 *///?}
                0.5F + 0.5F * random.nextFloat(),
                pitch
        );
    }

    /** Emits item particles from the villager's mouth. */
    private static void spawnEatingParticles(ServerLevel serverLevel, Villager villager, ItemStack foodItem) {
        RandomSource random = villager.getRandom();
        Vec3 lookVec = villager.getLookAngle();

        double mouthHeight = villager.getEyeY() - 0.2;
        double x = villager.getX() + lookVec.x * 0.5;
        double y = mouthHeight;
        double z = villager.getZ() + lookVec.z * 0.5;

        for (int i = 0; i < EATING_PARTICLE_COUNT; i++) {
            double offsetX = (random.nextDouble() - 0.5) * 0.3;
            double offsetY = random.nextDouble() * 0.1;
            double offsetZ = (random.nextDouble() - 0.5) * 0.3;

            double velX = (random.nextDouble() - 0.5) * 0.1;
            double velY = random.nextDouble() * 0.05 + 0.02;
            double velZ = (random.nextDouble() - 0.5) * 0.1;

            serverLevel.sendParticles(
                    new ItemParticleOption(ParticleTypes.ITEM, foodItem),
                    x + offsetX,
                    y + offsetY,
                    z + offsetZ,
                    0, // count (0 = use velocity)
                    velX,
                    velY,
                    velZ,
                    0.1 // speed multiplier
            );
        }
    }

    /** Applies nutrition and consumes the originally selected stack when still present. */
    private static void finishEating(Villager villager, VillagerStatsAttachment stats) {
        int slot = stats.getEatingSlot();
        // Capture the item before finishEating() clears the animation state.
        ItemStack expectedItem = stats.getEatingItem().copy();
        int nutrition = stats.finishEating();

        // Validate identity before consuming; inventory contents may change mid-animation.
        var inventory = villager.getInventory();
        boolean consumed = false;
        if (slot >= 0 && slot < inventory.getContainerSize()) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, expectedItem)) {
                stack.shrink(1);
                inventory.setItem(slot, stack);
                if (stack.isEmpty()) {
                    stats.setCachedFoodSlot(-1);
                }
                consumed = true;
            }
        }
        // If the item moved, consume the matching stack rather than the new slot occupant.
        if (!consumed && !expectedItem.isEmpty()) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                if (i == slot) continue;
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, expectedItem)) {
                    stack.shrink(1);
                    inventory.setItem(i, stack);
                    if (stack.isEmpty()) {
                        stats.setCachedFoodSlot(-1);
                    }
                    consumed = true;
                    break;
                }
            }
        }
        if (!consumed) {
            stats.setCachedFoodSlot(-1);
            return;
        }

        stats.increaseHunger(nutrition);
        stats.setLastAteTime(villager.level().getGameTime());

        villager.playSound(
                SoundEvents.PLAYER_BURP,
                0.5F,
                villager.getRandom().nextFloat() * 0.1F + 0.9F
        );

    }
}
