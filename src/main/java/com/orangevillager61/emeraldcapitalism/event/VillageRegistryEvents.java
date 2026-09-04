package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.VillageManagerBlockEntity;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import com.orangevillager61.emeraldcapitalism.entity.ai.FarmerBreadConversionGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.VillagerInventoryBankGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.BankerWorkGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.HarvestPumpkinGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.EmeraldSmithProcessorGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.EmeraldSmithGolemConstructionGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.ReplenishFarmlandGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.LumberjackGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.LumberjackSaplingCache;
import com.orangevillager61.emeraldcapitalism.entity.ai.LumberjackTreeReservations;
import com.orangevillager61.emeraldcapitalism.entity.ai.MayorDoorRepairGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.MayorFollowGovernorCandidateGoal;
import com.orangevillager61.emeraldcapitalism.behavior.InteractWithFenceGateBehavior;
import com.orangevillager61.emeraldcapitalism.network.POIOverlaySubscriptions;
import com.orangevillager61.emeraldcapitalism.network.VillagePOIDataCache;
import com.orangevillager61.emeraldcapitalism.network.ManualVillageScanBudget;
import com.orangevillager61.emeraldcapitalism.network.DuplicateVillageBlocksPacket;
import com.orangevillager61.emeraldcapitalism.network.RequestExpandBoundsPacket;
import com.orangevillager61.emeraldcapitalism.network.RequestFullScanPacket;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageGovernance;
import com.orangevillager61.emeraldcapitalism.world.village.VillageHostility;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryManager;
import com.orangevillager61.emeraldcapitalism.world.village.VillageOpinionCache;
import com.orangevillager61.emeraldcapitalism.world.bank.BankReputationData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hooks {@link VillageRegistryManager} into the server-side level tick
 * and event-driven updates (death, spawn/load).
 * One manager instance is maintained per {@link ServerLevel}.
 */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public class VillageRegistryEvents {

    private static final Map<ServerLevel, VillageRegistryManager> MANAGERS = new ConcurrentHashMap<>();
    /** Tracks which village each player is currently inside (by village UUID). */
    private static final Map<UUID, UUID> PLAYER_VILLAGE_MAP = new ConcurrentHashMap<>();

    private static final int BED_PLACED_OPINION_DELTA = 3;
    private static final int BED_DESTROYED_OPINION_DELTA = -5;
    private static final int EMERALD_GOLEM_KILLED_OPINION_DELTA = -25;
    private static final int IRON_GOLEM_KILLED_OPINION_DELTA = -50;
    private static final int GOLEM_HOSTILITY_THRESHOLD = -25;
    private static final double BANK_GOLEM_CONNECTION_RADIUS = 32.0D;
    private static final long GOLEM_OPINION_REFRESH_INTERVAL_TICKS = 40L;

    /**
     * Returns the manager for the given level, creating one if needed.
     * Used by the full-scan packet handler.
     */
    public static VillageRegistryManager getManager(ServerLevel level) {
        return MANAGERS.computeIfAbsent(level, VillageRegistryManager::new);
    }

    /**
     * Clears all per-level manager references.
     * Call on server lifecycle boundaries to avoid retaining stale level instances.
     */
    public static void clearManagers() {
        MANAGERS.values().forEach(VillageRegistryManager::shutdown);
        MANAGERS.clear();
        BankBlockEntity.clearLoadedBanks();
        InteractWithFenceGateBehavior.clearGateUseCache();
        VillageOpinionCache.clearAll();
        VillageHostility.clearLookupCache();
        PLAYER_VILLAGE_MAP.clear();
        ManualVillageScanBudget.clearAll();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            VillageRegistryManager manager = MANAGERS.remove(serverLevel);
            if (manager != null) {
                manager.shutdown();
            }
            BankBlockEntity.clearLoadedBanks(serverLevel);
            LumberjackSaplingCache.clear(serverLevel);
            LumberjackTreeReservations.clear(serverLevel);
            VillagePOIDataCache.invalidateDimension(serverLevel.dimension());
            InteractWithFenceGateBehavior.clearGateUseCache();
            VillageHostility.clearLookupCache();
            VillageOpinionCache.clearAll();
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        VillageRegistryManager manager = MANAGERS.computeIfAbsent(
                serverLevel, VillageRegistryManager::new);
        manager.tick(serverLevel);

        long levelTick = serverLevel.getGameTime();

        // Connected non-player-built golems re-evaluate nearby players every
        // two seconds. A Village Opinion of You <= -25 makes both golem types
        // attack. The target goals remain independent of this reconciliation.
        if (levelTick % GOLEM_OPINION_REFRESH_INTERVAL_TICKS == 0) {
            applyOpinionBasedGolemTargets(serverLevel);
        }

        // Push POI overlay data to subscribed players
        POIOverlaySubscriptions.tick(serverLevel, levelTick);

        // Check for players entering/leaving village bounding boxes (every 20 ticks)
        if (levelTick % 20 == 0) {
            checkPlayerVillageEntry(serverLevel);
        }
    }

    @SubscribeEvent
    public static void onTreeGrowth(BlockGrowFeatureEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || event.getFeature() == null
                || !(event.getFeature().value().config() instanceof TreeConfiguration)) {
            return;
        }

        AABB growthArea = new AABB(event.getPos()).inflate(1.0D, 3.0D, 1.0D);
        if (!level.getEntitiesOfClass(Villager.class, growthArea,
                villager -> villager.getVillagerData().getProfession()
                        == ECAPVillagerProfessions.LUMBERJACK.get()).isEmpty()) {
            event.setCanceled(true);
        }
    }

    /**
     * Checks all players in the level against village bounding boxes.
     * Sends a welcome message when a player first enters a village.
     */
    private static void checkPlayerVillageEntry(ServerLevel level) {
        VillageRegistryData data = VillageRegistryData.get(level);
        for (ServerPlayer player : level.players()) {
            UUID playerId = player.getUUID();
            VillageRecord village = data.getVillageFor(player.blockPosition());
            UUID currentVillageId = village != null ? village.getVillageId() : null;
            UUID previousVillageId = PLAYER_VILLAGE_MAP.get(playerId);
            boolean welcomeReady = village == null || isWelcomeReady(village);

            if (currentVillageId != null && !currentVillageId.equals(previousVillageId) && welcomeReady) {
                // Player entered a new village
                String msg = village.getWelcomeMessage();
                if (!msg.isEmpty()) {
                    player.sendSystemMessage(
                            Component.literal("[" + village.getName() + "]: " + msg)
                                    .withStyle(ChatFormatting.GOLD));
                }
            }

            if (currentVillageId != null && welcomeReady) {
                PLAYER_VILLAGE_MAP.put(playerId, currentVillageId);
            } else {
                PLAYER_VILLAGE_MAP.remove(playerId);
            }
        }
    }

    private static boolean isWelcomeReady(VillageRecord village) {
        if (!Config.enableWorldgenVillageRootNaming) {
            return true;
        }
        String name = village.getName();
        return name != null
                && !name.isBlank()
                && !"Village".equals(name)
                && !name.matches("Village \\d+");
    }

    /** Applies the requested village-opinion penalty when a player kills a connected golem. */
    private static void applyGolemDeathOpinionPenalty(ServerLevel level, Entity golem,
                                                      DamageSource source, int delta) {
        if (!(source.getEntity() instanceof Player player)) {
            return;
        }
        if (golem instanceof EmeraldGolem emerald && emerald.isPlayerCreated()) {
            return;
        }
        if (golem instanceof IronGolem iron && iron.isPlayerCreated()) {
            return;
        }

        VillageRecord village = findConnectedVillage(level, golem);
        if (village != null && !VillageGovernance.isContestedGovernor(level, village, player.getUUID())) {
            village.adjustOpinionModifier(player.getUUID(), delta);
            VillageRegistryData.get(level).setDirty();
        }
    }

    /** Finds a village whose bounds or registered bank owns/contains the golem. */
    @org.jetbrains.annotations.Nullable
    private static VillageRecord findConnectedVillage(ServerLevel level, Entity golem) {
        VillageRegistryData data = VillageRegistryData.get(level);
        VillageRecord containing = data.getVillageFor(golem.blockPosition());
        if (containing != null) {
            return containing;
        }

        if (golem instanceof EmeraldGolem emerald && emerald.getBankEmployeePos() != null) {
            VillageRecord bankVillage = data.getVillageFor(emerald.getBankEmployeePos());
            if (bankVillage != null) {
                return bankVillage;
            }
        }

        double radiusSq = BANK_GOLEM_CONNECTION_RADIUS * BANK_GOLEM_CONNECTION_RADIUS;
        for (VillageRecord village : data.getVillages().values()) {
            BlockPos bankPos = data.getBankPos(village.getVillageId());
            if (bankPos != null && bankPos.distSqr(golem.blockPosition()) <= radiusSq) {
                return village;
            }
        }
        return null;
    }

    /**
     * Makes connected, non-player-built golems attack players whose computed
     * village opinion is -25 or lower. The score combines average villager
     * reputation with persistent bed/golem action modifiers.
     */
    private static void applyOpinionBasedGolemTargets(ServerLevel level) {
        VillageRegistryData data = VillageRegistryData.get(level);
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }

        for (VillageRecord village : data.getVillages().values()) {
            AABB searchArea = village.getBoundingBox().inflate(BANK_GOLEM_CONNECTION_RADIUS);
            List<ServerPlayer> nearbyPlayers = players.stream()
                    .filter(player -> !player.isSpectator()
                            && searchArea.contains(player.getX(), player.getY(), player.getZ()))
                    .toList();
            if (nearbyPlayers.isEmpty()) {
                continue;
            }
            // Emerald golems extend IronGolem, so this single query covers both
            // types without processing emerald golems twice.
            var golems = level.getEntitiesOfClass(IronGolem.class, searchArea, IronGolem::isAlive);

            for (ServerPlayer player : nearbyPlayers) {
                boolean villageHostile = village.getVillageOpinion(level, player) <= GOLEM_HOSTILITY_THRESHOLD;

                for (IronGolem golem : golems) {
                    if (villageHostile && !golem.isPlayerCreated()
                            && !(golem instanceof EmeraldGolem emeraldGolem
                            && emeraldGolem.isOwnedByBank(player.getUUID()))
                            && isConnectedToVillage(data, village, golem)) {
                        setOpinionAttackTarget(golem, player);
                    }
                }
            }
        }
    }

    private static boolean isConnectedToVillage(VillageRegistryData data, VillageRecord village, Entity golem) {
        if (village.getBoundingBox().contains(golem.getX(), golem.getY(), golem.getZ())) {
            return true;
        }
        if (golem instanceof EmeraldGolem emerald
                && emerald.getBankEmployeePos() != null
                && emerald.getBankEmployeePos().equals(data.getBankPos(village.getVillageId()))) {
            return true;
        }
        BlockPos bankPos = data.getBankPos(village.getVillageId());
        return bankPos != null
                && bankPos.distSqr(golem.blockPosition())
                <= BANK_GOLEM_CONNECTION_RADIUS * BANK_GOLEM_CONNECTION_RADIUS;
    }

    private static void setOpinionAttackTarget(IronGolem golem, ServerPlayer player) {
        golem.setTarget(player);
        golem.setPersistentAngerTarget(player.getUUID());
        golem.startPersistentAngerTimer();
    }

    /**
     * On villager death, immediately remove from the village registry
     * instead of waiting for the next scan cycle.
     */
    @SubscribeEvent
    public static void onLivingEntityDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (event.isCanceled()) {
            return;
        }

        if (event.getEntity() instanceof EmeraldGolem emeraldGolem) {
            // Killing a non-player-built emerald golem connected to a village or
            // its bank costs the killer 25 village-opinion points.
            applyGolemDeathOpinionPenalty(serverLevel, emeraldGolem, event.getSource(),
                    EMERALD_GOLEM_KILLED_OPINION_DELTA);
            if (emeraldGolem.getBankEmployeePos() != null
                    && event.getSource().getEntity() instanceof Player player) {
                BankReputationData.get(serverLevel).adjustReputation(
                        player.getUUID(), BankReputationData.GOLEM_KILLED_PENALTY);
            }
            BlockPos bankPos = emeraldGolem.getBankEmployeePos();
            if (bankPos != null
                    && serverLevel.getBlockEntity(bankPos) instanceof BankBlockEntity bank) {
                bank.removeEmeraldGolemEmployee(emeraldGolem.getUUID());
            }
            return;
        }

        if (event.getEntity() instanceof IronGolem ironGolem) {
            // Killing a non-player-built iron golem connected to a village or
            // its bank costs the killer 50 village-opinion points.
            applyGolemDeathOpinionPenalty(serverLevel, ironGolem, event.getSource(),
                    IRON_GOLEM_KILLED_OPINION_DELTA);
            return;
        }

        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }

        if (villager.getVillagerData().getProfession()
                == ECAPVillagerProfessions.LUMBERJACK.get()) {
            LumberjackSaplingCache.clearOwner(serverLevel, villager.getUUID());
        }

        VillageRegistryManager manager = MANAGERS.get(serverLevel);
        if (manager != null) {
            manager.handleVillagerDeath(villager,
                    event.getSource().getEntity() instanceof Player player ? player : null);
        }
    }

    /**
     * On villager spawn or load, if inside a village bounding box,
     * register them immediately instead of waiting for the next scan.
     */
    @SubscribeEvent
    public static void onVillagerJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        // Initialize the manager here as well as from the level ticker so a
        // villager joining during the first tick is eligible for Bank employee
        // registration immediately.
        BankBlockEntity.registerSpawnedEmployeeAtNearestBank(serverLevel, villager);
        VillageRegistryManager manager = MANAGERS.computeIfAbsent(
                serverLevel, VillageRegistryManager::new);
        manager.handleVillagerSpawnOrLoad(villager);

        // Profession-specific goals are present before profession assignment so
        // villagers that change profession after spawning/loading are covered as well.
        injectVillagerInventoryBankGoal(villager);
        injectMayorDoorRepairGoal(villager);
        injectMayorFollowGovernorCandidateGoal(villager);
        injectBankerWorkGoal(villager);
        injectFarmerGoals(villager);
        injectLumberjackGoal(villager);
        injectEmeraldSmithGolemConstructionGoal(villager);
        injectEmeraldSmithGoal(villager);
    }

    /** Adds the once-daily/full-inventory bank delivery task to villagers when needed. */
    private static void injectVillagerInventoryBankGoal(Villager villager) {
        boolean hasGoal = villager.goalSelector.getAvailableGoals().stream()
                .anyMatch(g -> g.getGoal() instanceof VillagerInventoryBankGoal);
        if (!hasGoal) {
            // Cleanup interrupts lower-priority profession work when a delivery
            // becomes pending.
            villager.goalSelector.addGoal(VillagerInventoryBankGoal.GOAL_PRIORITY,
                    new VillagerInventoryBankGoal(villager));
        }
    }

    /** Adds the daytime missing-door repair task to villagers when needed. */
    private static void injectMayorDoorRepairGoal(Villager villager) {
        boolean hasGoal = villager.goalSelector.getAvailableGoals().stream()
                .anyMatch(g -> g.getGoal() instanceof MayorDoorRepairGoal);
        if (!hasGoal) {
            villager.goalSelector.addGoal(MayorDoorRepairGoal.GOAL_PRIORITY,
                    new MayorDoorRepairGoal(villager));
            EmeraldCapitalism.LOGGER.debug(
                    "[ECAP][MayorRepair] INJECTED villager={} profession={} pos={} priority={} flags=MOVE,LOOK",
                    villager.getUUID(), villager.getVillagerData().getProfession(), villager.blockPosition(),
                    MayorDoorRepairGoal.GOAL_PRIORITY);
        }
    }

    /** Adds the election-time goal that keeps the village Mayor with its candidate. */
    private static void injectMayorFollowGovernorCandidateGoal(Villager villager) {
        boolean hasGoal = villager.goalSelector.getAvailableGoals().stream()
                .anyMatch(g -> g.getGoal() instanceof MayorFollowGovernorCandidateGoal);
        if (!hasGoal) {
            villager.goalSelector.addGoal(1, new MayorFollowGovernorCandidateGoal(villager));
        }
    }

    /** Adds the facing-side work task to banker villagers when needed. */
    private static void injectBankerWorkGoal(Villager villager) {
        boolean hasGoal = villager.goalSelector.getAvailableGoals().stream()
                .anyMatch(g -> g.getGoal() instanceof BankerWorkGoal);
        if (!hasGoal) {
            // The side opposite the bank's FACING side is reserved for the
            // banker; depositors use the FACING side documented by BankBlock.
            villager.goalSelector.addGoal(4, new BankerWorkGoal(villager));
        }
    }

    /** Adds the custom farmer goals to a villager's goal selector if not already present. */
    private static void injectFarmerGoals(Villager villager) {
        boolean hasBreadConversionGoal = villager.goalSelector.getAvailableGoals().stream()
                .anyMatch(g -> g.getGoal() instanceof FarmerBreadConversionGoal);
        if (!hasBreadConversionGoal) {
            villager.goalSelector.addGoal(FarmerBreadConversionGoal.GOAL_PRIORITY,
                    new FarmerBreadConversionGoal(villager));
        }

        boolean hasFarmlandRepairGoal = villager.goalSelector.getAvailableGoals().stream()
                .anyMatch(g -> g.getGoal() instanceof ReplenishFarmlandGoal);
        if (!hasFarmlandRepairGoal) {
            villager.goalSelector.addGoal(4, new ReplenishFarmlandGoal(villager));
        }

        boolean hasPumpkinHarvestGoal = villager.goalSelector.getAvailableGoals().stream()
                .anyMatch(g -> g.getGoal() instanceof HarvestPumpkinGoal);
        if (!hasPumpkinHarvestGoal) {
            villager.goalSelector.addGoal(4, new HarvestPumpkinGoal(villager));
        }
    }

    /** Adds the Bank-backed Emerald Processor task to villagers when needed. */
    private static void injectEmeraldSmithGolemConstructionGoal(Villager villager) {
        boolean hasGoal = villager.goalSelector.getAvailableGoals().stream()
                .anyMatch(g -> g.getGoal() instanceof EmeraldSmithGolemConstructionGoal);
        if (!hasGoal) {
            // Golem construction must win over ordinary processor maintenance when
            // the bank has reserved the smith for a build.
            villager.goalSelector.addGoal(3, new EmeraldSmithGolemConstructionGoal(villager));
            if (villager.getVillagerData().getProfession() == ECAPVillagerProfessions.EMERALDSMITH.get()) {
                EmeraldCapitalism.LOGGER.debug(
                        "[EmeraldsmithGolem] INJECT villager={} uuid={} pos={}",
                        villager.getName().getString(), villager.getUUID(), villager.blockPosition());
            }
        }
    }

    /** Adds the tree-harvesting task to villagers when needed. */
    private static void injectLumberjackGoal(Villager villager) {
        boolean hasGoal = villager.goalSelector.getAvailableGoals().stream()
                .anyMatch(g -> g.getGoal() instanceof LumberjackGoal);
        if (!hasGoal) {
            villager.goalSelector.addGoal(4, new LumberjackGoal(villager));
        }
    }

    /** Adds the Bank-backed Emerald Processor task to villagers when needed. */
    private static void injectEmeraldSmithGoal(Villager villager) {
        boolean hasGoal = villager.goalSelector.getAvailableGoals().stream()
                .anyMatch(g -> g.getGoal() instanceof EmeraldSmithProcessorGoal);
        if (!hasGoal) {
            villager.goalSelector.addGoal(4, new EmeraldSmithProcessorGoal(villager));
        }
    }

    /**
     * Clean up POI overlay subscriptions when a player disconnects.
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            POIOverlaySubscriptions.onPlayerDisconnect(player.getUUID());
            RequestFullScanPacket.onPlayerDisconnect(player.getUUID());
            RequestExpandBoundsPacket.onPlayerDisconnect(player.getUUID());
            DuplicateVillageBlocksPacket.onPlayerDisconnect(player.getUUID());
            BankReputationEvents.clearPlayer(player.getUUID());
            VillagePOIDataCache.invalidateViewer(player.getUUID());
            PLAYER_VILLAGE_MAP.remove(player.getUUID());
        }
    }

    /**
     * On dimension change, clear subscriptions and per-player cooldown caches
     * to avoid stale village bindings from a previous dimension.
     */
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            POIOverlaySubscriptions.onPlayerDisconnect(player.getUUID());
            RequestFullScanPacket.onPlayerDisconnect(player.getUUID());
            RequestExpandBoundsPacket.onPlayerDisconnect(player.getUUID());
            BankReputationEvents.clearPlayer(player.getUUID());
            VillagePOIDataCache.invalidateViewer(player.getUUID());
            PLAYER_VILLAGE_MAP.remove(player.getUUID());
        }
    }

    // Block place/break → village cache updates

    /**
     * When a player places a bed, workstation, or door inside a village,
     * update that village's block cache immediately.
     */
    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockState state = event.getPlacedBlock();
        boolean isBed = state.getBlock() instanceof BedBlock;
        boolean isWorkstation = VillageRecord.getWorkstationType(state.getBlock()) != null;
        boolean isFarmland = state.getBlock() instanceof FarmBlock;
        boolean isDoor = state.getBlock() instanceof DoorBlock;
        boolean isEmeraldChest = state.is(com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks.EMERALD_CHEST.get());
        boolean isEmeraldProcessor = state.is(com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks.EMERALD_ORE_PROCESSOR.get());
        if (!isBed && !isWorkstation && !isFarmland && !isDoor && !isEmeraldChest && !isEmeraldProcessor) {
            return;
        }

        BlockPos pos = event.getPos();
        if (isEmeraldChest || isEmeraldProcessor) {
            BankBlockEntity.markChestCachesDirtyNear(level, pos);
        }
        VillageRegistryData data = VillageRegistryData.get(level);
        VillageRecord village = data.getVillageFor(pos);
        if (isDoor) {
            BlockPos basePos = VillageRecord.doorBasePos(pos, state);
            EmeraldCapitalism.LOGGER.info(
                    "[ECAP][DoorCache] PLAYER/ENTITY PLACE entity={} pos={} base={} state={} village={} cacheInitialized={}",
                    event.getEntity().getUUID(), pos, basePos, state,
                    village == null ? null : village.getVillageId(),
                    village != null && village.isCacheInitialized());
        }
        if (village != null) {
            village.onBlockPlaced(pos, state);
            if (isFarmland && village.addFarmland(pos)) {
                data.setDirty();
            }
            if (isDoor) {
                data.setDirty();
                BlockPos basePos = VillageRecord.doorBasePos(pos, state);
                EmeraldCapitalism.LOGGER.info(
                        "[ECAP][DoorCache] PLACE recorded base={} doorRegistered={} missing={} village={}",
                        basePos, village.getDoorRegistry().contains(basePos),
                        village.getMissingDoorRegistry().contains(basePos), village.getVillageId());
            }
            if (isBed && !level.canSeeSky(pos.above())
                    && event.getEntity() instanceof Player player) {
                // Placing a bed in a village improves that player's village opinion by 3.
                village.adjustOpinionModifier(player.getUUID(), BED_PLACED_OPINION_DELTA);
                data.setDirty();
            }
        }
    }

    /**
     * When a player breaks a block inside a village, remove it from
     * that village's block cache if it was a tracked bed, workstation, or door.
     * Also handles farmland destruction: removes from registry.
     * <p>
     * Bank-specific behaviour:
     * <ul>
     *   <li>If the block is a {@link BankBlock} and its linked chests still hold emeralds,
     *       the break is <b>cancelled</b> and the player receives an explanation.</li>
     *   <li>If the bank is broken (no emeralds), the owning village manager is notified
     *       to deregister the bank.</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState state = event.getLevel().getBlockState(pos);
        boolean isDoor = state.getBlock() instanceof DoorBlock;

        if (state.is(com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks.EMERALD_CHEST.get())
                || state.is(com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks.EMERALD_ORE_PROCESSOR.get())) {
            BankBlockEntity.markChestCachesDirtyNear(level, pos);
        }

        // Bank break check
        if (state.getBlock() instanceof BankBlock) {
            if (level.getBlockEntity(pos) instanceof BankBlockEntity bank) {
                if (bank.hasUnverifiedChestCache()) {
                    event.setCanceled(true);
                    Player breaker = event.getPlayer();
                    if (breaker instanceof ServerPlayer serverPlayer) {
                        serverPlayer.sendSystemMessage(Component.literal(
                                "The bank is still verifying its linked chests; try again shortly."));
                    }
                    return;
                }
                if (bank.getTotalEmeraldCount() > 0) {
                    // Prevent breaking while villager funds are held
                    event.setCanceled(true);
                    Player breaker = event.getPlayer();
                    if (breaker instanceof ServerPlayer serverPlayer) {
                        serverPlayer.sendSystemMessage(Component.literal(
                                "The bank cannot be removed while it holds villager funds."));
                    }
                    return;
                }

                // No emeralds: allow the break and deregister from the village manager.
                UUID bankVillageId = bank.getVillageId();
                if (bankVillageId != null) {
                    VillageRegistryData bankData = VillageRegistryData.get(level);
                    BlockPos vmPos = bankData.getVMPos(bankVillageId);
                    if (vmPos != null && level.getBlockEntity(vmPos) instanceof VillageManagerBlockEntity vm) {
                        vm.deregisterBank();
                    }
                    // The manager's chunk can be unloaded while the bank is being broken.
                    // This conditional removal only clears a link that still points at this bank.
                    bankData.deregisterBankPosition(bankVillageId, pos);
                }
            }
        }

        // Village cache updates
        VillageRegistryData data = VillageRegistryData.get(level);
        VillageRecord village = data.getVillageFor(pos);
        if (isDoor) {
            BlockPos basePos = VillageRecord.doorBasePos(pos, state);
            EmeraldCapitalism.LOGGER.info(
                    "[ECAP][DoorCache] PLAYER BREAK entity={} pos={} base={} state={} village={} cacheInitialized={} canceled={}",
                    event.getPlayer().getUUID(), pos, basePos, state,
                    village == null ? null : village.getVillageId(),
                    village != null && village.isCacheInitialized(), event.isCanceled());
        }
        if (village != null) {
            village.onBlockRemoved(pos);
            // BreakEvent fires before the block actually changes, so check the current state
            if (state.getBlock() instanceof FarmBlock && village.addToRepairQueue(pos)) {
                data.setDirty();
            }
            if (isDoor) {
                data.setDirty();
                BlockPos basePos = VillageRecord.doorBasePos(pos, state);
                EmeraldCapitalism.LOGGER.info(
                        "[ECAP][DoorCache] BREAK recorded base={} doorRegistered={} missing={} claimed={} village={}",
                        basePos, village.getDoorRegistry().contains(basePos),
                        village.getMissingDoorRegistry().contains(basePos),
                        village.getClaimedDoorPositions().contains(basePos), village.getVillageId());
            }
            if (state.getBlock() instanceof BedBlock) {
                // Destroying a bed in a village reduces that player's village opinion by 5.
                village.adjustOpinionModifier(event.getPlayer().getUUID(), BED_DESTROYED_OPINION_DELTA);
                data.setDirty();
            }
        }

        // Emerald block aggro: breaking an emerald block angers nearby non-player-spawned golems
        if (Config.emeraldBlockAggroEnabled) {
            if (state.is(Blocks.EMERALD_BLOCK)) {
                Player breaker = event.getPlayer();
                aggroNearbyGolems(level, pos, breaker);
            }
        }
    }

    /**
     * Angers all non-player-spawned iron golems and emerald golems within
     * {@link Config#EMERALD_BLOCK_AGGRO_RADIUS} of the broken emerald block.
     */
    private static void aggroNearbyGolems(ServerLevel level, BlockPos pos, Player player) {
        double r = Config.EMERALD_BLOCK_AGGRO_RADIUS;
        AABB searchArea = new AABB(pos).inflate(r);

        // Emerald golems extend IronGolem, so one query handles both types.
        for (IronGolem golem : level.getEntitiesOfClass(IronGolem.class, searchArea)) {
            if (!golem.isPlayerCreated()
                    && !(golem instanceof EmeraldGolem emeraldGolem
                    && emeraldGolem.isOwnedByBank(player.getUUID()))) {
                golem.setTarget(player);
                golem.setPersistentAngerTarget(player.getUUID());
                golem.startPersistentAngerTimer();
            }
        }
    }

    // Farmland-specific event handlers

    /**
     * When farmland is trampled (entity jumps on it), add the position to the
     * repair queue so a farmer can restore it.
     */
    @SubscribeEvent
    public static void onFarmlandTrampled(BlockEvent.FarmlandTrampleEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        VillageRegistryData data = VillageRegistryData.get(level);
        VillageRecord village = data.getVillageFor(pos);
        if (village != null && village.addToRepairQueue(pos)) {
            data.setDirty();
        }
    }

    /**
     * Listens for block changes that affect the farmland registry.
     * Called via NeighborNotifyEvent to detect farmland→dirt transitions
     * that are not covered by trample events (e.g. dehydration).
     */
    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        VillageRegistryData data = VillageRegistryData.get(level);
        VillageRecord village = data.getVillageFor(pos);
        if (village == null) {
            return;
        }

        BlockState newState = event.getLevel().getBlockState(pos);

        if (newState.getBlock() instanceof DoorBlock) {
            BlockPos basePos = VillageRecord.doorBasePos(pos, newState);
            boolean added = village.addDoor(basePos, newState);
            EmeraldCapitalism.LOGGER.debug(
                    "[ECAP][DoorCache] NEIGHBOR door state changed pos={} base={} added={} village={} cacheInitialized={}",
                    pos, basePos, added, village.getVillageId(), village.isCacheInitialized());
            if (added) {
                data.setDirty();
            }
        } else if (newState.getBlock() instanceof FarmBlock) {
            // If new farmland is placed within the bounding box, add it to the registry.
            if (village.addFarmland(pos)) {
                data.setDirty();
            }
        } else if (village.addToRepairQueue(pos)) {
            // If tracked farmland became dirt (for example, through dehydration), queue one repair.
            data.setDirty();
        }
    }
}
