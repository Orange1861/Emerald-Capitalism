package com.orangevillager61.emeraldcapitalism.villager;

/** Stable hunger thresholds and timing rules for villager gameplay. */
public final class HungerPolicy {
    public static final int MAX_HUNGER = 20;
    public static final int HUNGRY_THRESHOLD = 10;
    public static final int TICKS_PER_HUNGER_DECREASE = 1_600;
    public static final int TICKS_PER_STARVATION_DAMAGE = 3_200;
    public static final int TICKS_PER_HEAL = 80;
    public static final int UPDATE_INTERVAL = 20;
    public static final int EATING_DURATION_TICKS = 32;
    public static final int EATING_EFFECT_INTERVAL = 4;
    public static final int HUNGER_THRESHOLD_TO_EAT_WOUNDED = 18;
    public static final int HUNGER_THRESHOLD_TO_EAT_HEALTHY = 15;
    public static final int HUNGER_THRESHOLD_TO_HEAL = 18;

    private HungerPolicy() {
    }

    public static int eatingThreshold(boolean wounded) {
        return wounded ? HUNGER_THRESHOLD_TO_EAT_WOUNDED : HUNGER_THRESHOLD_TO_EAT_HEALTHY;
    }

    public static boolean shouldEat(int hunger, boolean wounded, boolean hasBreedTarget) {
        return !hasBreedTarget && hunger < eatingThreshold(wounded);
    }

    public static boolean shouldHeal(int hunger, boolean wounded) {
        return wounded && hunger >= HUNGER_THRESHOLD_TO_HEAL;
    }
}
