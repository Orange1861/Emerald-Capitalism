package com.orangevillager61.emeraldcapitalism.attachments;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.orangevillager61.emeraldcapitalism.network.ProtocolStringLimits;
import com.orangevillager61.emeraldcapitalism.villager.HungerState;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class VillagerStatsAttachment {

    static final int MAX_PERSISTED_CHILDREN = 1_024;
    private static final int MAX_PERSISTED_GRANDPARENTS = 4;
    private static final int MAX_NAMING_ELEMENT_LENGTH = 64;
    private static final int MAX_PERSISTED_DRIFT_RULES = 12;

    private static final Codec<String> NAMING_ELEMENT_CODEC = boundedStringCodec(
            MAX_NAMING_ELEMENT_LENGTH, "Villager naming element");
    private static final Codec<String> PARENT_NAME_CODEC = boundedStringCodec(
            ProtocolStringLimits.MAX_PARENT_NAME_LENGTH, "Parent name");
    private static final Codec<List<UUID>> UNIQUE_CHILD_UUIDS_CODEC =
            UUIDUtil.CODEC.sizeLimitedListOf(MAX_PERSISTED_CHILDREN)
            .xmap(VillagerStatsAttachment::deduplicateUuids, VillagerStatsAttachment::deduplicateUuids);
    private static final Codec<List<UUID>> GRANDPARENT_UUIDS_CODEC =
            UUIDUtil.CODEC.sizeLimitedListOf(MAX_PERSISTED_GRANDPARENTS)
            .comapFlatMap(values -> {
                List<UUID> unique = deduplicateUuids(values);
                return unique.size() <= MAX_PERSISTED_GRANDPARENTS
                        ? DataResult.success(unique)
                        : DataResult.error(() -> "A villager may persist at most four unique grandparents");
            }, VillagerStatsAttachment::deduplicateUuids);

    private record NamingState(
            Optional<String> personalFirstElement,
            Optional<String> personalSecondElement,
            Optional<java.util.UUID> namingVillageId,
            Optional<String> specialFirstName,
            List<String> wanderingTraderDriftRules,
            Optional<String> generatedWanderingTraderName
    ) {
        private static final Codec<NamingState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                NAMING_ELEMENT_CODEC.optionalFieldOf("personal_first_element")
                        .forGetter(NamingState::personalFirstElement),
                NAMING_ELEMENT_CODEC.optionalFieldOf("personal_second_element")
                        .forGetter(NamingState::personalSecondElement),
                UUIDUtil.CODEC.optionalFieldOf("naming_village_id")
                        .forGetter(NamingState::namingVillageId),
                NAMING_ELEMENT_CODEC.optionalFieldOf("special_first_name")
                        .forGetter(NamingState::specialFirstName),
                NAMING_ELEMENT_CODEC.sizeLimitedListOf(MAX_PERSISTED_DRIFT_RULES)
                        .optionalFieldOf("wandering_trader_drift_rules", List.of())
                        .forGetter(NamingState::wanderingTraderDriftRules),
                NAMING_ELEMENT_CODEC.optionalFieldOf("generated_wandering_trader_name")
                        .forGetter(NamingState::generatedWanderingTraderName)
        ).apply(instance, NamingState::new));
    }

    /**
     * The durable attachment state. Codec decoding always creates a new attachment,
     * so the mutable runtime lists and all transient state cannot be shared with the
     * source object or with another decoded attachment.
     */
    public static final Codec<VillagerStatsAttachment> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("hunger_level", 20).forGetter(VillagerStatsAttachment::getHungerLevel),
            Codec.LONG.optionalFieldOf("last_ate_time", 0L).forGetter(VillagerStatsAttachment::getLastAteTime),
            Codec.INT.optionalFieldOf("ticks_since_last_hunger_decrease", 0)
                    .forGetter(VillagerStatsAttachment::getTicksSinceLastHungerDecrease),
            Codec.INT.optionalFieldOf("ticks_since_last_heal", 0)
                    .forGetter(VillagerStatsAttachment::getTicksSinceLastHeal),
            Codec.INT.optionalFieldOf("ticks_since_last_starvation_damage", 0)
                    .forGetter(VillagerStatsAttachment::getTicksSinceLastStarvationDamage),
            Codec.LONG.optionalFieldOf("last_beg_time", 0L).forGetter(VillagerStatsAttachment::getLastBegTime),
            Codec.INT.optionalFieldOf("beg_time", 0).forGetter(VillagerStatsAttachment::getBegTime),
            UUIDUtil.CODEC.optionalFieldOf("beg_donor_uuid")
                    .forGetter(attachment -> Optional.ofNullable(attachment.getBegDonorUUID())),
            NamingState.CODEC.optionalFieldOf("villager_naming")
                    .forGetter(attachment -> attachment.hasAssignedFirstName()
                            ? Optional.of(new NamingState(
                            Optional.ofNullable(attachment.getPersonalFirstElement()),
                            Optional.ofNullable(attachment.getPersonalSecondElement()),
                            Optional.ofNullable(attachment.getNamingVillageId()),
                            Optional.ofNullable(attachment.getSpecialFirstName()),
                            attachment.getWanderingTraderDriftRules(),
                            Optional.ofNullable(attachment.getGeneratedWanderingTraderName())))
                            : Optional.empty()),
            UUIDUtil.CODEC.optionalFieldOf("parent_1_uuid")
                    .forGetter(attachment -> Optional.ofNullable(attachment.getParent1UUID())),
            UUIDUtil.CODEC.optionalFieldOf("parent_2_uuid")
                    .forGetter(attachment -> Optional.ofNullable(attachment.getParent2UUID())),
            PARENT_NAME_CODEC.optionalFieldOf("parent_1_name")
                    .forGetter(attachment -> Optional.ofNullable(attachment.getParent1Name())),
            PARENT_NAME_CODEC.optionalFieldOf("parent_2_name")
                    .forGetter(attachment -> Optional.ofNullable(attachment.getParent2Name())),
            UNIQUE_CHILD_UUIDS_CODEC.optionalFieldOf("children_uuids", List.of())
                    .forGetter(VillagerStatsAttachment::getChildrenUUIDs),
            GRANDPARENT_UUIDS_CODEC.optionalFieldOf("grandparent_uuids", List.of())
                    .forGetter(VillagerStatsAttachment::getGrandparentUUIDs),
            Codec.INT.optionalFieldOf("emerald_balance", 0)
                    .forGetter(VillagerStatsAttachment::getEmeraldBalance)
    ).apply(instance, VillagerStatsAttachment::new));

    // Platform-free hunger state is kept separate from the attachment codec and item cache.
    private final HungerState hungerState = new HungerState();

    // Beg-for-food behavior tracking
    private long lastBegTime = 0;
    private int begTime = 0;
    private UUID begDonorUUID = null;

    // Eating animation state (transient, not saved to NBT)
    // Keep the clean state lazy so codec-only consumers do not initialize item
    // registries; the public getter still exposes the existing EMPTY sentinel.
    private ItemStack eatingItem = null;
    private int cachedFoodSlot = -1;

    // Cached inventory item counts (transient, not saved to NBT; -1 = stale)
    private int cachedEmeraldCount = -1;
    private int cachedWheatCount   = -1; // raw wheat + hay bale × 9
    private int cachedBreadCount   = -1;
    private int cachedPumpkinCount = -1;

    // Villager name
    // Regular villager display names remain derived; wandering traders also
    // persist a bounded generated-name marker to protect name-tag overrides.
    private String villagerName = null;
    private String personalFirstElement = null;
    private String personalSecondElement = null;
    private UUID namingVillageId = null;
    private String specialFirstName = null;
    private final List<String> wanderingTraderDriftRules = new ArrayList<>();
    private String generatedWanderingTraderName = null;
    // Transient render cache used to avoid rebuilding names on every entity tick.
    private String lastRenderedProfession = null;
    private int lastRenderedAgeStage = Integer.MIN_VALUE;
    private String lastRenderedOriginVillageName = null;

    // Family tracking
    private UUID parent1UUID = null;
    private UUID parent2UUID = null;
    private String parent1Name = null;
    private String parent2Name = null;
    private final List<UUID> childrenUUIDs = new ArrayList<>();
    private final List<UUID> grandparentUUIDs = new ArrayList<>(); // Up to 4 grandparents

    // Emerald balance tracking (can go negative from trades)
    private int emeraldBalance = 0;

    public VillagerStatsAttachment() {
    }

    private static List<UUID> deduplicateUuids(List<UUID> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private static Codec<String> boundedStringCodec(int maxLength, String description) {
        return Codec.STRING.validate(value -> value.length() <= maxLength
                ? DataResult.success(value)
                : DataResult.error(() -> description + " exceeds " + maxLength + " characters"));
    }

    private VillagerStatsAttachment(
            int hungerLevel,
            long lastAteTime,
            int ticksSinceLastHungerDecrease,
            int ticksSinceLastHeal,
            int ticksSinceLastStarvationDamage,
            long lastBegTime,
            int begTime,
            Optional<UUID> begDonorUUID,
            Optional<NamingState> namingState,
            Optional<UUID> parent1UUID,
            Optional<UUID> parent2UUID,
            Optional<String> parent1Name,
            Optional<String> parent2Name,
            List<UUID> childrenUUIDs,
            List<UUID> grandparentUUIDs,
            int emeraldBalance) {
        hungerState.setHungerLevel(hungerLevel);
        hungerState.setLastAteTime(lastAteTime);
        hungerState.setTicksSinceLastHungerDecrease(ticksSinceLastHungerDecrease);
        hungerState.setTicksSinceLastHeal(ticksSinceLastHeal);
        hungerState.setTicksSinceLastStarvationDamage(ticksSinceLastStarvationDamage);
        this.lastBegTime = lastBegTime;
        this.begTime = begTime;
        this.begDonorUUID = begDonorUUID.orElse(null);
        namingState.ifPresent(state -> {
            this.personalFirstElement = state.personalFirstElement().orElse(null);
            this.personalSecondElement = state.personalSecondElement().orElse(null);
            this.namingVillageId = state.namingVillageId().orElse(null);
            this.specialFirstName = state.specialFirstName().orElse(null);
            this.wanderingTraderDriftRules.addAll(state.wanderingTraderDriftRules());
            this.generatedWanderingTraderName = state.generatedWanderingTraderName().orElse(null);
        });
        this.parent1UUID = parent1UUID.orElse(null);
        this.parent2UUID = parent2UUID.orElse(null);
        this.parent1Name = parent1Name.orElse(null);
        this.parent2Name = parent2Name.orElse(null);
        this.childrenUUIDs.addAll(childrenUUIDs);
        this.grandparentUUIDs.addAll(grandparentUUIDs);
        this.emeraldBalance = emeraldBalance;
    }

    // Getters and setters
    public int getHungerLevel() {
        return hungerState.hungerLevel();
    }

    public void setHungerLevel(int hungerLevel) {
        hungerState.setHungerLevel(hungerLevel);
    }

    public void decreaseHunger(int amount) {
        hungerState.decreaseHunger(amount);
    }

    public void increaseHunger(int amount) {
        hungerState.increaseHunger(amount);
    }

    public boolean isHungry() {
        return hungerState.isHungry();
    }

    public boolean isStarving() {
        return hungerState.isStarving();
    }

    public long getLastAteTime() {
        return hungerState.lastAteTime();
    }

    public void setLastAteTime(long time) {
        hungerState.setLastAteTime(time);
    }

    public int getTicksSinceLastHungerDecrease() {
        return hungerState.ticksSinceLastHungerDecrease();
    }

    public void setTicksSinceLastHungerDecrease(int ticks) {
        hungerState.setTicksSinceLastHungerDecrease(ticks);
    }

    public int getTicksSinceLastHeal() {
        return hungerState.ticksSinceLastHeal();
    }

    public void setTicksSinceLastHeal(int ticks) {
        hungerState.setTicksSinceLastHeal(ticks);
    }

    public int getTicksSinceLastStarvationDamage() {
        return hungerState.ticksSinceLastStarvationDamage();
    }

    public void setTicksSinceLastStarvationDamage(int ticks) {
        hungerState.setTicksSinceLastStarvationDamage(ticks);
    }

    public long getLastBegTime() {
        return lastBegTime;
    }

    public void setLastBegTime(long lastBegTime) {
        this.lastBegTime = lastBegTime;
    }

    public int getBegTime() {
        return begTime;
    }

    public void setBegTime(int begTime) {
        this.begTime = begTime;
    }

    public UUID getBegDonorUUID() {
        return begDonorUUID;
    }

    public void setBegDonorUUID(UUID begDonorUUID) {
        this.begDonorUUID = begDonorUUID;
    }

    public void resetBeggingState() {
        begTime = 0;
        begDonorUUID = null;
    }

    // Emerald balance methods

    /**
     * Gets the villager's emerald balance from trading.
     * This can be negative if the villager has paid out more emeralds than received.
     */
    public int getEmeraldBalance() {
        return emeraldBalance;
    }

    /**
     * Sets the villager's emerald balance.
     */
    public void setEmeraldBalance(int balance) {
        this.emeraldBalance = balance;
    }

    /**
     * Adds emeralds to the villager's balance (e.g., when player buys items with emeralds).
     */
    public void addEmeralds(int amount) {
        this.emeraldBalance += amount;
    }

    /**
     * Subtracts emeralds from the villager's balance (e.g., when player sells items for emeralds).
     * Balance can go negative.
     */
    public void subtractEmeralds(int amount) {
        this.emeraldBalance -= amount;
    }

    /**
     * Returns true if the villager's emerald balance is negative (in debt).
     */
    public boolean isInDebt() {
        return emeraldBalance < 0;
    }

    // Eating animation methods
    
    /**
     * Checks if the villager is currently eating.
     */
    public boolean isEating() {
        return hungerState.isEating();
    }

    /**
     * Gets the item currently being eaten (for particle effects).
     */
    public ItemStack getEatingItem() {
        return eatingItem == null ? ItemStack.EMPTY : eatingItem;
    }

    /**
     * Gets the remaining ticks until eating is complete.
     */
    public int getEatingTicksRemaining() {
        return hungerState.eatingTicksRemaining();
    }

    /**
     * Decrements eating ticks and returns true if eating is complete.
     */
    public boolean tickEating() {
        return hungerState.tickEating();
    }

    /**
     * Starts the eating animation for a food item.
     * 
     * @param item the food item being eaten (copy stored for particles)
     * @param slot the inventory slot the food is from
     * @param nutrition the nutrition value to apply when done
     * @param durationTicks how long eating takes (default 32 for players)
     */
    public void startEating(ItemStack item, int slot, int nutrition, int durationTicks) {
        hungerState.startEating(slot, nutrition, durationTicks);
        this.eatingItem = item.copy();
    }

    /**
     * Completes eating and returns the nutrition gained.
     * Resets all eating state.
     * 
     * @return the nutrition value to add to hunger
     */
    public int finishEating() {
        int nutrition = hungerState.finishEating();
        this.eatingItem = null;
        return nutrition;
    }

    /**
     * Cancels eating without applying nutrition.
     */
    public void resetEating() {
        hungerState.resetEating();
        this.eatingItem = null;
    }

    /**
     * Gets the inventory slot of the food being eaten.
     */
    public int getEatingSlot() {
        return hungerState.eatingSlot();
    }

    /**
     * Gets the cached inventory slot that most recently contained food.
     */
    public int getCachedFoodSlot() {
        return cachedFoodSlot;
    }

    /**
     * Caches the inventory slot that contains food for quick lookups.
     */
    public void setCachedFoodSlot(int cachedFoodSlot) {
        this.cachedFoodSlot = cachedFoodSlot;
    }

    // Inventory item count cache

    /**
     * Scans the villager's inventory in a single pass and updates the cached counts
     * for emerald value (raw emeralds + emerald blocks × 9), wheat-equivalent
     * (raw wheat + hay bale × 9), bread, and pumpkins.
     * Refresh this cache before reading a cached count.
     */
    public void refreshInventoryCounts(SimpleContainer inv) {
        int emeralds = 0, wheat = 0, bread = 0, pumpkins = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if      (stack.is(Items.EMERALD))       { emeralds += stack.getCount(); }
            else if (stack.is(Items.EMERALD_BLOCK)) { emeralds += stack.getCount() * 9; }
            else if (stack.is(Items.WHEAT))     { wheat    += stack.getCount(); }
            else if (stack.is(Items.HAY_BLOCK)) { wheat    += stack.getCount() * 9; }
            else if (stack.is(Items.BREAD))     { bread    += stack.getCount(); }
            else if (stack.is(Items.PUMPKIN))   { pumpkins += stack.getCount(); }
        }
        cachedEmeraldCount = emeralds;
        cachedWheatCount   = wheat;
        cachedBreadCount   = bread;
        cachedPumpkinCount = pumpkins;
    }

    /** Returns the cached emerald value from the last {@link #refreshInventoryCounts} call. */
    public int getCachedEmeraldCount()  { return cachedEmeraldCount; }

    /** Returns the cached wheat-equivalent count (raw wheat + hay bale × 9). */
    public int getCachedWheatCount()    { return cachedWheatCount; }

    /** Returns the cached bread count. */
    public int getCachedBreadCount()    { return cachedBreadCount; }

    /** Returns the cached pumpkin count. */
    public int getCachedPumpkinCount()  { return cachedPumpkinCount; }

    // Villager name methods

    /**
     * Gets the assigned name for this villager.
     */
    public String getVillagerName() {
        return villagerName;
    }

    /**
     * Sets the assigned name for this villager.
     */
    public void setVillagerName(String name) {
        this.villagerName = name;
    }

    public String getPersonalFirstElement() {
        return personalFirstElement;
    }

    public void setPersonalFirstElement(String personalFirstElement) {
        this.personalFirstElement = clampNullable(personalFirstElement, MAX_NAMING_ELEMENT_LENGTH);
    }

    public String getPersonalSecondElement() {
        return personalSecondElement;
    }

    public void setPersonalSecondElement(String personalSecondElement) {
        this.personalSecondElement = clampNullable(personalSecondElement, MAX_NAMING_ELEMENT_LENGTH);
    }

    public boolean hasPersonalNameSlot() {
        return personalFirstElement != null && !personalFirstElement.isBlank()
                && personalSecondElement != null && !personalSecondElement.isBlank();
    }

    public String getSpecialFirstName() {
        return specialFirstName;
    }

    public void setSpecialFirstName(String specialFirstName) {
        this.specialFirstName = clampNullable(specialFirstName, MAX_NAMING_ELEMENT_LENGTH);
    }

    public boolean hasSpecialFirstName() {
        return specialFirstName != null && !specialFirstName.isBlank();
    }

    public boolean hasAssignedFirstName() {
        return hasPersonalNameSlot() || hasSpecialFirstName();
    }

    public List<String> getWanderingTraderDriftRules() {
        return List.copyOf(wanderingTraderDriftRules);
    }

    public void setWanderingTraderDriftRules(List<String> rules) {
        Objects.requireNonNull(rules, "rules");
        if (rules.size() > MAX_PERSISTED_DRIFT_RULES) {
            throw new IllegalArgumentException(
                    "A villager may persist at most " + MAX_PERSISTED_DRIFT_RULES + " drift rules");
        }
        List<String> validatedRules = new ArrayList<>(rules.size());
        for (String rule : rules) {
            Objects.requireNonNull(rule, "rules contains null");
            if (rule.length() > MAX_NAMING_ELEMENT_LENGTH) {
                throw new IllegalArgumentException(
                        "Wandering trader drift rule exceeds " + MAX_NAMING_ELEMENT_LENGTH + " characters");
            }
            validatedRules.add(rule);
        }

        wanderingTraderDriftRules.clear();
        wanderingTraderDriftRules.addAll(validatedRules);
    }

    public String getGeneratedWanderingTraderName() {
        return generatedWanderingTraderName;
    }

    public void setGeneratedWanderingTraderName(String name) {
        this.generatedWanderingTraderName = clampNullable(
                name, ProtocolStringLimits.MAX_PARENT_NAME_LENGTH);
    }

    public UUID getNamingVillageId() {
        return namingVillageId;
    }

    public void setNamingVillageId(UUID namingVillageId) {
        this.namingVillageId = namingVillageId;
    }

    public String getLastRenderedProfession() {
        return lastRenderedProfession;
    }

    public void setLastRenderedProfession(String lastRenderedProfession) {
        this.lastRenderedProfession = lastRenderedProfession;
    }

    public int getLastRenderedAgeStage() {
        return lastRenderedAgeStage;
    }

    public void setLastRenderedAgeStage(int lastRenderedAgeStage) {
        this.lastRenderedAgeStage = lastRenderedAgeStage;
    }

    public String getLastRenderedOriginVillageName() {
        return lastRenderedOriginVillageName;
    }

    public void setLastRenderedOriginVillageName(String lastRenderedOriginVillageName) {
        this.lastRenderedOriginVillageName = lastRenderedOriginVillageName;
    }

    // Family tracking methods
    public List<UUID> getChildrenUUIDs() {
        return new ArrayList<>(childrenUUIDs);
    }

    public void addChild(UUID childUUID) {
        if (!childrenUUIDs.contains(childUUID)) {
            childrenUUIDs.add(childUUID);
        }
    }

    public boolean hasChildren() {
        return !childrenUUIDs.isEmpty();
    }

    public int getChildCount() {
        return childrenUUIDs.size();
    }

    public UUID getParent1UUID() {
        return parent1UUID;
    }

    public void setParent1UUID(UUID uuid) {
        this.parent1UUID = uuid;
    }

    public UUID getParent2UUID() {
        return parent2UUID;
    }

    public void setParent2UUID(UUID uuid) {
        this.parent2UUID = uuid;
    }

    public String getParent1Name() {
        return parent1Name;
    }

    public void setParent1Name(String name) {
        this.parent1Name = clampNullable(name, ProtocolStringLimits.MAX_PARENT_NAME_LENGTH);
    }

    public String getParent2Name() {
        return parent2Name;
    }

    public void setParent2Name(String name) {
        this.parent2Name = clampNullable(name, ProtocolStringLimits.MAX_PARENT_NAME_LENGTH);
    }

    public boolean hasParents() {
        return parent1UUID != null || parent2UUID != null;
    }

    // Grandparent tracking methods
    public List<UUID> getGrandparentUUIDs() {
        return new ArrayList<>(grandparentUUIDs);
    }

    public void addGrandparent(UUID grandparentUUID) {
        Objects.requireNonNull(grandparentUUID, "grandparentUUID");
        if (grandparentUUIDs.size() < 4
                && !grandparentUUIDs.contains(grandparentUUID)) {
            grandparentUUIDs.add(grandparentUUID);
        }
    }

    public boolean hasGrandparents() {
        return !grandparentUUIDs.isEmpty();
    }

    public boolean isGrandparent(UUID uuid) {
        return grandparentUUIDs.contains(uuid);
    }

    private static String clampNullable(String value, int maxLength) {
        return value == null ? null : ProtocolStringLimits.clamp(value, maxLength);
    }

}
