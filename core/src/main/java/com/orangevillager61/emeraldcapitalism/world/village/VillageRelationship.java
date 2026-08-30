package com.orangevillager61.emeraldcapitalism.world.village;

/** Platform-free relationship rules between a player and a village. */
public enum VillageRelationship {
    GOVERNOR("Governor"),
    GOVERNOR_CANDIDATE("Governor-Candidate"),
    NEUTRAL("Neutral"),
    HOSTILE("Hostile");

    public static final int DEFAULT_HOSTILE_THRESHOLD = -100;
    public static final int DEFAULT_GOVERNOR_CANDIDATE_THRESHOLD = 99;

    private final String displayName;

    VillageRelationship(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static VillageRelationship resolve(int opinion, boolean governor, boolean candidate) {
        return resolve(opinion, DEFAULT_HOSTILE_THRESHOLD, governor, candidate);
    }

    public static VillageRelationship resolve(int opinion, int hostileThreshold,
                                              boolean governor, boolean candidate) {
        if (opinion <= hostileThreshold) {
            return HOSTILE;
        }
        if (governor) {
            return GOVERNOR;
        }
        if (candidate) {
            return GOVERNOR_CANDIDATE;
        }
        return NEUTRAL;
    }

    public static boolean canBecomeGovernorCandidate(int opinion) {
        return canBecomeGovernorCandidate(opinion, DEFAULT_GOVERNOR_CANDIDATE_THRESHOLD);
    }

    public static boolean canBecomeGovernorCandidate(int opinion, int candidateThreshold) {
        return opinion > candidateThreshold;
    }

    /** The integer half used for candidate invalidation and the Mayor-loss penalty. */
    public static int candidateFloor(int candidateThreshold) {
        return Math.floorDiv(candidateThreshold, 2);
    }

    public static VillageRelationship fromNetworkId(int id) {
        return id >= 0 && id < values().length ? values()[id] : NEUTRAL;
    }
}
