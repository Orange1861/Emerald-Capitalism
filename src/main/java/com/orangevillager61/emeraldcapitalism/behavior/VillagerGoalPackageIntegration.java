package com.orangevillager61.emeraldcapitalism.behavior;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.npc.Villager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Owns the mod's additions to the vanilla 1.21.1 villager goal packages.
 *
 * <p>NeoForge 21.1.219 has no public event for changing these static package
 * factories, so the version-sensitive mixin delegates here. Keeping package
 * composition outside the mixin preserves behavior order and priorities.</p>
 */
public final class VillagerGoalPackageIntegration {

    public static final int BEGGING_PRIORITY = 5;
    public static final int BOAT_AVOIDANCE_PRIORITY = 5;
    public static final int ZOMBIE_PLAGUE_AVOIDANCE_PRIORITY = 4;
    public static final int ZOMBIE_SMELL_PRIORITY = 1;
    public static final int HOSTILE_VILLAGE_PLAYER_FLEE_PRIORITY = 2;
    public static final int FENCE_GATE_PRIORITY = 6;

    private VillagerGoalPackageIntegration() {
    }

    public static ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>> addCoreBehaviors(
            List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> original) {
        List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> modified = new ArrayList<>(original);
        addIfMissing(modified, UseZombieSmellBehavior.class,
                ZOMBIE_SMELL_PRIORITY, UseZombieSmellBehavior::new);
        addIfMissing(modified, AvoidZombiePlagueBehavior.class,
                ZOMBIE_PLAGUE_AVOIDANCE_PRIORITY, AvoidZombiePlagueBehavior::new);
        addIfMissing(modified, FleeHostileVillagePlayerBehavior.class,
                HOSTILE_VILLAGE_PLAYER_FLEE_PRIORITY, FleeHostileVillagePlayerBehavior::new);
        addIfMissing(modified, InteractWithFenceGateBehavior.class, FENCE_GATE_PRIORITY,
                InteractWithFenceGateBehavior::new);
        addIfMissing(modified, AvoidBoatBehavior.class, BOAT_AVOIDANCE_PRIORITY, AvoidBoatBehavior::new);
        return ImmutableList.copyOf(modified);
    }

    public static ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>> addIdleBehaviors(
            List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> original) {
        return addBeggingBehavior(original);
    }

    public static ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>> addMeetBehaviors(
            List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> original) {
        return addBeggingBehavior(original);
    }

    private static ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>> addBeggingBehavior(
            List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> original) {
        List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> modified = new ArrayList<>(original);
        addIfMissing(modified, BegForFoodBehavior.class, BEGGING_PRIORITY, BegForFoodBehavior::new);
        return ImmutableList.copyOf(modified);
    }

    private static void addIfMissing(
            List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> behaviors,
            Class<? extends BehaviorControl<?>> behaviorType,
            int priority,
            Supplier<? extends BehaviorControl<? super Villager>> factory) {
        boolean alreadyPresent = behaviors.stream()
                .anyMatch(entry -> entry.getSecond().getClass() == behaviorType);
        if (!alreadyPresent) {
            behaviors.add(Pair.of(priority, factory.get()));
        }
    }
}
