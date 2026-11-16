package com.orangevillager61.emeraldcapitalism.world.forestry;

import com.orangevillager61.emeraldcapitalism.navigation.ClaimLedger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * One village's lumbermill furnaces, the single claim on each, and the queue of
 * lumberjacks waiting for one.
 *
 * <p>The pool holds positions and claim records only. It never reads a world:
 * the caller validates a furnace against the membership rule (inside the village
 * bounds and part of a lumbermill) before {@link #addFurnace}, and moves every
 * item itself. Everything here is transient; after a reload the saved
 * {@code charcoal_furnace} position is turned back into a claim with
 * {@link #restoreClaim}.</p>
 */
public final class FurnacePool {

    public static final String PROFESSION = "lumberjack";
    /** About five minutes: how long ready charcoal may sit before the claim passes to the village. */
    public static final long ORPHAN_TICKS = 6_000L;
    /** A lumberjack asks every 100 ticks; a queue entry unrefreshed for this long is dropped. */
    public static final long QUEUE_STALE_TICKS = 300L;
    /** After creation no free furnace is offered, so reloaded holders can restore their claims first. */
    public static final long RELOAD_GRACE_TICKS = 600L;
    /** Vanilla smelts one item per 200 ticks. */
    public static final long SMELT_TICKS_PER_ITEM = 200L;
    /** Owner of a claim the village holds after its holder is gone. */
    public static final UUID VILLAGE_OWNER = new UUID(0L, 0L);

    /** A granted claim and the record of what it has put into the furnace. */
    public record Grant(ClaimLedger.Claim claim, FurnaceClaim batch, long position, boolean collectingOrphan) {
    }

    /** Result of one holder check. */
    public record CheckResult(FurnaceClaim.Reconciliation reconciliation, boolean released) {
    }

    private static final class Entry {
        ClaimLedger.Claim claim;
        final FurnaceClaim batch;
        boolean orphaned;
        long estimatedReadyTick;
        final long position;

        Entry(long position, ClaimLedger.Claim claim, FurnaceClaim batch) {
            this.position = position;
            this.claim = claim;
            this.batch = batch;
        }
    }

    private static final class Waiting {
        final long firstAsked;
        final long sequence;
        long lastAsked;

        Waiting(long firstAsked, long sequence) {
            this.firstAsked = firstAsked;
            this.sequence = sequence;
            this.lastAsked = firstAsked;
        }
    }

    private final UUID villageId;
    private final ClaimLedger ledger;
    private final long graceUntil;
    private final Set<Long> furnaces = new TreeSet<>();
    private final Set<Long> occupied = new HashSet<>();
    private final Map<Long, Entry> entries = new HashMap<>();
    private final Map<UUID, Waiting> queue = new LinkedHashMap<>();
    private long nextSequence;

    public FurnacePool(UUID villageId, ClaimLedger ledger, long createdAtTick) {
        this.villageId = Objects.requireNonNull(villageId, "villageId");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.graceUntil = createdAtTick + RELOAD_GRACE_TICKS;
    }

    // ----- index -----

    /** Adds a furnace the caller already validated against the membership rule. */
    public boolean addFurnace(long position) {
        return furnaces.add(position);
    }

    public boolean containsFurnace(long position) {
        return furnaces.contains(position);
    }

    public Set<Long> furnaces() {
        return Set.copyOf(furnaces);
    }

    /**
     * A furnace was removed or broken: its claim is dropped and its owner
     * rejoins the queue with the wait it already had.
     */
    public boolean removeFurnace(long position, long now) {
        boolean removed = furnaces.remove(position);
        occupied.remove(position);
        Entry entry = entries.remove(position);
        if (entry != null) {
            ledger.release(entry.claim);
            UUID owner = entry.claim.owner();
            if (!entry.orphaned && !owner.equals(VILLAGE_OWNER)) {
                queue.putIfAbsent(owner, new Waiting(now, nextSequence++));
            }
        }
        return removed;
    }

    /** Marks a furnace that holds someone else's items; it is not offered until cleared. */
    public void markOccupied(long position) {
        if (furnaces.contains(position)) {
            occupied.add(position);
        }
    }

    /** A later check found the furnace empty. */
    public void clearOccupied(long position) {
        occupied.remove(position);
    }

    public boolean isOccupied(long position) {
        return occupied.contains(position);
    }

    // ----- claims -----

    /**
     * Asks for a furnace. Returns the owner's existing claim, a claim on an
     * orphaned batch to collect, or a claim on a free furnace; otherwise the owner
     * joins (or stays in) the queue and gets nothing. Only the longest-waiting
     * lumberjack is served, so a late asker never takes a furnace from the queue.
     */
    public Optional<Grant> request(UUID owner, long now) {
        Objects.requireNonNull(owner, "owner");
        prune(now);
        for (Entry entry : entries.values()) {
            if (entry.claim.owner().equals(owner)) {
                return Optional.of(grantOf(entry, false));
            }
        }
        Waiting mine = queue.computeIfAbsent(owner, ignored -> new Waiting(now, nextSequence++));
        mine.lastAsked = now;
        if (now < graceUntil || !isFront(owner)) {
            return Optional.empty();
        }

        Entry orphan = entries.values().stream()
                .filter(entry -> entry.orphaned)
                .min(Comparator.comparingLong(entry -> entry.position))
                .orElse(null);
        if (orphan != null) {
            ledger.release(orphan.claim);
            Optional<ClaimLedger.Claim> claim = ledger.tryClaim(keyOf(orphan.position), owner);
            if (claim.isPresent()) {
                orphan.claim = claim.get();
                orphan.orphaned = false;
                queue.remove(owner);
                return Optional.of(grantOf(orphan, true));
            }
        }
        for (long position : furnaces) {
            if (entries.containsKey(position) || occupied.contains(position)) {
                continue;
            }
            Optional<ClaimLedger.Claim> claim = ledger.tryClaim(keyOf(position), owner);
            if (claim.isPresent()) {
                Entry entry = new Entry(position, claim.get(), new FurnaceClaim());
                entries.put(position, entry);
                queue.remove(owner);
                return Optional.of(grantOf(entry, false));
            }
        }
        return Optional.empty();
    }

    /**
     * The saved {@code charcoal_furnace} of a reloaded lumberjack becomes a claim
     * again. Returns empty when the furnace is gone from the pool, held by someone
     * else, or taken over by the village: the holder then clears its saved position.
     */
    public Optional<Grant> restoreClaim(UUID owner, long position, FurnaceClaim.Observation observed) {
        if (!furnaces.contains(position)) {
            return Optional.empty();
        }
        Entry existing = entries.get(position);
        if (existing != null) {
            return existing.claim.owner().equals(owner) && !existing.orphaned
                    ? Optional.of(grantOf(existing, false)) : Optional.empty();
        }
        Optional<ClaimLedger.Claim> claim = ledger.tryClaim(keyOf(position), owner);
        if (claim.isEmpty()) {
            return Optional.empty();
        }
        Entry entry = new Entry(position, claim.get(), FurnaceClaim.adopt(observed));
        entries.put(position, entry);
        queue.remove(owner);
        return Optional.of(grantOf(entry, false));
    }

    /** The holder loaded logs. Starts the estimate used to decide when its charcoal is abandoned. */
    public boolean recordLoaded(ClaimLedger.Claim claim, int logs, long now) {
        Entry entry = currentEntry(claim);
        if (entry == null) {
            return false;
        }
        entry.batch.recordLoaded(logs);
        entry.estimatedReadyTick = Math.max(entry.estimatedReadyTick, now) + SMELT_TICKS_PER_ITEM * logs;
        return true;
    }

    /**
     * A holder check: compares the furnace with the record, says what to take,
     * and releases the claim when the furnace is verified empty or only holds
     * items the claim did not place. A stale claim changes nothing.
     */
    public Optional<CheckResult> check(ClaimLedger.Claim claim, FurnaceClaim.Observation observed) {
        Entry entry = currentEntry(claim);
        if (entry == null) {
            return Optional.empty();
        }
        FurnaceClaim.Reconciliation result = entry.batch.reconcile(observed);
        boolean released = false;
        if (result.verdict() != FurnaceClaim.Verdict.CONTINUE) {
            if (result.verdict() == FurnaceClaim.Verdict.RELEASE_OCCUPIED) {
                occupied.add(entry.position);
            }
            release(claim);
            released = true;
        }
        return Optional.of(new CheckResult(result, released));
    }

    /** Releases exactly this claim. A stale claim releases nothing. */
    public boolean release(ClaimLedger.Claim claim) {
        Entry entry = currentEntry(claim);
        if (entry == null) {
            return false;
        }
        entries.remove(entry.position);
        return ledger.release(claim);
    }

    /**
     * The holder died, unloaded, or changed profession. The claim passes to the
     * village under a new generation, so the old holder's late cleanup releases
     * nothing, and the furnace stays reserved for the next in the queue.
     */
    public boolean handToVillage(UUID owner) {
        boolean changed = false;
        for (Entry entry : entries.values()) {
            if (entry.claim.owner().equals(owner) && !entry.orphaned) {
                ledger.release(entry.claim);
                entry.claim = ledger.tryClaim(keyOf(entry.position), VILLAGE_OWNER).orElseThrow();
                entry.orphaned = true;
                changed = true;
            }
        }
        queue.remove(owner);
        return changed;
    }

    /** The owner's current grant, if it holds a furnace; the batch record is the pool's, not the caller's. */
    public Optional<Grant> activeGrant(UUID owner) {
        return entries.values().stream()
                .filter(entry -> entry.claim.owner().equals(owner) && !entry.orphaned)
                .findFirst()
                .map(entry -> grantOf(entry, false));
    }

    public Optional<ClaimLedger.Claim> claimOf(UUID owner) {
        return entries.values().stream()
                .filter(entry -> entry.claim.owner().equals(owner))
                .map(entry -> entry.claim)
                .findFirst();
    }

    public boolean isOrphaned(long position) {
        Entry entry = entries.get(position);
        return entry != null && entry.orphaned;
    }

    public boolean isClaimed(long position) {
        return entries.containsKey(position);
    }

    public int queueSize() {
        return queue.size();
    }

    /** The owner next in line, longest wait first, or empty. */
    public Optional<UUID> queueFront() {
        return queueOrder().stream().findFirst();
    }

    public List<UUID> queueOrder() {
        List<Map.Entry<UUID, Waiting>> waiting = new ArrayList<>(queue.entrySet());
        waiting.sort(Comparator.<Map.Entry<UUID, Waiting>>comparingLong(e -> e.getValue().firstAsked)
                .thenComparingLong(e -> e.getValue().sequence));
        return waiting.stream().map(Map.Entry::getKey).toList();
    }

    /** Leaves the queue; the owner no longer wants a furnace. */
    public void leaveQueue(UUID owner) {
        queue.remove(owner);
    }

    /** Drops stale queue entries and hands batches left uncollected too long to the village. */
    public void prune(long now) {
        queue.values().removeIf(waiting -> now - waiting.lastAsked > QUEUE_STALE_TICKS);
        for (Entry entry : new ArrayList<>(entries.values())) {
            if (!entry.orphaned && entry.estimatedReadyTick > 0L
                    && now - entry.estimatedReadyTick >= ORPHAN_TICKS) {
                handToVillage(entry.claim.owner());
            }
        }
    }

    // ----- helpers -----

    private Entry currentEntry(ClaimLedger.Claim claim) {
        Entry entry = entries.get(claim.key().target());
        return entry != null && entry.claim.equals(claim) && ledger.isCurrent(claim) ? entry : null;
    }

    private boolean isFront(UUID owner) {
        return queueFront().filter(owner::equals).isPresent();
    }

    private ClaimLedger.Key keyOf(long position) {
        return new ClaimLedger.Key(villageId, PROFESSION, ClaimLedger.Kind.FURNACE, position);
    }

    private Grant grantOf(Entry entry, boolean collectingOrphan) {
        return new Grant(entry.claim, entry.batch, entry.position, collectingOrphan);
    }
}
