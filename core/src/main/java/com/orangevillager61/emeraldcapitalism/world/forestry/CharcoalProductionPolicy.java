package com.orangevillager61.emeraldcapitalism.world.forestry;

/**
 * Platform-free allocation rules for a lumberjack's log-to-charcoal production.
 *
 * <p>Market rates are expressed as item units per emerald. The reciprocal is
 * therefore the item's value in emeralds, so the charcoal-to-log value ratio is
 * {@code logRate / charcoalRate}.</p>
 */
public final class CharcoalProductionPolicy {
    public static final double DEFAULT_SHARE = 0.50D;
    public static final double MIN_SHARE = 0.20D;
    public static final double MAX_SHARE = 0.80D;
    public static final double MAX_PENDING_QUOTA = 1_000_000.0D;

    private static final double SHARE_SENSITIVITY = 0.20D;
    private static final double LOG_TWO = Math.log(2.0D);

    private CharcoalProductionPolicy() {
    }

    /**
     * Returns the fraction of newly harvested logs assigned to charcoal.
     * Equal prices produce 50%; a two-to-one charcoal premium produces 70%.
     */
    public static double charcoalShare(double logRate, double charcoalRate) {
        if (!isPositiveFinite(logRate) || !isPositiveFinite(charcoalRate)) {
            return DEFAULT_SHARE;
        }

        double valueRatio = logRate / charcoalRate;
        if (!isPositiveFinite(valueRatio)) {
            return DEFAULT_SHARE;
        }

        double share = DEFAULT_SHARE + SHARE_SENSITIVITY * (Math.log(valueRatio) / LOG_TWO);
        return clamp(share, MIN_SHARE, MAX_SHARE);
    }

    /** Adds newly harvested logs to the persistent whole-log conversion quota. */
    public static double accrueQuota(double pendingQuota, int newlyHarvestedLogs, double share) {
        double safeQuota = sanitizeQuota(pendingQuota);
        if (newlyHarvestedLogs <= 0) {
            return safeQuota;
        }

        double safeShare = isFinite(share) ? clamp(share, MIN_SHARE, MAX_SHARE) : DEFAULT_SHARE;
        double accrued = safeQuota + newlyHarvestedLogs * safeShare;
        return clampFinite(accrued, 0.0D, MAX_PENDING_QUOTA);
    }

    /** Returns the number of complete log conversions currently owed. */
    public static int wholeConversions(double pendingQuota, int availableLogs) {
        if (availableLogs <= 0 || !isFinite(pendingQuota) || pendingQuota <= 0.0D) {
            return 0;
        }
        return Math.min(availableLogs, (int) Math.min(MAX_PENDING_QUOTA, Math.floor(pendingQuota)));
    }

    /** Removes successfully completed conversions from the pending quota. */
    public static double afterConversions(double pendingQuota, int convertedLogs) {
        double safeQuota = sanitizeQuota(pendingQuota);
        if (convertedLogs <= 0) {
            return safeQuota;
        }
        return Math.max(0.0D, safeQuota - convertedLogs);
    }

    /** Returns whether a quota can be accepted by the persisted schema. */
    public static boolean isValidQuota(double quota) {
        return isFinite(quota) && quota >= 0.0D && quota <= MAX_PENDING_QUOTA;
    }

    /** Clamps malformed or out-of-range persisted quota values safely. */
    public static double sanitizeQuota(double quota) {
        return clampFinite(quota, 0.0D, MAX_PENDING_QUOTA);
    }

    private static boolean isPositiveFinite(double value) {
        return isFinite(value) && value > 0.0D;
    }

    private static boolean isFinite(double value) {
        return Double.isFinite(value);
    }

    private static double clampFinite(double value, double min, double max) {
        return !isFinite(value) ? min : clamp(value, min, max);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
