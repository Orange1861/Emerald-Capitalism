package com.orangevillager61.emeraldcapitalism.behavior;

import com.orangevillager61.emeraldcapitalism.item.RottenFleshCoverItem;
import com.orangevillager61.emeraldcapitalism.registry.ECAPEffects;
import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;

/** Makes a villager consume a cover when a zombie has a clear line of sight. */
public final class UseZombieSmellBehavior extends Behavior<Villager> {

    private static final double ZOMBIE_DETECTION_RANGE = 16.0D;
    private static final long ZOMBIE_LOOKUP_INTERVAL_TICKS = 5L;
    private static final long EMPTY_LOOKUP_INTERVAL_TICKS = 20L;
    private static final long COVER_LOOKUP_INTERVAL_TICKS = 20L;

    private UUID cachedVillagerId;
    private Zombie cachedZombie;
    private long nextZombieLookupTick = Long.MIN_VALUE;
    private ServerLevel cachedCoverLevel;
    private boolean cachedHasCover;
    private long nextCoverLookupTick = Long.MIN_VALUE;

    public UseZombieSmellBehavior() {
        super(Map.of(), 1, 1);
    }

    @Override
    protected boolean checkExtraStartConditions(@Nonnull ServerLevel level, @Nonnull Villager villager) {
        if (villager.getEffect(ECAPEffects.ZOMBIE_SMELL) != null
                || !hasCover(level, villager.getInventory())) {
            return false;
        }
        return findClearLineOfSightZombie(level, villager) != null;
    }

    @Override
    protected void start(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        SimpleContainer inventory = villager.getInventory();
        // Recheck the mutable inventory before consuming the item; the cached
        // result only throttles the behavior's eligibility scan.
        if (!hasCoverNow(inventory)) {
            cachedCoverLevel = level;
            cachedHasCover = false;
            nextCoverLookupTick = gameTime + COVER_LOOKUP_INTERVAL_TICKS;
            return;
        }

        inventory.removeItemType(ECAPItems.ROTTEN_FLESH_COVER.get(), 1);
        RottenFleshCoverItem.applyZombieSmell(villager);
        cachedCoverLevel = level;
        cachedHasCover = false;
        nextCoverLookupTick = gameTime + COVER_LOOKUP_INTERVAL_TICKS;
    }

    @Override
    protected boolean canStillUse(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        return false;
    }

    private Zombie findClearLineOfSightZombie(ServerLevel level, Villager villager) {
        long gameTime = level.getGameTime();
        if (villager.getUUID().equals(cachedVillagerId) && gameTime < nextZombieLookupTick) {
            return cachedZombie != null
                    && cachedZombie.isAlive()
                    && villager.distanceToSqr(cachedZombie) <= ZOMBIE_DETECTION_RANGE * ZOMBIE_DETECTION_RANGE
                    && cachedZombie.hasLineOfSight(villager)
                    ? cachedZombie
                    : null;
        }

        Zombie closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Zombie zombie : level.getEntitiesOfClass(
                Zombie.class,
                villager.getBoundingBox().inflate(ZOMBIE_DETECTION_RANGE),
                candidate -> candidate.isAlive() && candidate.hasLineOfSight(villager))) {
            double distance = villager.distanceToSqr(zombie);
            if (distance < closestDistance) {
                closest = zombie;
                closestDistance = distance;
            }
        }

        cachedVillagerId = villager.getUUID();
        cachedZombie = closest;
        nextZombieLookupTick = gameTime + (closest == null
                ? EMPTY_LOOKUP_INTERVAL_TICKS
                : ZOMBIE_LOOKUP_INTERVAL_TICKS);
        return closest;
    }

    private boolean hasCover(ServerLevel level, SimpleContainer inventory) {
        long gameTime = level.getGameTime();
        if (cachedCoverLevel == level && gameTime < nextCoverLookupTick) {
            return cachedHasCover;
        }

        cachedCoverLevel = level;
        cachedHasCover = hasCoverNow(inventory);
        nextCoverLookupTick = gameTime + COVER_LOOKUP_INTERVAL_TICKS;
        return cachedHasCover;
    }

    private static boolean hasCoverNow(SimpleContainer inventory) {
        return inventory.countItem(ECAPItems.ROTTEN_FLESH_COVER.get()) > 0;
    }
}
