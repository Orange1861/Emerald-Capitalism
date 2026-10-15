package com.orangevillager61.emeraldcapitalism.navigation;

import java.util.EnumMap;
import java.util.LinkedHashMap;
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

    public record RequestKey(long sessionToken, String owner, long generation) {
        public RequestKey {
            if (sessionToken <= 0L) {
                throw new IllegalArgumentException("sessionToken must be positive");
            }
            Objects.requireNonNull(owner, "owner");
            if (owner.isBlank()) {
                throw new IllegalArgumentException("owner must not be blank");
            }
            if (generation <= 0L) {
                throw new IllegalArgumentException("generation must be positive");
            }
        }
    }

    /** Bounded duplicate-callback fence; session tokens are never reused. */
    public static final int DEFAULT_TERMINAL_HISTORY_LIMIT = 4_096;

    private final int terminalHistoryLimit;
    private final Map<RequestKey, Boolean> active = new LinkedHashMap<>();
    private final Map<RequestKey, TerminalReason> terminal = new LinkedHashMap<>();
    private final EnumMap<TerminalReason, Long> terminalCounts =
            new EnumMap<>(TerminalReason.class);

    public MovementRequestLifecycleCoordinator() {
        this(DEFAULT_TERMINAL_HISTORY_LIMIT);
    }

    public MovementRequestLifecycleCoordinator(int terminalHistoryLimit) {
        if (terminalHistoryLimit <= 0) {
            throw new IllegalArgumentException("terminalHistoryLimit must be positive");
        }
        this.terminalHistoryLimit = terminalHistoryLimit;
        for (TerminalReason reason : TerminalReason.values()) {
            terminalCounts.put(reason, 0L);
        }
    }

    /** Registers a newly published generation. Duplicate registration is rejected. */
    public boolean register(long sessionToken, String owner, long generation) {
        return register(new RequestKey(sessionToken, owner, generation));
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
    public boolean terminate(long sessionToken, String owner, long generation,
                             TerminalReason reason) {
        return terminate(new RequestKey(sessionToken, owner, generation), reason);
    }

    public boolean terminate(RequestKey key, TerminalReason reason) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(reason, "reason");
        if (!active.containsKey(key) || terminal.containsKey(key)) {
            return false;
        }
        active.remove(key);
        terminal.put(key, reason);
        while (terminal.size() > terminalHistoryLimit) {
            terminal.remove(terminal.keySet().iterator().next());
        }
        terminalCounts.compute(reason, (ignored, count) -> count + 1L);
        return true;
    }

    public boolean isActive(long sessionToken, String owner, long generation) {
        return active.containsKey(new RequestKey(sessionToken, owner, generation));
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
