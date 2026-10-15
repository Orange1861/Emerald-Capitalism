package com.orangevillager61.emeraldcapitalism.behavior;

import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import net.minecraft.world.entity.npc.Villager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Deterministic internal composition registry for future villager AI work.
 * Definitions are shared; bindings and lifecycle state are per villager.
 */
public final class VillagerBehaviorRegistry {

    private static final Map<String, VillagerBehaviorDefinition> DEFINITIONS = new LinkedHashMap<>();
    private static final Map<Villager, Map<String, Binding>> BINDINGS = new WeakHashMap<>();
    private static volatile boolean definitionsPresent;
    private static volatile List<VillagerBehaviorDefinition> orderedDefinitions = List.of();
    private static final Comparator<VillagerBehaviorDefinition> PRIORITY_ORDER =
            Comparator.comparingInt(VillagerBehaviorDefinition::priority)
                    .reversed()
                    .thenComparing(VillagerBehaviorDefinition::id);

    private VillagerBehaviorRegistry() {
    }

    public static synchronized void register(VillagerBehaviorDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        VillagerBehaviorDefinition previous = DEFINITIONS.putIfAbsent(definition.id(), definition);
        if (previous != null && previous != definition) {
            throw new IllegalStateException("Behavior already registered: " + definition.id());
        }
        definitionsPresent = true;
        rebuildOrderedDefinitions();
    }

    public static synchronized void replace(VillagerBehaviorDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        VillagerBehaviorDefinition previous = DEFINITIONS.put(definition.id(), definition);
        definitionsPresent = true;
        rebuildOrderedDefinitions();
        for (Map.Entry<Villager, Map<String, Binding>> entry
                : new ArrayList<>(BINDINGS.entrySet())) {
            Villager villager = entry.getKey();
            Map<String, Binding> bindings = entry.getValue();
            Binding binding = bindings.remove(definition.id());
            if (binding != null) {
                // Replacing a live definition is a lifecycle transition, not
                // a map overwrite. Give the old definition a chance to stop
                // external work before the next refresh starts the new one.
                if (previous != null && !binding.canceled) {
                    previous.lifecycle().cancel(villager);
                }
            }
        }
    }

    public static synchronized void remove(String id) {
        Objects.requireNonNull(id, "id");
        VillagerBehaviorDefinition definition = DEFINITIONS.remove(id);
        if (definition == null) {
            return;
        }
        definitionsPresent = !DEFINITIONS.isEmpty();
        rebuildOrderedDefinitions();
        for (Map.Entry<Villager, Map<String, Binding>> entry
                : new ArrayList<>(BINDINGS.entrySet())) {
            Villager villager = entry.getKey();
            Map<String, Binding> bindings = entry.getValue();
            Binding binding = bindings.remove(id);
            if (binding != null && !binding.canceled) {
                definition.lifecycle().cancel(villager);
            }
        }
    }

    /** Reconciles the definitions with current profession/config eligibility. */
    public static void refresh(Villager villager) {
        PerformanceTimingCounters.measure(
                PerformanceTimingCounters.Operation.BEHAVIOR_REGISTRY_TIME,
                () -> refreshInternal(villager));
    }

    private static void refreshInternal(Villager villager) {
        Objects.requireNonNull(villager, "villager");
        Map<String, Binding> bindings = BINDINGS.computeIfAbsent(villager, ignored -> new LinkedHashMap<>());
        List<VillagerBehaviorDefinition> definitions = orderedDefinitions;
        for (VillagerBehaviorDefinition definition : definitions) {
            if (DEFINITIONS.get(definition.id()) != definition) {
                throw new IllegalStateException(
                        "Behavior definition changed during refresh: " + definition.id());
            }
            boolean eligible = definition.eligibility().test(villager);
            if (DEFINITIONS.get(definition.id()) != definition) {
                throw new IllegalStateException(
                        "Behavior eligibility changed its definition during refresh: "
                                + definition.id());
            }
            Binding current = bindings.get(definition.id());
            if (!eligible) {
                if (current != null) {
                    if (current.canceled) {
                        bindings.remove(definition.id(), current);
                    } else {
                        removeBinding(villager, bindings, definition, current);
                    }
                }
                continue;
            }
            if (current == null) {
                activate(villager, bindings, definition);
            } else if (!current.canceled && current.suspended) {
                resumeIfAvailable(villager, bindings, definition, current);
            }
        }
    }

    /** Advances bound behaviors without exposing registry-owned binding state. */
    public static void tick(Villager villager) {
        PerformanceTimingCounters.measure(
                PerformanceTimingCounters.Operation.BEHAVIOR_REGISTRY_TIME,
                () -> tickInternal(villager));
    }

    private static void tickInternal(Villager villager) {
        Objects.requireNonNull(villager, "villager");
        Map<String, Binding> bindings = BINDINGS.get(villager);
        if (bindings == null || bindings.isEmpty()) {
            return;
        }
        for (String id : List.copyOf(bindings.keySet())) {
            VillagerBehaviorDefinition definition = DEFINITIONS.get(id);
            Binding binding = bindings.get(id);
            if (definition == null || binding == null) {
                throw new IllegalStateException(
                        "Behavior binding has no matching definition: " + id);
            }
            if (!binding.suspended && !binding.canceled) {
                definition.lifecycle().tick(villager);
            }
        }
    }

    /** Finishes one active or suspended behavior and releases its binding. */
    public static synchronized boolean finish(Villager villager, String id) {
        Objects.requireNonNull(villager, "villager");
        Objects.requireNonNull(id, "id");
        Map<String, Binding> bindings = BINDINGS.get(villager);
        VillagerBehaviorDefinition definition = DEFINITIONS.get(id);
        Binding binding = bindings == null ? null : bindings.get(id);
        if (binding == null || definition == null || !isLive(binding)) {
            return false;
        }
        bindings.remove(id, binding);
        definition.lifecycle().finish(villager);
        return true;
    }

    public static synchronized boolean isRegistered(String id) {
        return DEFINITIONS.containsKey(Objects.requireNonNull(id, "id"));
    }

    /** Lock-free fast path for the per-villager tick hook. */
    public static boolean hasDefinitions() {
        return definitionsPresent;
    }

    public static synchronized int registrationCount() {
        return DEFINITIONS.size();
    }

    public static synchronized int bindingCount(Villager villager) {
        Objects.requireNonNull(villager, "villager");
        Map<String, Binding> bindings = BINDINGS.get(villager);
        if (bindings == null) {
            return 0;
        }
        int count = 0;
        for (Binding binding : bindings.values()) {
            if (!binding.canceled) {
                count++;
            }
        }
        return count;
    }

    /** Returns whether one specific definition is bound, without exposing mutable state. */
    public static synchronized boolean isBound(Villager villager, String id) {
        Objects.requireNonNull(villager, "villager");
        Objects.requireNonNull(id, "id");
        Map<String, Binding> bindings = BINDINGS.get(villager);
        Binding binding = bindings == null ? null : bindings.get(id);
        return binding != null && !binding.canceled;
    }

    public static synchronized void release(Villager villager) {
        Objects.requireNonNull(villager, "villager");
        Map<String, Binding> bindings = BINDINGS.remove(villager);
        if (bindings != null) {
            for (String id : new ArrayList<>(bindings.keySet())) {
                VillagerBehaviorDefinition definition = DEFINITIONS.get(id);
                Binding binding = bindings.get(id);
                if (definition == null || binding == null) {
                    throw new IllegalStateException(
                            "Behavior binding has no matching definition: " + id);
                }
                if (!binding.canceled) {
                    definition.lifecycle().cancel(villager);
                }
            }
        }
    }

    public static synchronized void clear() {
        clearBindings();
        DEFINITIONS.clear();
        definitionsPresent = false;
        orderedDefinitions = List.of();
    }

    /** Clears only per-villager lifecycle state; definitions survive a server restart. */
    public static synchronized void clearBindings() {
        List<Map.Entry<Villager, Map<String, Binding>>> entries =
                new ArrayList<>(BINDINGS.entrySet());
        BINDINGS.clear();
        for (Map.Entry<Villager, Map<String, Binding>> entry : entries) {
            Villager villager = entry.getKey();
            for (String id : new ArrayList<>(entry.getValue().keySet())) {
                VillagerBehaviorDefinition definition = DEFINITIONS.get(id);
                Binding binding = entry.getValue().get(id);
                if (definition == null || binding == null) {
                    throw new IllegalStateException(
                            "Behavior binding has no matching definition: " + id);
                }
                if (!binding.canceled) {
                    definition.lifecycle().cancel(villager);
                }
            }
        }
    }

    private static void activate(Villager villager,
                                 Map<String, Binding> bindings,
                                 VillagerBehaviorDefinition definition) {
        List<Binding> conflicts = activeConflicts(bindings, definition);
        for (Binding conflict : conflicts) {
            VillagerBehaviorDefinition existing = DEFINITIONS.get(conflict.id);
            if (existing == null) {
                throw new IllegalStateException(
                        "Behavior conflict definition disappeared during activation: "
                                + conflict.id);
            }
            if (existing.priority() >= definition.priority()) {
                // Registration order is deterministic for equal priorities;
                // the already-active behavior retains ownership.
                return;
            }
        }

        List<VillagerBehaviorLifecycle> suspended = new ArrayList<>();
        List<VillagerBehaviorLifecycle> canceled = new ArrayList<>();
        for (Binding conflict : conflicts) {
            VillagerBehaviorDefinition existing = DEFINITIONS.get(conflict.id);
            if (existing == null) {
                throw new IllegalStateException(
                        "Behavior conflict definition disappeared during activation: "
                                + conflict.id);
            }
            switch (existing.interruptionPolicy()) {
                case RESUME_IF_ELIGIBLE -> {
                    conflict.suspended = true;
                    suspended.add(existing.lifecycle());
                }
                case RESTART -> {
                    bindings.remove(conflict.id);
                    canceled.add(existing.lifecycle());
                }
                case CANCEL -> {
                    // CANCEL is sticky while eligibility remains true. It
                    // will not spring back merely because the preemptor is
                    // removed; the definition must become ineligible first.
                    conflict.canceled = true;
                    canceled.add(existing.lifecycle());
                }
            }
        }

        bindings.put(definition.id(), new Binding(definition.id(), false));
        for (VillagerBehaviorLifecycle lifecycle : suspended) {
            lifecycle.suspend(villager);
        }
        for (VillagerBehaviorLifecycle lifecycle : canceled) {
            lifecycle.cancel(villager);
        }
        definition.lifecycle().start(villager);
    }

    private static void resumeIfAvailable(Villager villager,
                                          Map<String, Binding> bindings,
                                          VillagerBehaviorDefinition definition,
                                          Binding binding) {
        if (!activeConflicts(bindings, definition).isEmpty()) {
            return;
        }
        binding.suspended = false;
        definition.lifecycle().resume(villager);
    }

    private static void removeBinding(Villager villager,
                                      Map<String, Binding> bindings,
                                      VillagerBehaviorDefinition definition,
                                      Binding binding) {
        if (bindings.remove(definition.id(), binding) && !binding.canceled) {
            definition.lifecycle().cancel(villager);
        }
    }

    private static List<Binding> activeConflicts(Map<String, Binding> bindings,
                                                 VillagerBehaviorDefinition definition) {
        List<Binding> conflicts = new ArrayList<>();
        for (Binding binding : new ArrayList<>(bindings.values())) {
            if (binding.suspended || binding.canceled) {
                continue;
            }
            VillagerBehaviorDefinition existing = DEFINITIONS.get(binding.id);
            if (existing == null) {
                throw new IllegalStateException(
                        "Behavior binding has no matching definition: " + binding.id);
            }
            if (conflicts(existing, definition)) {
                conflicts.add(binding);
            }
        }
        return conflicts;
    }

    private static void rebuildOrderedDefinitions() {
        ArrayList<VillagerBehaviorDefinition> ordered = new ArrayList<>(DEFINITIONS.values());
        ordered.sort(PRIORITY_ORDER);
        orderedDefinitions = List.copyOf(ordered);
    }

    private static boolean conflicts(VillagerBehaviorDefinition first,
                                     VillagerBehaviorDefinition second) {
        return !Collections.disjoint(first.requiredControls(), second.requiredControls())
                || !Collections.disjoint(first.ownedMemories(), second.ownedMemories());
    }

    private static boolean isLive(Binding binding) {
        return !binding.canceled;
    }

    private static final class Binding {
        private final String id;
        private boolean suspended;
        private boolean canceled;

        private Binding(String id, boolean suspended) {
            this.id = id;
            this.suspended = suspended;
        }
    }
}
