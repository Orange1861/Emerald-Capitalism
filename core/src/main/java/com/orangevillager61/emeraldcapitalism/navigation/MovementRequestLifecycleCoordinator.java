package com.orangevillager61.emeraldcapitalism.navigation;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Exactly-once lifecycle accounting for transient movement requests.
 *
 * <p>This class deliberately has no Minecraft or event-bus dependency. The
 * server adapter registers a generation when it publishes it and commits one
 * terminal reason when the generation stops owning execution. A late callback
 * can therefore be safely ignored without changing the terminal counters.</p>
 */
public final class MovementRequestLifecycleCoordinator {

    public enum TerminalReason {
        ARRIVED,
        ABANDONED,
        FAILED,
        PREEMPTED,
        REPLACED,
        UNLOADED,
        SERVER_SHUTDOWN
    }

    public record RequestKey(String owner, long generation) {
        public RequestKey {
            Objects.requireNonNull(owner, "owner");
            if (owner.isBlank()) {
                throw new IllegalArgumentException("owner must not be blank");
            }
            if (generation <= 0L) {
                throw new IllegalArgumentException("generation must be positive");
            }
        }
    }

    private final Map<RequestKey, Boolean> active = new HashMap<>();
    private final Map<RequestKey, TerminalReason> terminal = new HashMap<>();
    private final EnumMap<TerminalReason, Long> terminalCounts =
            new EnumMap<>(TerminalReason.class);

    public MovementRequestLifecycleCoordinator() {
        for (TerminalReason reason : TerminalReason.values()) {
            terminalCounts.put(reason, 0L);
        }
    }

    /** Registers a newly published generation. Duplicate registration is rejected. */
    public boolean register(String owner, long generation) {
        return register(new RequestKey(owner, generation));
    }

    public boolean register(RequestKey key) {
        Objects.requireNonNull(key, "key");
        if (active.containsKey(key) || terminal.containsKey(key)) {
            return false;
        }
        active.put(key, Boolean.TRUE);
        return true;
    }

    /**
     * Commits a terminal reason once. Returns false for unknown or already
     * terminal generations, which is the intended result for delayed cleanup.
     */
    public boolean terminate(String owner, long generation, TerminalReason reason) {
        return terminate(new RequestKey(owner, generation), reason);
    }

    public boolean terminate(RequestKey key, TerminalReason reason) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(reason, "reason");
        if (!active.containsKey(key) || terminal.containsKey(key)) {
            return false;
        }
        active.remove(key);
        terminal.put(key, reason);
        terminalCounts.compute(reason, (ignored, count) -> count + 1L);
        return true;
    }

    public boolean isActive(String owner, long generation) {
        return active.containsKey(new RequestKey(owner, generation));
    }

    public int activeCount() {
        return active.size();
    }

    public long terminalCount(TerminalReason reason) {
        return terminalCounts.get(Objects.requireNonNull(reason, "reason"));
    }

    public int terminalRequestCount() {
        return terminal.size();
    }

    /** Clears the session boundary; a new server session starts fresh. */
    public void clear() {
        active.clear();
        terminal.clear();
        for (TerminalReason reason : TerminalReason.values()) {
            terminalCounts.put(reason, 0L);
        }
    }
}
