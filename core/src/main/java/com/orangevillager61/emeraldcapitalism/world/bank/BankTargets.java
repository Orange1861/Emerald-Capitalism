package com.orangevillager61.emeraldcapitalism.world.bank;

/** Derived inventory targets used by a village bank's internal logistics. */
public final class BankTargets {

    public static final int PUMPKINS_PER_EMERALD_GOLEM = 5;
    public static final int BASE_PUMPKIN_TARGET = 2;
    public static final int PLANKS_PER_EXPECTED_GOLEM = 4;
    public static final int PLANKS_PER_EMERALD_CHEST = 12;
    public static final int PLANKS_PER_VILLAGE_BED = 8;
    public static final int PLANKS_PER_VILLAGE_DOOR = 6;
    public static final int BASE_PLANK_TARGET = 128;
    public static final int SKRIMISHERS_PER_EMERALD_GOLEM = 2;
    public static final int BREAD_PER_DAY = 3;
    public static final int INTERNAL_BREAD_DAYS = 5;
    public static final int BREAD_PER_VILLAGER = BREAD_PER_DAY * INTERNAL_BREAD_DAYS;
    public static final int BREAD_TRADE_TARGET = BREAD_PER_VILLAGER * 2;
    public static final int BASE_COAL_TARGET = 192;
    public static final int COAL_PER_EMERALD_ORE = 1;

    private BankTargets() {
    }

    /** Returns {@code 5 * emeraldGolemCapacity + 2}, clamped to a valid item count. */
    public static int pumpkinTarget(int emeraldGolemCapacity) {
        int safeCapacity = Math.max(0, emeraldGolemCapacity);
        if (safeCapacity > (Integer.MAX_VALUE - BASE_PUMPKIN_TARGET) / PUMPKINS_PER_EMERALD_GOLEM) {
            return Integer.MAX_VALUE;
        }
        return safeCapacity * PUMPKINS_PER_EMERALD_GOLEM + BASE_PUMPKIN_TARGET;
    }

    /**
     * Returns the bank's plank-equivalent reserve target:
     * {@code (4 * expected golems) + (12 * emerald chests) + (8 * beds)
     * + (6 * doors) + 128}.
     */
    public static int plankTarget(int expectedEmeraldGolems, int emeraldChestCount,
                                  int bedCount, int doorCount) {
        long target = (long) Math.max(0, expectedEmeraldGolems) * PLANKS_PER_EXPECTED_GOLEM
                + (long) Math.max(0, emeraldChestCount) * PLANKS_PER_EMERALD_CHEST
                + (long) Math.max(0, bedCount) * PLANKS_PER_VILLAGE_BED
                + (long) Math.max(0, doorCount) * PLANKS_PER_VILLAGE_DOOR
                + BASE_PLANK_TARGET;
        return target >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) target;
    }

    /** Returns the maximum number of Emerald Skrimishers for live emerald golems. */
    public static int emeraldSkrimisherLimit(int emeraldGolemCount) {
        int safeCount = Math.max(0, emeraldGolemCount);
        if (safeCount > Integer.MAX_VALUE / SKRIMISHERS_PER_EMERALD_GOLEM) {
            return Integer.MAX_VALUE;
        }
        return safeCount * SKRIMISHERS_PER_EMERALD_GOLEM;
    }

    /** Returns five days of bread per villager, clamped to a valid item count. */
    public static int breadTarget(int villagerCount) {
        int safeVillagerCount = Math.max(0, villagerCount);
        if (safeVillagerCount > Integer.MAX_VALUE / BREAD_PER_VILLAGER) {
            return Integer.MAX_VALUE;
        }
        return safeVillagerCount * BREAD_PER_VILLAGER;
    }

    /** Returns the amount needed to fill a villager to twice their bread target, or zero. */
    public static int breadPurchaseQuantity(int currentBread) {
        int safeCurrentBread = Math.max(0, currentBread);
        return safeCurrentBread < BREAD_PER_VILLAGER
                ? BREAD_TRADE_TARGET - safeCurrentBread : 0;
    }

    /** Returns the amount a villager may sell to reach twice their target, or zero. */
    public static int breadSaleQuantity(int currentBread) {
        int safeCurrentBread = Math.max(0, currentBread);
        return (long) safeCurrentBread * 2 >= (long) BREAD_PER_VILLAGER * 5
                ? Math.max(0, safeCurrentBread - BREAD_TRADE_TARGET) : 0;
    }

    /** Returns the coal-and-charcoal reserve target: {@code 192 + emerald ore}. */
    public static int coalTarget(int emeraldOreCount) {
        int safeEmeraldOreCount = Math.max(0, emeraldOreCount);
        if (safeEmeraldOreCount > (Integer.MAX_VALUE - BASE_COAL_TARGET) / COAL_PER_EMERALD_ORE) {
            return Integer.MAX_VALUE;
        }
        return BASE_COAL_TARGET + safeEmeraldOreCount * COAL_PER_EMERALD_ORE;
    }
}
