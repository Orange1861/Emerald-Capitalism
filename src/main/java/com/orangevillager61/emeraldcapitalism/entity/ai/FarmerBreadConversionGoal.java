package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumSet;

/** Lets farmers craft complete three-wheat recipes from their own inventory. */
public final class FarmerBreadConversionGoal extends Goal {

    public static final int GOAL_PRIORITY = 2;
    private static final int WHEAT_PER_BREAD = 3;

    private final Villager farmer;
    private boolean finished;

    public FarmerBreadConversionGoal(Villager farmer) {
        this.farmer = farmer;
        setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        return farmer.level() instanceof ServerLevel
                && farmer.getVillagerData().getProfession() == VillagerProfession.FARMER
                && !farmer.isBaby()
                && !farmer.isSleeping()
                && !farmer.isTrading()
                && !VillagerBreedingSessions.shouldYieldCustomWork(farmer)
                && wheatCount() >= WHEAT_PER_BREAD
                && breadCapacity() > 0;
    }

    @Override
    public boolean canContinueToUse() {
        return !finished;
    }

    @Override
    public void start() {
        finished = false;
    }

    @Override
    public void tick() {
        int breadCount = Math.min(wheatCount() / WHEAT_PER_BREAD, breadCapacity());
        if (breadCount <= 0) {
            finished = true;
            return;
        }

        removeWheat(breadCount * WHEAT_PER_BREAD);
        ItemStack remainder = farmer.getInventory().addItem(new ItemStack(Items.BREAD, breadCount));
        if (!remainder.isEmpty()) {
            // Capacity was preflighted, but restore the input if another inventory
            // mutation raced this goal between the check and insertion.
            farmer.getInventory().addItem(new ItemStack(Items.WHEAT,
                    breadCount * WHEAT_PER_BREAD));
        }
        finished = true;
    }

    @Override
    public void stop() {
        finished = false;
    }

    private int wheatCount() {
        int count = 0;
        for (int slot = 0; slot < farmer.getInventory().getContainerSize(); slot++) {
            ItemStack stack = farmer.getInventory().getItem(slot);
            if (stack.is(Items.WHEAT)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int breadCapacity() {
        int capacity = 0;
        for (int slot = 0; slot < farmer.getInventory().getContainerSize(); slot++) {
            ItemStack stack = farmer.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                capacity += Items.BREAD.getDefaultMaxStackSize();
            } else if (stack.is(Items.BREAD)) {
                capacity += stack.getMaxStackSize() - stack.getCount();
            }
        }
        return capacity;
    }

    private void removeWheat(int amount) {
        int remaining = amount;
        for (int slot = 0; slot < farmer.getInventory().getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = farmer.getInventory().getItem(slot);
            if (!stack.is(Items.WHEAT)) {
                continue;
            }
            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            remaining -= removed;
            if (stack.isEmpty()) {
                farmer.getInventory().setItem(slot, ItemStack.EMPTY);
            }
        }
    }
}
