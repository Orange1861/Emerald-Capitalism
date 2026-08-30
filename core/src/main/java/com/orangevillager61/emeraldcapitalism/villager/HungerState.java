package com.orangevillager61.emeraldcapitalism.villager;

/** Mutable platform-free hunger and eating state owned by a platform attachment. */
public final class HungerState {
    private int hungerLevel = HungerPolicy.MAX_HUNGER;
    private long lastAteTime;
    private int ticksSinceLastHungerDecrease;
    private int ticksSinceLastHeal;
    private int ticksSinceLastStarvationDamage;
    private boolean eating;
    private int eatingTicksRemaining;
    private int eatingSlot = -1;
    private int eatingNutrition;

    public int hungerLevel() {
        return hungerLevel;
    }

    public void setHungerLevel(int hungerLevel) {
        this.hungerLevel = Math.max(0, Math.min(HungerPolicy.MAX_HUNGER, hungerLevel));
    }

    public void decreaseHunger(int amount) {
        setHungerLevel(hungerLevel - amount);
    }

    public void increaseHunger(int amount) {
        setHungerLevel(hungerLevel + amount);
    }

    public boolean isHungry() {
        return hungerLevel < HungerPolicy.HUNGRY_THRESHOLD;
    }

    public boolean isStarving() {
        return hungerLevel <= 0;
    }

    public long lastAteTime() {
        return lastAteTime;
    }

    public void setLastAteTime(long lastAteTime) {
        this.lastAteTime = lastAteTime;
    }

    public int ticksSinceLastHungerDecrease() {
        return ticksSinceLastHungerDecrease;
    }

    public void setTicksSinceLastHungerDecrease(int ticks) {
        this.ticksSinceLastHungerDecrease = ticks;
    }

    public int ticksSinceLastHeal() {
        return ticksSinceLastHeal;
    }

    public void setTicksSinceLastHeal(int ticks) {
        this.ticksSinceLastHeal = ticks;
    }

    public int ticksSinceLastStarvationDamage() {
        return ticksSinceLastStarvationDamage;
    }

    public void setTicksSinceLastStarvationDamage(int ticks) {
        this.ticksSinceLastStarvationDamage = ticks;
    }

    public boolean isEating() {
        return eating;
    }

    public int eatingTicksRemaining() {
        return eatingTicksRemaining;
    }

    public void startEating(int slot, int nutrition, int durationTicks) {
        eating = true;
        eatingSlot = slot;
        eatingNutrition = nutrition;
        eatingTicksRemaining = durationTicks;
    }

    public boolean tickEating() {
        if (!eating) {
            return false;
        }
        eatingTicksRemaining--;
        return eatingTicksRemaining <= 0;
    }

    public int eatingSlot() {
        return eatingSlot;
    }

    public int finishEating() {
        int nutrition = eatingNutrition;
        resetEating();
        return nutrition;
    }

    public void resetEating() {
        eating = false;
        eatingSlot = -1;
        eatingNutrition = 0;
        eatingTicksRemaining = 0;
    }
}
