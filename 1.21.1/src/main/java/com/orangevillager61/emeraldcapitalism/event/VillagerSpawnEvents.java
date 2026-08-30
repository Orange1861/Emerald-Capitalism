package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.util.VillagerFamilyUtils;
import com.orangevillager61.emeraldcapitalism.util.VillagerNameManager;
import com.orangevillager61.emeraldcapitalism.world.bank.BankTargets;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Assigns stable parent metadata to structure- and spawn-egg babies. */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
@SuppressWarnings("resource") // Server/level instances are lifecycle-managed by Minecraft.
public class VillagerSpawnEvents {

    private static final double PARENT_SEARCH_RADIUS = 32.0;
    private static final double EXTENDED_PARENT_SEARCH_RADIUS = 64.0;
    private static final int PARENT_ASSIGNMENT_RETRY_INTERVAL_TICKS = 40;
    private static final int MAX_PARENT_ASSIGNMENT_RETRIES = 30;
    private static final int INITIAL_PARENT_ASSIGNMENT_DELAY_TICKS = 60;
    private static final int STRUCTURE_SPAWN_EMERALDS_MIN = 32;
    private static final int STRUCTURE_SPAWN_EMERALDS_MAX = 96;
    private static final int BABY_STRUCTURE_SPAWN_EMERALDS_MIN = STRUCTURE_SPAWN_EMERALDS_MIN / 2;
    private static final int BABY_STRUCTURE_SPAWN_EMERALDS_MAX = STRUCTURE_SPAWN_EMERALDS_MAX / 2;
    private static final int STRUCTURE_SPAWN_BREAD_MIN_DAYS = 6;
    private static final int STRUCTURE_SPAWN_BREAD_MAX_DAYS = 9;
    private static final Map<UUID, Integer> PENDING_PARENT_ASSIGNMENT_RETRIES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> PARENT_ASSIGNMENT_READY_TIME = new ConcurrentHashMap<>();

    /** Handles finalization of structure and spawn-egg villager babies. */
    @SubscribeEvent
    public static void onVillagerSpawn(FinalizeSpawnEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }

        VillagerNameManager.assignNameIfNeeded(villager);

        MobSpawnType spawnType = event.getSpawnType();
        if (spawnType == MobSpawnType.STRUCTURE) {
            addStructureSpawnSupplies(villager);
        }

        if (!villager.isBaby()) {
            return;
        }

        if (spawnType != MobSpawnType.STRUCTURE && spawnType != MobSpawnType.SPAWN_EGG) {
            return;
        }

        EmeraldCapitalism.LOGGER.debug(
                "FinalizeSpawn parent assignment check for baby villager {} with spawnType={}",
                villager.getUUID(), spawnType);

        PENDING_PARENT_ASSIGNMENT_RETRIES.putIfAbsent(villager.getUUID(), 0);
        PARENT_ASSIGNMENT_READY_TIME.put(villager.getUUID(), villager.level().getGameTime() + INITIAL_PARENT_ASSIGNMENT_DELAY_TICKS);
        EmeraldCapitalism.LOGGER.debug(
                "Queued delayed parent assignment for baby {} in {} ticks",
                villager.getUUID(), INITIAL_PARENT_ASSIGNMENT_DELAY_TICKS);
    }

    /** Restores or assigns villager names when entities join a server level. */
    @SubscribeEvent
    public static void onVillagerJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }
        if (villager.level().isClientSide()) {
            return;
        }
        VillagerNameManager.assignNameIfNeeded(villager);

        if (villager.isBaby()) {
            VillagerStatsAttachment babyStats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
            EmeraldCapitalism.LOGGER.debug(
                    "Baby villager joined level: uuid={} spawnReason=join parent1={} parent2={} hasParents={} age={}",
                    villager.getUUID(),
                    babyStats.getParent1UUID(), babyStats.getParent2UUID(), babyStats.hasParents(), villager.getAge());
        } else {
            tryAssignNearbyOrphanBabies(villager);
        }
        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        if (villager.isBaby()) {
            if (!stats.hasParents()) {
                if (isBeforeParentAssignmentReady(villager)) {
                    EmeraldCapitalism.LOGGER.debug(
                            "Deferring parent assignment for baby {} until ready tick {} (current {})",
                            villager.getUUID(),
                            PARENT_ASSIGNMENT_READY_TIME.getOrDefault(villager.getUUID(), 0L),
                            villager.level().getGameTime());
                } else if (villager.getSpawnType() == null || villager.getSpawnType() == MobSpawnType.BREEDING) {
                    // Direct offspring creation can carry BREEDING without a
                    // FinalizeSpawnEvent; let its caller hook assign the parent first.
                    EmeraldCapitalism.LOGGER.debug(
                            "Deferring immediate parent assignment for baby {} because no explicit parent marker is available",
                            villager.getUUID());
                } else {
                    EmeraldCapitalism.LOGGER.debug(
                            "Baby villager {} joined without parents; attempting nearby-parent assignment", villager.getUUID());
                    assignParentsFromNearbyVillagers(villager);
                    if (!stats.hasParents()) {
                        EmeraldCapitalism.LOGGER.debug(
                                "Baby villager {} still has no parents after assignment attempt (likely no nearby adults within {} blocks)",
                                villager.getUUID(), PARENT_SEARCH_RADIUS);
                    }
                }
            }
            ensureParentNamesFromUUIDs(villager, stats);
        }
        if (stats.getVillagerName() == null || stats.getVillagerName().isEmpty()) {
            if (villager.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                serverLevel.getServer().execute(() -> {
                    if (villager.isRemoved()) {
                        return;
                    }
                    VillagerNameManager.assignNameIfNeeded(villager);
                });
            }
        }
    }

    @SubscribeEvent
    @SuppressWarnings("unused") // Invoked by NeoForge event bus.
    public static void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }
        if (villager.level().isClientSide()) {
            return;
        }

        if (!villager.isBaby()) {
            return;
        }

        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        if (stats.hasParents()) {
            PENDING_PARENT_ASSIGNMENT_RETRIES.remove(villager.getUUID());
            PARENT_ASSIGNMENT_READY_TIME.remove(villager.getUUID());
            return;
        }

        if (isBeforeParentAssignmentReady(villager)) {
            return;
        }

        if ((villager.tickCount + villager.getId()) % PARENT_ASSIGNMENT_RETRY_INTERVAL_TICKS != 0) {
            return;
        }

        int retries = PENDING_PARENT_ASSIGNMENT_RETRIES.getOrDefault(villager.getUUID(), 0);
        if (retries >= MAX_PARENT_ASSIGNMENT_RETRIES) {
            EmeraldCapitalism.LOGGER.debug(
                    "Stopping parent assignment retries for baby {} after {} attempts",
                    villager.getUUID(), retries);
            PENDING_PARENT_ASSIGNMENT_RETRIES.remove(villager.getUUID());
            PARENT_ASSIGNMENT_READY_TIME.remove(villager.getUUID());
            return;
        }

        EmeraldCapitalism.LOGGER.debug(
                "Retrying parent assignment for orphan baby {} (attempt {}/{})",
                villager.getUUID(), retries + 1, MAX_PARENT_ASSIGNMENT_RETRIES);
        assignParentsFromNearbyVillagers(villager);
        if (stats.hasParents()) {
            PENDING_PARENT_ASSIGNMENT_RETRIES.remove(villager.getUUID());
            PARENT_ASSIGNMENT_READY_TIME.remove(villager.getUUID());
        } else {
            PENDING_PARENT_ASSIGNMENT_RETRIES.put(villager.getUUID(), retries + 1);
        }
    }

    private static boolean isBeforeParentAssignmentReady(Villager villager) {
        long readyAt = PARENT_ASSIGNMENT_READY_TIME.getOrDefault(villager.getUUID(), 0L);
        return villager.level().getGameTime() < readyAt;
    }

    private static void tryAssignNearbyOrphanBabies(Villager adultVillager) {
        AABB searchArea = adultVillager.getBoundingBox().inflate(PARENT_SEARCH_RADIUS);
        List<Villager> nearbyBabies = adultVillager.level().getEntitiesOfClass(
                Villager.class,
                searchArea,
                villager -> villager.isBaby() && villager != adultVillager
        );

        if (nearbyBabies.isEmpty()) {
            return;
        }

        int orphans = 0;
        for (Villager baby : nearbyBabies) {
            VillagerStatsAttachment babyStats = baby.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
            if (babyStats.hasParents()) {
                continue;
            }
            orphans++;
            assignParentsFromNearbyVillagers(baby);
        }

        if (orphans > 0) {
            EmeraldCapitalism.LOGGER.debug(
                    "Adult villager {} joined and triggered orphan-baby parent assignment for {} nearby babies",
                    adultVillager.getUUID(), orphans);
        }
    }

    /** Assigns nearest loaded adults as fallback parents for an unassigned baby. */
    private static void assignParentsFromNearbyVillagers(Villager babyVillager) {
        VillagerStatsAttachment babyStats = babyVillager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        if (babyStats.hasParents()) {
            EmeraldCapitalism.LOGGER.debug(
                    "Skipping parent assignment for baby villager {} because parents are already set: parent1={} parent2={}",
                    babyVillager.getUUID(), babyStats.getParent1UUID(), babyStats.getParent2UUID());
            return;
        }

        List<Villager> nearbyAdults = findNearbyAdults(babyVillager, PARENT_SEARCH_RADIUS);

        if (nearbyAdults.isEmpty()) {
            // Structure-spawned babies can join before all nearby adults are loaded.
            List<Villager> extendedAdults = findNearbyAdults(babyVillager, EXTENDED_PARENT_SEARCH_RADIUS);
            if (!extendedAdults.isEmpty()) {
                nearbyAdults = extendedAdults;
                EmeraldCapitalism.LOGGER.debug(
                        "Parent assignment fallback for baby {}: found {} loaded adults within {} blocks after none within {} blocks",
                        babyVillager.getUUID(), nearbyAdults.size(), EXTENDED_PARENT_SEARCH_RADIUS, PARENT_SEARCH_RADIUS);
            } else {
                EmeraldCapitalism.LOGGER.debug(
                        "Could not assign parents to baby villager {}: found 0 loaded adult villagers within {} blocks (and none within {} blocks)",
                        babyVillager.getUUID(), PARENT_SEARCH_RADIUS, EXTENDED_PARENT_SEARCH_RADIUS);
                return;
            }
        }

        nearbyAdults.sort((a, b) -> {
            double distA = a.distanceToSqr(babyVillager);
            double distB = b.distanceToSqr(babyVillager);
            return Double.compare(distA, distB);
        });

        Villager parent1 = nearbyAdults.getFirst();
        Villager parent2 = nearbyAdults.size() > 1 ? nearbyAdults.get(1) : null;

        EmeraldCapitalism.LOGGER.debug(
                "Parent assignment candidates for baby {}: adultCount={} nearestParent1={} nearestParent2={}",
                babyVillager.getUUID(),
                nearbyAdults.size(),
                parent1.getUUID(),
                parent2 != null ? parent2.getUUID() : "none");

        VillagerNameManager.assignNameIfNeeded(parent1);
        VillagerNameManager.assignNameIfNeeded(babyVillager);
        if (parent2 != null) {
            VillagerNameManager.assignNameIfNeeded(parent2);
        }

        VillagerStatsAttachment parent1Stats = parent1.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        VillagerStatsAttachment parent2Stats = parent2 != null
                ? parent2.getData(EmeraldCapitalismAttachments.VILLAGER_STATS)
                : null;

        babyStats.setParent1UUID(parent1.getUUID());
        babyStats.setParent1Name(VillagerFamilyUtils.getVillagerDisplayName(parent1));
        if (parent2 != null) {
            babyStats.setParent2UUID(parent2.getUUID());
            babyStats.setParent2Name(VillagerFamilyUtils.getVillagerDisplayName(parent2));
        }

        parent1Stats.addChild(babyVillager.getUUID());
        if (parent2Stats != null) {
            parent2Stats.addChild(babyVillager.getUUID());
        }

        if (parent1Stats.getParent1UUID() != null) {
            babyStats.addGrandparent(parent1Stats.getParent1UUID());
        }
        if (parent1Stats.getParent2UUID() != null) {
            babyStats.addGrandparent(parent1Stats.getParent2UUID());
        }
        if (parent2Stats != null) {
            if (parent2Stats.getParent1UUID() != null) {
                babyStats.addGrandparent(parent2Stats.getParent1UUID());
            }
            if (parent2Stats.getParent2UUID() != null) {
                babyStats.addGrandparent(parent2Stats.getParent2UUID());
            }
        }

        if (parent2 != null) {
            EmeraldCapitalism.LOGGER.debug("Assigned parents to structure-spawned baby villager: {} -> parents {} and {}",
                    babyVillager.getUUID(), parent1.getUUID(), parent2.getUUID());
        } else {
            EmeraldCapitalism.LOGGER.debug("Assigned parent to baby villager: {} -> parent {}",
                    babyVillager.getUUID(), parent1.getUUID());
        }
    }

    private static List<Villager> findNearbyAdults(Villager babyVillager, double radius) {
        AABB searchArea = babyVillager.getBoundingBox().inflate(radius);
        return babyVillager.level().getEntitiesOfClass(
                Villager.class,
                searchArea,
                villager -> !villager.isBaby() && villager != babyVillager
        );
    }

    public static void assignParentsFromSpawnEgg(Villager babyVillager, Villager primaryParent) {
        VillagerStatsAttachment babyStats = babyVillager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        if (babyStats.hasParents()) {
            return;
        }

        VillagerNameManager.assignNameIfNeeded(primaryParent);
        VillagerNameManager.assignNameIfNeeded(babyVillager);

        VillagerStatsAttachment primaryStats = primaryParent.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        babyStats.setParent1UUID(primaryParent.getUUID());
        babyStats.setParent1Name(VillagerFamilyUtils.getVillagerDisplayName(primaryParent));
        primaryStats.addChild(babyVillager.getUUID());

        AABB searchArea = babyVillager.getBoundingBox().inflate(PARENT_SEARCH_RADIUS);
        List<Villager> nearbyAdults = babyVillager.level().getEntitiesOfClass(
                Villager.class,
                searchArea,
                villager -> !villager.isBaby() && villager != babyVillager && villager != primaryParent
        );

        Villager secondaryParent = null;
        if (!nearbyAdults.isEmpty()) {
            nearbyAdults.sort((a, b) -> {
                double distA = a.distanceToSqr(babyVillager);
                double distB = b.distanceToSqr(babyVillager);
                return Double.compare(distA, distB);
            });
            secondaryParent = nearbyAdults.getFirst();
            VillagerStatsAttachment secondaryStats =
                    secondaryParent.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
            VillagerNameManager.assignNameIfNeeded(secondaryParent);
            babyStats.setParent2UUID(secondaryParent.getUUID());
            babyStats.setParent2Name(VillagerFamilyUtils.getVillagerDisplayName(secondaryParent));
            secondaryStats.addChild(babyVillager.getUUID());

            if (secondaryStats.getParent1UUID() != null) {
                babyStats.addGrandparent(secondaryStats.getParent1UUID());
            }
            if (secondaryStats.getParent2UUID() != null) {
                babyStats.addGrandparent(secondaryStats.getParent2UUID());
            }
        }

        if (primaryStats.getParent1UUID() != null) {
            babyStats.addGrandparent(primaryStats.getParent1UUID());
        }
        if (primaryStats.getParent2UUID() != null) {
            babyStats.addGrandparent(primaryStats.getParent2UUID());
        }

        if (secondaryParent != null) {
            EmeraldCapitalism.LOGGER.debug("Assigned spawn-egg parents to baby villager: {} -> parents {} and {}",
                    babyVillager.getUUID(), primaryParent.getUUID(), secondaryParent.getUUID());
        } else {
            EmeraldCapitalism.LOGGER.debug("Assigned spawn-egg parent to baby villager: {} -> parent {}",
                    babyVillager.getUUID(), primaryParent.getUUID());
        }
    }

    private static void ensureParentNamesFromUUIDs(Villager villager, VillagerStatsAttachment stats) {
        java.util.UUID parent1UUID = stats.getParent1UUID();
        if (parent1UUID != null && (stats.getParent1Name() == null || stats.getParent1Name().isEmpty())) {
            String parentName = resolveVillagerNameByUUID(villager, parent1UUID);
            if (parentName != null) {
                stats.setParent1Name(parentName);
            } else {
                EmeraldCapitalism.LOGGER.debug(
                        "Failed to resolve parent1 name for child {} from UUID {}", villager.getUUID(), parent1UUID);
            }
        }

        java.util.UUID parent2UUID = stats.getParent2UUID();
        if (parent2UUID != null && (stats.getParent2Name() == null || stats.getParent2Name().isEmpty())) {
            String parentName = resolveVillagerNameByUUID(villager, parent2UUID);
            if (parentName != null) {
                stats.setParent2Name(parentName);
            } else {
                EmeraldCapitalism.LOGGER.debug(
                        "Failed to resolve parent2 name for child {} from UUID {}", villager.getUUID(), parent2UUID);
            }
        }
    }

    private static String resolveVillagerNameByUUID(Villager child, java.util.UUID parentUUID) {
        if (!(child.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return null;
        }
        net.minecraft.world.entity.Entity entity = serverLevel.getEntity(parentUUID);
        if (entity == null) {
            EmeraldCapitalism.LOGGER.debug(
                    "Unable to resolve parent villager entity for child {}: UUID {} is not loaded", child.getUUID(), parentUUID);
            return null;
        }
        if (entity instanceof Villager parentVillager) {
            VillagerNameManager.assignNameIfNeeded(parentVillager);
            String resolvedName = VillagerFamilyUtils.getVillagerDisplayName(parentVillager);
            if (resolvedName != null && !resolvedName.isEmpty()) {
                EmeraldCapitalism.LOGGER.debug(
                        "Resolved missing parent name for child {} from parent {} -> '{}'",
                        child.getUUID(), parentUUID, resolvedName);
                return resolvedName;
            }
        }
        EmeraldCapitalism.LOGGER.debug(
                "Unable to resolve parent villager name for child {}: entity for UUID {} is {}",
                child.getUUID(), parentUUID, entity.getType().toShortString());
        return null;
    }

    public static void addStructureSpawnSupplies(Villager villager) {
        var inventory = villager.getInventory();
        int minimumEmeralds = villager.isBaby()
                ? BABY_STRUCTURE_SPAWN_EMERALDS_MIN
                : STRUCTURE_SPAWN_EMERALDS_MIN;
        int maximumEmeralds = villager.isBaby()
                ? BABY_STRUCTURE_SPAWN_EMERALDS_MAX
                : STRUCTURE_SPAWN_EMERALDS_MAX;
        int emeraldCount = minimumEmeralds
                + villager.level().getRandom().nextInt(maximumEmeralds - minimumEmeralds + 1);
        addEmeraldsToInventory(inventory, emeraldCount);
        int breadDays = STRUCTURE_SPAWN_BREAD_MIN_DAYS
                + villager.level().getRandom().nextInt(
                STRUCTURE_SPAWN_BREAD_MAX_DAYS - STRUCTURE_SPAWN_BREAD_MIN_DAYS + 1);
        inventory.addItem(new ItemStack(Items.BREAD, BankTargets.BREAD_PER_DAY * breadDays));
    }

    private static void addEmeraldsToInventory(SimpleContainer inventory, int emeraldCount) {
        int remainingEmeralds = emeraldCount;
        int maxStackSize = Items.EMERALD.getDefaultMaxStackSize();
        while (remainingEmeralds > 0) {
            int stackSize = Math.min(remainingEmeralds, maxStackSize);
            ItemStack remainder = inventory.addItem(new ItemStack(Items.EMERALD, stackSize));
            int inserted = stackSize - remainder.getCount();
            if (inserted <= 0) {
                return;
            }
            remainingEmeralds -= inserted;
        }
    }

}
