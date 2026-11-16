package com.orangevillager61.emeraldcapitalism.navigation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Transient ledger of village-scoped claims on shared work targets.
 *
 * <p>A claim names its village, profession, kind, and target. Two professions
 * can therefore claim the same coordinates without meeting. Every claim carries
 * a generation, and {@link #release(Claim)} only releases the exact claim it is
 * handed, so cleanup that arrives late from an old task cannot release a newer
 * claim by the same owner on the same target.</p>
 *
 * <p>Claims are never persisted. A reload starts with an empty ledger and
 * claims are re-established from durable progress.</p>
 */
public final class ClaimLedger {

    /**
     * Claim kinds in acquisition order. A task holding several claims takes them
     * in this order and releases them in reverse, so two tasks cannot each hold
     * one of a pair and wait for the other.
     */
    public enum Kind {
        TREE,
        FURNACE,
        WORK_CELL,
        FARMLAND_REPAIR,
        MATURE_CROP,
        ATTACHED_PUMPKIN
    }

    public record Key(UUID villageId, String profession, Kind kind, long target) {
        public Key {
            Objects.requireNonNull(villageId, "villageId");
            Objects.requireNonNull(profession, "profession");
            Objects.requireNonNull(kind, "kind");
            if (profession.isBlank()) {
                throw new IllegalArgumentException("profession must not be blank");
            }
        }
    }

    public record Claim(Key key, UUID owner, long generation) {
        public Claim {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(owner, "owner");
            if (generation <= 0L) {
                throw new IllegalArgumentException("generation must be positive");
            }
        }
    }

    private final Map<Key, Claim> claims = new HashMap<>();
    private long nextGeneration;

    /**
     * Claims one target. Returns the owner's existing claim if it already holds
     * it, a new claim if the target is free, and empty if someone else holds it.
     */
    public Optional<Claim> tryClaim(Key key, UUID owner) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(owner, "owner");
        Claim existing = claims.get(key);
        if (existing != null) {
            return existing.owner().equals(owner) ? Optional.of(existing) : Optional.empty();
        }
        Claim claim = new Claim(key, owner, ++nextGeneration);
        claims.put(key, claim);
        return Optional.of(claim);
    }

    /**
     * Claims several targets in {@link Kind} order, all or nothing. The returned
     * list is in acquisition order; release it with {@link #releaseAll}.
     */
    public Optional<List<Claim>> tryClaimAll(Collection<Key> keys, UUID owner) {
        List<Key> ordered = new ArrayList<>(keys);
        ordered.sort(Comparator.comparing(Key::kind));
        List<Claim> taken = new ArrayList<>();
        List<Claim> newlyCreated = new ArrayList<>();
        for (Key key : ordered) {
            boolean alreadyHeld = holder(key).filter(owner::equals).isPresent();
            Optional<Claim> claim = tryClaim(key, owner);
            if (claim.isEmpty()) {
                for (int i = newlyCreated.size() - 1; i >= 0; i--) {
                    release(newlyCreated.get(i));
                }
                return Optional.empty();
            }
            taken.add(claim.get());
            if (!alreadyHeld) {
                newlyCreated.add(claim.get());
            }
        }
        return Optional.of(taken);
    }

    /** Releases exactly this claim. A stale claim (older generation) releases nothing. */
    public boolean release(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        return claims.remove(claim.key(), claim);
    }

    /** Releases claims in reverse of the order they were taken. */
    public int releaseAll(List<Claim> inAcquisitionOrder) {
        int released = 0;
        for (int i = inAcquisitionOrder.size() - 1; i >= 0; i--) {
            if (release(inAcquisitionOrder.get(i))) {
                released++;
            }
        }
        return released;
    }

    /** Releases everything one owner holds in one profession. */
    public int releaseOwner(UUID owner, String profession) {
        int before = claims.size();
        claims.values().removeIf(claim -> claim.owner().equals(owner)
                && claim.key().profession().equals(profession));
        return before - claims.size();
    }

    /** Releases everything one owner holds, in every profession. */
    public int releaseOwner(UUID owner) {
        int before = claims.size();
        claims.values().removeIf(claim -> claim.owner().equals(owner));
        return before - claims.size();
    }

    public Optional<UUID> holder(Key key) {
        Claim claim = claims.get(key);
        return claim == null ? Optional.empty() : Optional.of(claim.owner());
    }

    public Optional<Claim> claimOn(Key key) {
        return Optional.ofNullable(claims.get(key));
    }

    public boolean isCurrent(Claim claim) {
        return claim.equals(claims.get(claim.key()));
    }

    public int size() {
        return claims.size();
    }

    public void clear() {
        claims.clear();
    }
}
