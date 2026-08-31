package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;

/** Goal-only boat avoidance for wandering traders. It does not touch trade offers. */
public final class WanderingTraderAvoidBoatGoal extends Goal {

    private static final double DETECTION_RANGE = 8.0D;
    private static final double SAFE_DISTANCE = 4.5D;
    private static final double ESCAPE_DISTANCE = 4.0D;
    private final WanderingTrader trader;
    private Boat boat;

    public WanderingTraderAvoidBoatGoal(WanderingTrader trader) {
        this.trader = trader;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!Config.enableBoatAvoidance) {
            return false;
        }
        boat = findBoat();
        return boat != null && trader.distanceToSqr(boat) < SAFE_DISTANCE * SAFE_DISTANCE;
    }

    @Override
    public boolean canContinueToUse() {
        return Config.enableBoatAvoidance && boat != null && boat.isAlive()
                && trader.distanceToSqr(boat) < DETECTION_RANGE * DETECTION_RANGE;
    }

    @Override
    public void tick() {
        if (boat == null || !boat.isAlive()) {
            boat = findBoat();
        }
        if (boat == null) {
            return;
        }
        double awayX = trader.getX() - boat.getX();
        double awayZ = trader.getZ() - boat.getZ();
        double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (length < 0.001D) {
            awayX = trader.getRandom().nextDouble() - 0.5D;
            awayZ = trader.getRandom().nextDouble() - 0.5D;
            length = Math.sqrt(awayX * awayX + awayZ * awayZ);
        }
        BlockPos target = BlockPos.containing(
                trader.getX() + awayX / length * ESCAPE_DISTANCE,
                trader.getY(),
                trader.getZ() + awayZ / length * ESCAPE_DISTANCE);
        trader.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 0.7D);
        trader.getLookControl().setLookAt(boat);
    }

    @Override
    public void stop() {
        boat = null;
        trader.getNavigation().stop();
    }

    private Boat findBoat() {
        return trader.level().getEntitiesOfClass(
                        Boat.class, trader.getBoundingBox().inflate(DETECTION_RANGE), Boat::isAlive)
                .stream()
                .min((first, second) -> Double.compare(
                        trader.distanceToSqr(first), trader.distanceToSqr(second)))
                .orElse(null);
    }
}
