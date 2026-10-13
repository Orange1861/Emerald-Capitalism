package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.pathfinder.Path;

/** Bounded transition diagnostics; it is enabled with a JVM system property. */
final class MovementDiagnostics {

    private MovementDiagnostics() {
    }

    static void transition(Villager villager, MovementRequestOutcome outcome,
                           MovementRequestHandle handle, MovementRequestSpec spec) {
        if (enabled()) {
            EmeraldCapitalism.LOGGER.info(
                    "[ECAP][Navigation] transition entity={} owner={} generation={} outcome={} destination={} speed={} arrival={} traversal={}",
                    villager.getUUID(), handle.owner(), handle.generation(), outcome,
                    spec.destination(), spec.speedModifier(), spec.arrivalPolicy(), spec.traversalPolicy());
        }
    }

    static void cleanup(Villager villager, MovementRequestHandle handle) {
        if (enabled()) {
            EmeraldCapitalism.LOGGER.info(
                    "[ECAP][Navigation] cleanup entity={} owner={} generation={}",
                    villager.getUUID(), handle.owner(), handle.generation());
        }
    }

    static void route(Villager villager, MovementRequestHandle handle,
                      int routeGeneration, Path path) {
        if (enabled()) {
            EmeraldCapitalism.LOGGER.info(
                    "[ECAP][Navigation] route entity={} owner={} requestGeneration={} routeGeneration={} state={} pathId={}",
                    villager.getUUID(), handle.owner(), handle.generation(), routeGeneration,
                    path == null ? "terminated" : "planned", path == null ? 0 : System.identityHashCode(path));
        }
    }

    private static boolean enabled() {
        return Boolean.getBoolean("emeraldcapitalism.navigationDiagnostics");
    }
}
