package com.orangevillager61.emeraldcapitalism;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final int TICKS_PER_SECOND = 20;


    // Hunger Settings


    private static final ModConfigSpec.BooleanValue VILLAGERS_CAN_STARVE_TO_DEATH = BUILDER
            .comment("If true, villagers can starve to death. If false, they stop taking starvation damage at 2 HP.")
            .translation("emeraldcapitalism.configuration.villagersCanStarveToDeath")
            .define("villagersCanStarveToDeath", true);


    // Villager Name Settings


    private static final ModConfigSpec.BooleanValue ENABLE_VILLAGER_NAMES = BUILDER
            .comment("If enabled, villagers will be named based on Emerald Capitalism's Unique Language System.")
            .translation("emeraldcapitalism.configuration.enableVillagerNames")
            .define("enableVillagerNames", true);


    // Reputation Settings


    private static final ModConfigSpec.BooleanValue PROPORTIONAL_VILLAGER_REPUTATION = BUILDER
            .comment("If true, villager reputation loss from player damage is proportional to damage dealt (5 points per HP, 10 per heart).",
                     "In vanilla, hitting a villager always costs 25 reputation regardless of damage.",
                     "With this enabled: weak hits lose less reputation, strong hits lose more.",
                     "Example: 1 heart of damage = 10 reputation loss (vs 25 in vanilla)",
                     "Example: 2.5 hearts of damage = 25 reputation loss (same as vanilla)")
            .translation("emeraldcapitalism.configuration.proportionalVillagerReputation")
            .define("proportionalVillagerReputation", true);

    private static final ModConfigSpec.BooleanValue REDACT_NON_OP_VILLAGE_POI_DETAILS = BUILDER
            .comment("Setting this to true removes coordinate data from overlays for non-operators.")
            .translation("emeraldcapitalism.configuration.redactNonOpPoiDetails")
            .define("redactNonOpPoiDetails", true);


    // AI Settings


    // All AI settings are grouped under AI in the configuration screen.

    private static final ModConfigSpec.BooleanValue ENABLE_FARMLAND_REPAIR = BUILDER
            .comment("If true, farmer villagers will pathfind to trampled or broken farmland and retill it.")
            .translation("emeraldcapitalism.configuration.ai.villagerAI.enableFarmlandRepair")
            .define("ai.villagerAI.enableFarmlandRepair", true);

    private static final ModConfigSpec.BooleanValue ENABLE_FENCE_GATE_INTERACTION = BUILDER
            .comment("If true, villagers can open and close fence gates during pathfinding.")
            .translation("emeraldcapitalism.configuration.ai.villagerAI.enableFenceGateInteraction")
            .define("ai.villagerAI.enableFenceGateInteraction", true);

    private static final ModConfigSpec.BooleanValue ENABLE_VILLAGER_STATS_SHIFT_CLICK = BUILDER
            .comment("If true, shift-right-clicking a villager opens the villager statistics screen.")
            .translation("emeraldcapitalism.configuration.ai.villagerAI.enableVillagerStatsShiftClick")
            .define("ai.villagerAI.enableVillagerStatsShiftClick", true);

    private static final ModConfigSpec.BooleanValue ENABLE_BOAT_AVOIDANCE = BUILDER
            .comment("If true, villagers will avoid nearby boats and try to keep a few blocks away.")
            .translation("emeraldcapitalism.configuration.ai.villagerAI.enableBoatAvoidance")
            .define("ai.villagerAI.enableBoatAvoidance", true);

    private static final ModConfigSpec.BooleanValue ENABLE_LADDER_TRAVERSAL = BUILDER
            .comment("If true, villagers and emerald golems can pathfind through and climb ladders and other climbable blocks.",
                     "They will ascend and descend ladders as part of their normal navigation.",
                     "A ladder occupancy system prevents multiple villagers from climbing the same ladder simultaneously.")
            .translation("emeraldcapitalism.configuration.ai.villagerAI.enableLadderTraversal")
            .define("ai.villagerAI.enableLadderTraversal", true);

    private static final ModConfigSpec.DoubleValue VILLAGER_MOVEMENT_SPEED_MULTIPLIER = BUILDER
            .comment("Global villager movement-speed multiplier.",
                     "1.0 is vanilla speed; 2.0 is twice vanilla speed.",
                     "Default: 1.15 (15% faster).")
            .translation("emeraldcapitalism.configuration.ai.villagerAI.movementSpeedMultiplier")
            .defineInRange("ai.villagerAI.movementSpeedMultiplier", 1.15D, 0.1D, 10.0D);


    // AI: Golem Settings


    private static final ModConfigSpec.IntValue IRON_GOLEM_VERTICAL_REACH_ABOVE_HEAD = BUILDER
            .comment("Additional vertical melee reach for iron golems above their heads, in blocks.",
                     "Allowed range: 1 to 16 blocks. Default: 5 blocks.")
            .translation("emeraldcapitalism.configuration.ai.golemAI.ironGolemVerticalReachAboveHead")
            .defineInRange("ai.golemAI.ironGolemVerticalReachAboveHead", 5, 1, 16);

    private static final ModConfigSpec.BooleanValue EMERALD_BLOCK_AGGRO_ENABLED = BUILDER
            .comment("If true, breaking an emerald block near a non-player-spawned iron golem or emerald golem",
                     "will cause those golems to become hostile toward the player.",
                     "Search radius: 16 blocks.")
            .translation("emeraldcapitalism.configuration.ai.golemAI.emeraldBlockAggro")
            .define("ai.golemAI.emeraldBlockAggro", true);

    private static final ModConfigSpec.BooleanValue EMERALD_GOLEM_AMBUSH_ENABLED = BUILDER
            .comment("If true, an emerald golem ambushes the player on their initial world spawn.")
            .translation("emeraldcapitalism.configuration.ai.golemAI.emeraldGolemAmbush.enabled")
            .define("ai.golemAI.emeraldGolemAmbush.enabled", true);

    private static final ModConfigSpec.BooleanValue EMERALD_GOLEM_AMBUSH_ON_RESPAWN = BUILDER
            .comment("If true, an emerald golem ambushes the player on every death respawn.")
            .translation("emeraldcapitalism.configuration.ai.golemAI.emeraldGolemAmbush.onRespawn")
            .define("ai.golemAI.emeraldGolemAmbush.onRespawn", false);

    private static final ModConfigSpec.IntValue EMERALD_GOLEM_AMBUSH_SPAWN_DISTANCE = BUILDER
            .comment("Horizontal distance in blocks at which an ambush emerald golem tries to spawn",
                     "from the player. Default: 16 blocks.")
            .translation("emeraldcapitalism.configuration.ai.golemAI.emeraldGolemAmbush.spawnDistance")
            .defineInRange("ai.golemAI.emeraldGolemAmbush.spawnDistance", 16, 8, 32);

    private static final ModConfigSpec.DoubleValue EMERALD_GOLEM_AMBUSH_HEALTH = BUILDER
            .comment("Health of an ambush emerald golem in HP. Default: 2 HP.")
            .translation("emeraldcapitalism.configuration.ai.golemAI.emeraldGolemAmbush.health")
            .defineInRange("ai.golemAI.emeraldGolemAmbush.health", 2.0D, 0.5D, 50.0D);

    private static final ModConfigSpec.IntValue EMERALD_GOLEM_AMBUSH_MIN_DELAY_SECONDS = BUILDER
            .comment("Minimum delay before an ambush emerald golem attacks its target",
                     "in seconds. Default: 3 seconds.")
            .translation("emeraldcapitalism.configuration.ai.golemAI.emeraldGolemAmbush.minAttackDelaySeconds")
            .defineInRange("ai.golemAI.emeraldGolemAmbush.minAttackDelaySeconds", 3, 0, 60);

    private static final ModConfigSpec.IntValue EMERALD_GOLEM_AMBUSH_MAX_DELAY_SECONDS = BUILDER
            .comment("Maximum delay before an ambush emerald golem attacks its target",
                     "in seconds. Default: 5 seconds.")
            .translation("emeraldcapitalism.configuration.ai.golemAI.emeraldGolemAmbush.maxAttackDelaySeconds")
            .defineInRange("ai.golemAI.emeraldGolemAmbush.maxAttackDelaySeconds", 5, 0, 60);

    private static final ModConfigSpec.BooleanValue EMERALD_GOLEM_AMBUSH_DROPS_VILLAGE_MAP = BUILDER
            .comment("If true, an ambush emerald golem drops one Village Map when it dies.")
            .translation("emeraldcapitalism.configuration.ai.golemAI.emeraldGolemAmbush.dropsVillageMap")
            .define("ai.golemAI.emeraldGolemAmbush.dropsVillageMap", true);


    // Illness: Zombie Plague


    private static final ModConfigSpec.IntValue ZOMBIE_VIRUS_PHASE_ONE_DURATION_SECONDS = BUILDER
            .comment("How long Zombie Plague remains in the greening phase before the turning phase begins.",
                     "Unit: seconds. Default: 600 seconds (10 minutes).")
            .translation("emeraldcapitalism.configuration.illness.zombieVirus.phaseOneDurationSeconds")
            .defineInRange("illness.zombieVirus.phaseOneDurationSeconds", 600, 1, 86_400);

    private static final ModConfigSpec.IntValue ZOMBIE_VIRUS_HIT_TIME_REDUCTION_SECONDS = BUILDER
            .comment("How much time a later Zombie hit removes from the greening phase of Zombie Plague.",
                     "Unit: seconds. Default: 20 seconds.")
            .translation("emeraldcapitalism.configuration.illness.zombieVirus.hitTimeReductionSeconds")
            .defineInRange("illness.zombieVirus.hitTimeReductionSeconds", 20, 1, 3_600);

    private static final ModConfigSpec.IntValue ZOMBIE_VIRUS_ROTTEN_FLESH_INFECTION_CHANCE_PERCENT = BUILDER
            .comment("Chance of contracting Zombie Plague when consuming rotten flesh.",
                     "Default: 2%. Range: 0% to 100%.")
            .translation("emeraldcapitalism.configuration.illness.zombieVirus.rottenFleshInfectionChancePercent")
            .defineInRange("illness.zombieVirus.rottenFleshInfectionChancePercent", 2, 0, 100);

    /** Radius in blocks to search for golems when an emerald block is broken. */
    public static final double EMERALD_BLOCK_AGGRO_RADIUS = 16.0;


    // AI: Zombie Settings


    private static final ModConfigSpec.BooleanValue ENABLE_ZOMBIE_VILLAGER_SUN_AVOIDANCE = BUILDER
            .comment("If true, zombie villagers avoid pathfinding into sunlight when they have no head protection.")
            .translation("emeraldcapitalism.configuration.ai.zombieAI.enableZombieVillagerSunAvoidance")
            .define("ai.zombieAI.enableZombieVillagerSunAvoidance", true);

    private static final ModConfigSpec.BooleanValue ALWAYS_CONVERT_VILLAGERS_TO_ZOMBIE_VILLAGERS = BUILDER
            .comment("If true, zombies always convert killed villagers into zombie villagers on Easy, Normal, and Hard difficulty.",
                     "If false, vanilla conversion chances apply: 0% on Easy, 50% on Normal, and 100% on Hard.")
            .translation("emeraldcapitalism.configuration.ai.zombieAI.alwaysConvertVillagersToZombieVillagers")
            .define("ai.zombieAI.alwaysConvertVillagersToZombieVillagers", true);

    private static final ModConfigSpec.BooleanValue ZOMBIES_CAN_BREAK_DOORS_ON_ANY_DIFFICULTY = BUILDER
            .comment("If true, zombies with the door-breaking ability can break wooden doors on any difficulty.",
                     "If false, door breaking is limited to Hard difficulty, as in vanilla.")
            .translation("emeraldcapitalism.configuration.ai.zombieAI.zombiesCanBreakDoorsOnAnyDifficulty")
            .define("ai.zombieAI.zombiesCanBreakDoorsOnAnyDifficulty", true);

    private static final ModConfigSpec.IntValue ZOMBIE_DOOR_BREAKING_CHANCE_PERCENT = BUILDER
            .comment("Percentage of newly spawned zombies that receive the door-breaking ability.",
                     "Default: 50%. Range: 0% to 100%.")
            .translation("emeraldcapitalism.configuration.ai.zombieAI.zombieDoorBreakingChancePercent")
            .defineInRange("ai.zombieAI.zombieDoorBreakingChancePercent", 50, 0, 100);


    // Village Farm Generation Settings


    // Pre-generation village farm pool modification settings were removed.
    // Outskirt farm placement settings below remain active.


    // Village Registry Scanning Settings


    private static final ModConfigSpec.IntValue VILLAGE_COMMAND_PERMISSION_LEVEL = BUILDER
            .comment("Permission level required to use /ecap village commands (0=everyone, 2=operator, 4=server owner).")
            .translation("emeraldcapitalism.configuration.advanced.villageRegistry.commandPermissionLevel")
            .defineInRange("advanced.villageRegistry.commandPermissionLevel", 2, 0, 4);

    private static final ModConfigSpec.IntValue MAX_VILLAGE_NAME_LENGTH = BUILDER
            .comment("Maximum number of characters allowed in a village name.")
            .translation("emeraldcapitalism.configuration.advanced.villageRegistry.maxVillageNameLength")
            .defineInRange("advanced.villageRegistry.maxVillageNameLength", 64, 1, 64);

    private static final ModConfigSpec.IntValue GOVERNOR_CANDIDATE_OPINION_THRESHOLD = BUILDER
            .comment("Village opinion must be above this positive threshold to become a governor candidate.")
            .translation("emeraldcapitalism.configuration.advanced.villageRegistry.governorCandidateOpinionThreshold")
            .defineInRange("advanced.villageRegistry.governorCandidateOpinionThreshold", 99, 1, 100_000);

    private static final ModConfigSpec.IntValue GOVERNOR_HOSTILE_OPINION_THRESHOLD = BUILDER
            .comment("Village opinion at or below this negative threshold makes a player hostile to the village.")
            .translation("emeraldcapitalism.configuration.advanced.villageRegistry.governorHostileOpinionThreshold")
            .defineInRange("advanced.villageRegistry.governorHostileOpinionThreshold", -100, -100_000, -1);


    private static final ModConfigSpec.BooleanValue ENABLE_WORLDGEN_VILLAGE_ROOT_NAMING = BUILDER
            .comment("If true, villages detected during worldgen use canonical root-based naming.",
                     "If false, villages use legacy numbered names (Village 1, Village 2, ...).")
            .translation("emeraldcapitalism.configuration.advanced.villageRegistry.enableWorldgenVillageRootNaming")
            .define("advanced.villageRegistry.enableWorldgenVillageRootNaming", true);

    private static final ModConfigSpec.IntValue VILLAGE_SCAN_INTERVAL_TICKS = BUILDER
            .comment("Interval between village scan cycles.",
                     "Unit: ticks (20 ticks = 1 second).",
                     "Default: 1800 ticks (90 seconds).")
            .translation("emeraldcapitalism.configuration.advanced.villageRegistry.scanIntervalTicks")
            .defineInRange("advanced.villageRegistry.scanIntervalTicks", 1800, 20, 72000);

    private static final ModConfigSpec.IntValue VILLAGE_SCAN_VILLAGER_BUDGET = BUILDER
            .comment("Maximum number of villagers to process per tick during a scan cycle.")
            .translation("emeraldcapitalism.configuration.advanced.villageRegistry.villagerBudgetPerTick")
            .defineInRange("advanced.villageRegistry.villagerBudgetPerTick", 8, 1, 64);

    private static final ModConfigSpec.IntValue VILLAGE_FULL_SCAN_BLOCK_BUDGET = BUILDER
            .comment("Maximum number of loaded block positions inspected per tick by a full village scan.",
                     "Higher values finish manual scans sooner but use more server tick time.")
            .translation("emeraldcapitalism.configuration.advanced.villageRegistry.fullScanBlockBudgetPerTick")
            .defineInRange("advanced.villageRegistry.fullScanBlockBudgetPerTick", 16_384, 1_024, 65_536);

    private static final ModConfigSpec.IntValue VILLAGE_INITIAL_SCAN_CHUNK_LOAD_POOL_SIZE = BUILDER
            .comment("Maximum number of chunks that adaptive initial village scans may load asynchronously at once per level.",
                     "Set to 0 to wait for unloaded chunks to become available instead of publishing a partial initial scan.")
            .translation("emeraldcapitalism.configuration.advanced.villageRegistry.initialScanChunkLoadPoolSize")
            .defineInRange("advanced.villageRegistry.initialScanChunkLoadPoolSize", 3, 0, 8);

    private static final ModConfigSpec.IntValue VILLAGE_INITIAL_SCAN_CHUNK_LOAD_CAP_PER_VILLAGE = BUILDER
            .comment("Maximum unloaded chunks that one adaptive initial village scan may request per progressive batch.",
                     "A new batch begins after the prior batch is scanned; scan progress is preserved.")
            .translation("emeraldcapitalism.configuration.advanced.villageRegistry.initialScanChunkLoadCapPerVillage")
            .defineInRange("advanced.villageRegistry.initialScanChunkLoadCapPerVillage", 3, 0, 16);

    private static final ModConfigSpec.IntValue VILLAGE_SCAN_DEPARTURE_THRESHOLD = BUILDER
            .comment("Number of consecutive scans a villager must be absent before removal.")
            .translation("emeraldcapitalism.configuration.advanced.villageRegistry.departureGraceThreshold")
            .defineInRange("advanced.villageRegistry.departureGraceThreshold", 3, 1, 20);

    private static final ModConfigSpec.IntValue MANUAL_FULL_SCAN_PLAYER_COOLDOWN_TICKS = BUILDER
            .comment("Per-player cooldown for manual full-scan requests.",
                     "Unit: ticks (20 ticks = 1 second).",
                     "Default: 300 ticks (15 seconds).")
            .translation("emeraldcapitalism.configuration.advanced.villageRegistry.manualFullScanPlayerCooldownTicks")
            .defineInRange("advanced.villageRegistry.manualFullScanPlayerCooldownTicks", TICKS_PER_SECOND * 15, TICKS_PER_SECOND, TICKS_PER_SECOND * 60 * 30);

    private static final ModConfigSpec.IntValue MANUAL_EXPAND_BOUNDS_PLAYER_COOLDOWN_TICKS = BUILDER
            .comment("Per-player cooldown for expand-bounds requests.",
                     "Unit: ticks (20 ticks = 1 second).",
                     "Default: 1800 ticks (90 seconds).")
            .translation("emeraldcapitalism.configuration.advanced.villageRegistry.manualExpandBoundsPlayerCooldownTicks")
            .defineInRange("advanced.villageRegistry.manualExpandBoundsPlayerCooldownTicks", TICKS_PER_SECOND * 90, TICKS_PER_SECOND, TICKS_PER_SECOND * 60 * 30);

    private static final ModConfigSpec.IntValue MANUAL_SCAN_PER_VILLAGE_COOLDOWN_TICKS = BUILDER
            .comment("Per-village cooldown for heavy manual scans to prevent alternating-player abuse.",
                     "Unit: ticks (20 ticks = 1 second).",
                     "Default: 200 ticks (10 seconds).")
            .translation("emeraldcapitalism.configuration.advanced.villageRegistry.manualScanPerVillageCooldownTicks")
            .defineInRange("advanced.villageRegistry.manualScanPerVillageCooldownTicks", TICKS_PER_SECOND * 10, TICKS_PER_SECOND, TICKS_PER_SECOND * 60 * 30);


    // Advanced: Outskirt Farm Placement Settings


    public static ModConfigSpec.BooleanValue outskirtFarmsEnabled = BUILDER
            .comment("Enable outskirt farm placement around detected villages.",
                     "When enabled, farms are placed along village paths and in empty",
                     "gaps within the village bounding box, connected by dirt paths.")
            .translation("emeraldcapitalism.configuration.advanced.outskirtFarms.enabled")
            .define("advanced.outskirtFarms.enabled", true);

    public static ModConfigSpec.IntValue outskirtFarmsBaseFarmCount = BUILDER
            .comment("Base number of farms to place per village.",
                     "The total target is: base + (perChunkBonus * chunks in village BB).",
                     "Fewer may be placed if terrain is unsuitable.")
            .translation("emeraldcapitalism.configuration.advanced.outskirtFarms.baseFarmCount")
            .defineInRange("advanced.outskirtFarms.baseFarmCount", 3, 0, 12);

    public static ModConfigSpec.IntValue outskirtFarmsPerChunkBonus = BUILDER
            .comment("Additional farms per chunk covered by the village bounding box.",
                     "Larger villages get more farms proportionally.",
                     "The total target is: baseFarmCount + (this * chunks in village BB).")
            .translation("emeraldcapitalism.configuration.advanced.outskirtFarms.perChunkBonus")
            .defineInRange("advanced.outskirtFarms.perChunkBonus", 1, 0, 4);

    public static ModConfigSpec.IntValue outskirtFarmsMaxCount = BUILDER
            .comment("Maximum number of outskirt farms to place per village.",
                     "Caps the dynamic count (base + perChunk * chunks).")
            .translation("emeraldcapitalism.configuration.advanced.outskirtFarms.maxCount")
            .defineInRange("advanced.outskirtFarms.maxCount", 8, 1, 32);


    public static ModConfigSpec.BooleanValue outskirtFarmsPathsEnabled = BUILDER
            .comment("Enable dirt path generation connecting outskirt farms to the village.",
                     "Paths use biome-appropriate materials.")
            .translation("emeraldcapitalism.configuration.advanced.outskirtFarms.pathsEnabled")
            .define("advanced.outskirtFarms.pathsEnabled", true);

    public static ModConfigSpec.BooleanValue outskirtFarmsWaterContainmentEnabled = BUILDER
            .comment("Enable village-wide flowing-water containment around farmland.",
                     "When enabled, farmland-adjacent water escape routes in detected villages",
                     "and placed outskirt farms are plugged with dirt.",
                     "Disable to fully turn off the dirt-plugging behavior.")
            .translation("emeraldcapitalism.configuration.advanced.outskirtFarms.waterContainmentEnabled")
            .define("advanced.outskirtFarms.waterContainmentEnabled", true);

    @SuppressWarnings("unchecked")
    public static ModConfigSpec.ConfigValue<List<? extends String>> outskirtFarmsBlockBlacklist =
            (ModConfigSpec.ConfigValue<List<? extends String>>) (Object) BUILDER
            .comment("Blocks that indicate non-natural terrain (e.g. another structure).",
                     "If any of these blocks are found in a candidate farm footprint,",
                     "placement is skipped. Acts as a fallback for detecting structures",
                     "not registered in Minecraft's structure system.",
                     "Set to an empty list to disable this check.",
                     "Format: \"minecraft:cobblestone\", \"minecraft:oak_planks\", etc.")
            .translation("emeraldcapitalism.configuration.advanced.outskirtFarms.blockBlacklist")
            .defineListAllowEmpty("advanced.outskirtFarms.blockBlacklist", List.of(
                    "minecraft:cobblestone",
                    "minecraft:mossy_cobblestone",
                    "minecraft:stone_bricks",
                    "minecraft:mossy_stone_bricks",
                    "minecraft:cracked_stone_bricks",
                    "minecraft:oak_planks",
                    "minecraft:spruce_planks",
                    "minecraft:birch_planks",
                    "minecraft:jungle_planks",
                    "minecraft:acacia_planks",
                    "minecraft:dark_oak_planks",
                    "minecraft:mangrove_planks",
                    "minecraft:cherry_planks",
                    "minecraft:bamboo_planks",
                    "minecraft:crimson_planks",
                    "minecraft:warped_planks",
                    "minecraft:bricks",
                    "minecraft:cut_sandstone",
                    "minecraft:smooth_sandstone",
                    "minecraft:chiseled_sandstone",
                    "minecraft:terracotta",
                    "minecraft:white_terracotta",
                    "minecraft:orange_terracotta",
                    "minecraft:light_gray_terracotta",
                    "minecraft:stripped_oak_log",
                    "minecraft:stripped_spruce_log",
                    "minecraft:stripped_birch_log",
                    "minecraft:stripped_jungle_log",
                    "minecraft:stripped_acacia_log",
                    "minecraft:stripped_dark_oak_log",
                    "minecraft:glass",
                    "minecraft:glass_pane",
                    "minecraft:white_stained_glass",
                    "minecraft:orange_stained_glass",
                    "minecraft:magenta_stained_glass",
                    "minecraft:light_blue_stained_glass",
                    "minecraft:yellow_stained_glass",
                    "minecraft:lime_stained_glass",
                    "minecraft:pink_stained_glass",
                    "minecraft:gray_stained_glass",
                    "minecraft:light_gray_stained_glass",
                    "minecraft:cyan_stained_glass",
                    "minecraft:purple_stained_glass",
                    "minecraft:blue_stained_glass",
                    "minecraft:brown_stained_glass",
                    "minecraft:green_stained_glass",
                    "minecraft:red_stained_glass",
                    "minecraft:black_stained_glass",
                    "minecraft:white_stained_glass_pane",
                    "minecraft:orange_stained_glass_pane",
                    "minecraft:magenta_stained_glass_pane",
                    "minecraft:light_blue_stained_glass_pane",
                    "minecraft:yellow_stained_glass_pane",
                    "minecraft:lime_stained_glass_pane",
                    "minecraft:pink_stained_glass_pane",
                    "minecraft:gray_stained_glass_pane",
                    "minecraft:light_gray_stained_glass_pane",
                    "minecraft:cyan_stained_glass_pane",
                    "minecraft:purple_stained_glass_pane",
                    "minecraft:blue_stained_glass_pane",
                    "minecraft:brown_stained_glass_pane",
                    "minecraft:green_stained_glass_pane",
                    "minecraft:red_stained_glass_pane",
                    "minecraft:black_stained_glass_pane",
                    "minecraft:oak_door",
                    "minecraft:spruce_door",
                    "minecraft:birch_door",
                    "minecraft:jungle_door",
                    "minecraft:acacia_door",
                    "minecraft:dark_oak_door",
                    "minecraft:mangrove_door",
                    "minecraft:cherry_door",
                    "minecraft:bamboo_door",
                    "minecraft:crimson_door",
                    "minecraft:warped_door",
                    "minecraft:iron_door",
                    "minecraft:copper_door",
                    "minecraft:exposed_copper_door",
                    "minecraft:weathered_copper_door",
                    "minecraft:oxidized_copper_door",
                    "minecraft:waxed_copper_door",
                    "minecraft:waxed_exposed_copper_door",
                    "minecraft:waxed_weathered_copper_door",
                    "minecraft:waxed_oxidized_copper_door",
                    "minecraft:bookshelf",
                    "minecraft:iron_bars"
            ), () -> "", obj -> obj instanceof String s && ResourceLocation.tryParse(s) != null);


    // Advanced: Emerald Golem Formula


    // Golem target = max(0, ceil(scale * (sqrt(reserve) - offset) + base)).
    // Only the three numeric terms are configurable; the curve and zero clamp are fixed.

    private static final ModConfigSpec.DoubleValue EMERALD_GOLEM_FORMULA_SCALE = BUILDER
            .comment("Scaling factor applied to the square-root emerald reserve.",
                     "Default: 0.24.")
            .translation("emeraldcapitalism.configuration.advanced.emeraldGolemFormula.formulaScale")
            .defineInRange("advanced.emeraldGolemFormula.formulaScale", 0.24D, 0.0D, 100.0D);

    private static final ModConfigSpec.DoubleValue EMERALD_GOLEM_RESERVE_OFFSET = BUILDER
            .comment("Reserve offset subtracted from the square-root emerald reserve.",
                     "Default: 6.0.")
            .translation("emeraldcapitalism.configuration.advanced.emeraldGolemFormula.reserveOffset")
            .defineInRange("advanced.emeraldGolemFormula.reserveOffset", 6.0D, 0.0D, 1_000_000.0D);

    private static final ModConfigSpec.DoubleValue EMERALD_GOLEM_BASE_GOLEMS = BUILDER
            .comment("Baseline added to the scaled reserve value before rounding.",
                     "Default: 1.0.")
            .translation("emeraldcapitalism.configuration.advanced.emeraldGolemFormula.baseGolems")
            .defineInRange("advanced.emeraldGolemFormula.baseGolems", 1.0D, 0.0D, 1_000_000.0D);
    // Emerald blocks count as nine emeralds in reserve totals; the computed target
    // is rounded up and clamped to zero.


    // Books Settings


    private static final ModConfigSpec.BooleanValue ENABLE_BOOKS_IN_CREATIVE_TAB = BUILDER
            .comment("If true, all authored books are added to the Emerald Capitalism creative tab.",
                     "Warning: The mod author recommends exploring the world to find the books.")
            .translation("emeraldcapitalism.configuration.books.enableBooksInCreativeTab")
            .define("books.enableBooksInCreativeTab", false);

    // Build the spec


    static final ModConfigSpec SPEC = BUILDER.build();


    // Cached config values (resolved on config load/reload)


    public static boolean villagersCanStarveToDeath;
    public static boolean enableVillagerNames;
    public static boolean proportionalVillagerReputation;
    public static boolean enableFarmlandRepair;
    public static boolean enableFenceGateInteraction;
    public static boolean enableVillagerStatsShiftClick;
    public static boolean enableBoatAvoidance;
    public static boolean enableLadderTraversal;
    public static boolean enableZombieVillagerSunAvoidance = true;
    public static boolean alwaysConvertVillagersToZombieVillagers = true;
    public static boolean zombiesCanBreakDoorsOnAnyDifficulty = true;
    public static int zombieDoorBreakingChancePercent = 50;
    public static double villagerMovementSpeedMultiplier = 1.15D;
    public static int villageCommandPermissionLevel;
    public static int maxVillageNameLength;
    public static int governorCandidateOpinionThreshold = 99;
    public static int governorHostileOpinionThreshold = -100;
    public static boolean enableWorldgenVillageRootNaming = true;
    public static int villageScanIntervalTicks;
    public static int villageScanVillagerBudget;
    public static int villageFullScanBlockBudget = 16_384;
    public static int villageInitialScanChunkLoadPoolSize = 3;
    public static int villageInitialScanChunkLoadCapPerVillage = 3;
    public static int villageScanDepartureThreshold;
    public static boolean redactNonOpVillagePoiDetails;
    public static int manualFullScanPlayerCooldownTicks;
    public static int manualExpandBoundsPlayerCooldownTicks;
    public static int manualScanPerVillageCooldownTicks;
    public static int ironGolemVerticalReachAboveHead = 5;
    public static boolean emeraldBlockAggroEnabled;
    public static boolean emeraldGolemAmbushEnabled = true;
    public static boolean emeraldGolemAmbushOnRespawn;
    public static int emeraldGolemAmbushSpawnDistance = 16;
    public static double emeraldGolemAmbushHealth = 2.0D;
    public static int emeraldGolemAmbushMinDelaySeconds = 3;
    public static int emeraldGolemAmbushMaxDelaySeconds = 5;
    public static boolean emeraldGolemAmbushDropsVillageMap = true;
    public static int zombieVirusPhaseOneDurationSeconds = 600;
    public static int zombieVirusHitTimeReductionSeconds = 20;
    public static int zombieVirusRottenFleshInfectionChancePercent = 2;
    public static double emeraldGolemFormulaScale = 0.24D;
    public static double emeraldGolemReserveOffset = 6.0D;
    public static double emeraldGolemBaseGolems = 1.0D;
    public static boolean enableBooksInCreativeTab = false;

    public static void onLoad(final ModConfigEvent event) {
        villagersCanStarveToDeath = VILLAGERS_CAN_STARVE_TO_DEATH.get();
        enableVillagerNames = ENABLE_VILLAGER_NAMES.get();
        proportionalVillagerReputation = PROPORTIONAL_VILLAGER_REPUTATION.get();
        enableFarmlandRepair = ENABLE_FARMLAND_REPAIR.get();
        enableFenceGateInteraction = ENABLE_FENCE_GATE_INTERACTION.get();
        enableVillagerStatsShiftClick = ENABLE_VILLAGER_STATS_SHIFT_CLICK.get();
        enableBoatAvoidance = ENABLE_BOAT_AVOIDANCE.get();
        enableLadderTraversal = ENABLE_LADDER_TRAVERSAL.get();
        enableZombieVillagerSunAvoidance = ENABLE_ZOMBIE_VILLAGER_SUN_AVOIDANCE.get();
        alwaysConvertVillagersToZombieVillagers = ALWAYS_CONVERT_VILLAGERS_TO_ZOMBIE_VILLAGERS.get();
        zombiesCanBreakDoorsOnAnyDifficulty = ZOMBIES_CAN_BREAK_DOORS_ON_ANY_DIFFICULTY.get();
        zombieDoorBreakingChancePercent = ZOMBIE_DOOR_BREAKING_CHANCE_PERCENT.get();
        villagerMovementSpeedMultiplier = VILLAGER_MOVEMENT_SPEED_MULTIPLIER.get();
        villageCommandPermissionLevel = VILLAGE_COMMAND_PERMISSION_LEVEL.get();
        maxVillageNameLength = MAX_VILLAGE_NAME_LENGTH.get();
        governorCandidateOpinionThreshold = GOVERNOR_CANDIDATE_OPINION_THRESHOLD.get();
        governorHostileOpinionThreshold = GOVERNOR_HOSTILE_OPINION_THRESHOLD.get();
        enableWorldgenVillageRootNaming = ENABLE_WORLDGEN_VILLAGE_ROOT_NAMING.get();
        villageScanIntervalTicks = VILLAGE_SCAN_INTERVAL_TICKS.get();
        villageScanVillagerBudget = VILLAGE_SCAN_VILLAGER_BUDGET.get();
        villageFullScanBlockBudget = VILLAGE_FULL_SCAN_BLOCK_BUDGET.get();
        villageInitialScanChunkLoadPoolSize = VILLAGE_INITIAL_SCAN_CHUNK_LOAD_POOL_SIZE.get();
        villageInitialScanChunkLoadCapPerVillage = VILLAGE_INITIAL_SCAN_CHUNK_LOAD_CAP_PER_VILLAGE.get();
        villageScanDepartureThreshold = VILLAGE_SCAN_DEPARTURE_THRESHOLD.get();
        redactNonOpVillagePoiDetails = REDACT_NON_OP_VILLAGE_POI_DETAILS.get();
        manualFullScanPlayerCooldownTicks = MANUAL_FULL_SCAN_PLAYER_COOLDOWN_TICKS.get();
        manualExpandBoundsPlayerCooldownTicks = MANUAL_EXPAND_BOUNDS_PLAYER_COOLDOWN_TICKS.get();
        manualScanPerVillageCooldownTicks = MANUAL_SCAN_PER_VILLAGE_COOLDOWN_TICKS.get();
        ironGolemVerticalReachAboveHead = IRON_GOLEM_VERTICAL_REACH_ABOVE_HEAD.get();
        emeraldBlockAggroEnabled = EMERALD_BLOCK_AGGRO_ENABLED.get();
        emeraldGolemAmbushEnabled = EMERALD_GOLEM_AMBUSH_ENABLED.get();
        emeraldGolemAmbushOnRespawn = EMERALD_GOLEM_AMBUSH_ON_RESPAWN.get();
        emeraldGolemAmbushSpawnDistance = EMERALD_GOLEM_AMBUSH_SPAWN_DISTANCE.get();
        emeraldGolemAmbushHealth = EMERALD_GOLEM_AMBUSH_HEALTH.get();
        emeraldGolemAmbushMinDelaySeconds = EMERALD_GOLEM_AMBUSH_MIN_DELAY_SECONDS.get();
        emeraldGolemAmbushMaxDelaySeconds = EMERALD_GOLEM_AMBUSH_MAX_DELAY_SECONDS.get();
        emeraldGolemAmbushDropsVillageMap = EMERALD_GOLEM_AMBUSH_DROPS_VILLAGE_MAP.get();
        zombieVirusPhaseOneDurationSeconds = ZOMBIE_VIRUS_PHASE_ONE_DURATION_SECONDS.get();
        zombieVirusHitTimeReductionSeconds = ZOMBIE_VIRUS_HIT_TIME_REDUCTION_SECONDS.get();
        zombieVirusRottenFleshInfectionChancePercent = ZOMBIE_VIRUS_ROTTEN_FLESH_INFECTION_CHANCE_PERCENT.get();
        emeraldGolemFormulaScale = EMERALD_GOLEM_FORMULA_SCALE.get();
        emeraldGolemReserveOffset = EMERALD_GOLEM_RESERVE_OFFSET.get();
        emeraldGolemBaseGolems = EMERALD_GOLEM_BASE_GOLEMS.get();
        enableBooksInCreativeTab = ENABLE_BOOKS_IN_CREATIVE_TAB.get();
    }

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }
}
