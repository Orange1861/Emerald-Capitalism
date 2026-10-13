package com.orangevillager61.emeraldcapitalism.behavior;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Stable, shared definition for one internal villager behavior. */
public record VillagerBehaviorDefinition(
        String id,
        int priority,
        Predicate<Villager> eligibility,
        Set<Goal.Flag> requiredControls,
        Set<MemoryModuleType<?>> ownedMemories,
        InterruptionPolicy interruptionPolicy,
        VillagerBehaviorLifecycle lifecycle) {

    public VillagerBehaviorDefinition {
        id = requireText(id, "id");
        if (priority < 0) {
            throw new IllegalArgumentException("priority must be non-negative");
        }
        eligibility = Objects.requireNonNull(eligibility, "eligibility");
        requiredControls = copyControls(requiredControls);
        ownedMemories = Set.copyOf(Objects.requireNonNull(ownedMemories, "ownedMemories"));
        interruptionPolicy = Objects.requireNonNull(interruptionPolicy, "interruptionPolicy");
        lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Set<Goal.Flag> copyControls(Set<Goal.Flag> controls) {
        Objects.requireNonNull(controls, "requiredControls");
        return controls.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(controls));
    }

    public enum InterruptionPolicy {
        RESTART,
        RESUME_IF_ELIGIBLE,
        CANCEL
    }
}
