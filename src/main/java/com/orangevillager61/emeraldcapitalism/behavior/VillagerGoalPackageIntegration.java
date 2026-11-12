package com.orangevillager61.emeraldcapitalism.behavior;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.RunOne;
import net.minecraft.world.entity.ai.behavior.WorkAtPoi;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;

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
    /** Vanilla's WORK package runs its work-at-poi gate at this priority. */
    private static final int VANILLA_WORK_PRIORITY = 5;

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
        return ImmutableList.copyOf(original);
    }

    public static ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>> addMeetBehaviors(
            List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> original) {
        return ImmutableList.copyOf(original);
    }

    public static ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>> addWorkBehaviors(
            List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> original) {
        List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> modified = new ArrayList<>(original);
        addIfMissing(modified, BegForFoodBehavior.class, BEGGING_PRIORITY, BegForFoodBehavior::new);
        return ImmutableList.copyOf(modified);
    }

    /**
     * Work package for {@code profession}. A banker's work side is held by
     * {@link BankerWorkSideBehavior}; vanilla's job-site {@code RunOne} (strolling, which
     * overwrites {@code WALK_TARGET} at any priority) is replaced by plain {@code WorkAtPoi},
     * so the work-side behavior and vanilla never alternate the walk target.
     */
    public static ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>> addWorkBehaviors(
            VillagerProfession profession,
            List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> original) {
        ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>> common =
                addWorkBehaviors(original);
        if (profession != ECAPVillagerProfessions.BANKER.get()) {
            return common;
        }
        List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> modified = new ArrayList<>(common);
        removeJobSiteGate(modified);
        addIfMissing(modified, WorkAtPoi.class, VANILLA_WORK_PRIORITY, WorkAtPoi::new);
        addIfMissing(modified, BankerWorkSideBehavior.class,
                BankerWorkSideBehavior.PRIORITY, BankerWorkSideBehavior::new);
        return ImmutableList.copyOf(modified);
    }

    /**
     * Vanilla's work package holds two {@code RunOne} gates: the minimal look-around gate first,
     * then the job-site gate (work, strolling, farming). Only the second is removed. Any other
     * shape, including a package already stripped, is left alone and caught by the tracked
     * package-contents test.
     */
    private static void removeJobSiteGate(
            List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> behaviors) {
        int gates = 0;
        for (int index = 0; index < behaviors.size(); index++) {
            if (behaviors.get(index).getSecond() instanceof RunOne && ++gates == 2) {
                behaviors.remove(index);
                return;
            }
        }
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
