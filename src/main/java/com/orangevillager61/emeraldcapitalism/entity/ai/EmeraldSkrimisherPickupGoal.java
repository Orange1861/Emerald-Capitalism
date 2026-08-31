package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.entity.EmeraldSkrimisher;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.Comparator;
import java.util.EnumSet;

/** Finds nearby wanted item entities and walks the Emerald Skrimisher to them. */
public final class EmeraldSkrimisherPickupGoal extends Goal {

    private static final double SEARCH_RADIUS = 16.0D;
    private static final int SEARCH_INTERVAL_TICKS = 5;
    private static final double PICKUP_DISTANCE_SQR = 4.0D;
    private static final double SPEED = 0.7D;

    private final EmeraldSkrimisher skrimisher;
    private ItemEntity targetItem;
    private long nextSearchTick;

    public EmeraldSkrimisherPickupGoal(EmeraldSkrimisher skrimisher) {
        this.skrimisher = skrimisher;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (skrimisher.level().isClientSide() || skrimisher.getTarget() != null) {
            return false;
        }
        long gameTime = skrimisher.level().getGameTime();
        if (gameTime < nextSearchTick) {
            return false;
        }
        nextSearchTick = gameTime + SEARCH_INTERVAL_TICKS;
        targetItem = findNearestWantedItem();
        return targetItem != null;
    }

    @Override
    public boolean canContinueToUse() {
        return targetItem != null
                && targetItem.isAlive()
                && skrimisher.getTarget() == null
                && skrimisher.wantsToPickUp(targetItem.getItem());
    }

    @Override
    public void start() {
        moveToTarget();
    }

    @Override
    public void tick() {
        if (targetItem == null) {
            return;
        }

        if (skrimisher.distanceToSqr(targetItem) <= PICKUP_DISTANCE_SQR) {
            skrimisher.pickUpItemForGoal(targetItem);
            if (!targetItem.isAlive() || !skrimisher.wantsToPickUp(targetItem.getItem())) {
                return;
            }
        }

        if (skrimisher.getNavigation().isDone()) {
            moveToTarget();
        }
    }

    @Override
    public void stop() {
        skrimisher.getNavigation().stop();
        targetItem = null;
    }

    private void moveToTarget() {
        if (targetItem != null) {
            skrimisher.getNavigation().moveTo(
                    targetItem.getX(), targetItem.getY(), targetItem.getZ(), SPEED);
        }
    }

    private ItemEntity findNearestWantedItem() {
        return skrimisher.level().getEntitiesOfClass(
                        ItemEntity.class,
                        skrimisher.getBoundingBox().inflate(SEARCH_RADIUS),
                        item -> item.isAlive()
                                && !item.hasPickUpDelay()
                                && skrimisher.wantsToPickUp(item.getItem()))
                .stream()
                .min(Comparator.comparingDouble(skrimisher::distanceToSqr))
                .orElse(null);
    }
}
