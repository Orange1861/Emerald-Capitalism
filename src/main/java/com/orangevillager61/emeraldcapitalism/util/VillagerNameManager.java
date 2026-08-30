package com.orangevillager61.emeraldcapitalism.util;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.naming.villager.VillagerNamingData;
import com.orangevillager61.emeraldcapitalism.world.village.naming.villager.VillagerNamingDataLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-authoritative assembler for the Brief 3 villager naming system.
 *
 * <p>Only the substrate element pair or manual special first name and the
 * village's drift IDs are durable.
 * Age, profession, regional forms, and the displayed string are derived each
 * time the relevant entity state changes.</p>
 */
public final class VillagerNameManager {

    private static volatile VillagerNamingData namingData;

    private VillagerNameManager() {
    }

    /** Called by the server reload listener; the generated resource has no fallback name list. */
    public static void loadNames(net.minecraft.server.packs.resources.ResourceManager resourceManager) {
        namingData = VillagerNamingDataLoader.load(resourceManager).orElse(null);
        if (namingData != null) {
            EmeraldCapitalism.LOGGER.info(
                    "Loaded villager naming system: {} pools, {} drift rules",
                    5, namingData.driftRules().size());
        }
    }

    public static Optional<VillagerNamingData> getNamingData() {
        return Optional.ofNullable(namingData);
    }

    /** Assigns a new slot 1 when the villager belongs to a tracked village. */
    public static void assignNameIfNeeded(Villager villager) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return;
        }
        assignNameIfNeeded(villager, findVillage(level, villager));
    }

    /** Assigns/rebuilds a name using the server's authoritative village record. */
    public static void assignNameIfNeeded(Villager villager, VillageRecord village) {
        if (!Config.enableVillagerNames || !(villager.level() instanceof ServerLevel level)) {
            return;
        }
        VillagerNamingData data = ensureData(level);
        if (data == null) {
            return;
        }
        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        if (!stats.hasAssignedFirstName()) {
            if (stats.getVillagerName() == null && villager.hasCustomName()) {
                // A pre-existing name tag is player-owned. Wait until it is
                // removed before allocating a substrate slot or replacing it.
                return;
            }
            if (village == null) {
                // Do not allocate from a biome-only fallback before the village
                // registry sees the entity: that would make duplicate names
                // possible when a structure registers a few ticks later.
                return;
            }
            ensureVillageNamingState(level, village, data);
            allocatePersonalSlot(level, village, villager, stats, data);
        }
        refreshNameIfNeeded(villager, village);
        VillagerNameRefreshScheduler.trackVillager(villager);
    }

    /** Assigns a durable random-shift name to a wandering trader without requiring a village. */
    public static void assignWanderingTraderNameIfNeeded(WanderingTrader trader) {
        if (!Config.enableVillagerNames || !(trader.level() instanceof ServerLevel level)) {
            return;
        }
        VillagerNamingData data = ensureData(level);
        if (data == null) {
            return;
        }

        VillagerStatsAttachment stats = trader.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        if (!stats.hasAssignedFirstName()) {
            if (trader.hasCustomName()) {
                return;
            }
            VillagerNamingData.BiomePool pool = data.pool(biomeFromLevel(level, trader.blockPosition()));
            RandomSource random = trader.getRandom();
            var special = data.selectSpecialFirstName(random.nextLong());
            if (special.isPresent()) {
                stats.setSpecialFirstName(special.get());
            } else {
                stats.setPersonalFirstElement(pool.first().get(random.nextInt(pool.first().size())));
                stats.setPersonalSecondElement(pool.second().get(random.nextInt(pool.second().size())));
            }
            stats.setWanderingTraderDriftRules(selectRandomDriftRules(data, random));
        }
        refreshWanderingTraderName(trader);
    }

    /** Rebuilds a wandering trader's display name from its persisted substrate and shifts. */
    public static void refreshWanderingTraderName(WanderingTrader trader) {
        if (!Config.enableVillagerNames || !(trader.level() instanceof ServerLevel level)) {
            return;
        }
        VillagerStatsAttachment stats = trader.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        if (!stats.hasAssignedFirstName()) {
            assignWanderingTraderNameIfNeeded(trader);
            return;
        }
        String generatedName = stats.getGeneratedWanderingTraderName();
        if (generatedName != null && trader.hasCustomName()
                && !generatedName.equals(trader.getCustomName().getString())) {
            return;
        }

        VillagerNamingData data = ensureData(level);
        if (data == null) {
            return;
        }
        String biome = biomeFromLevel(level, trader.blockPosition());
        String personal;
        if (stats.hasSpecialFirstName()) {
            personal = stats.getSpecialFirstName().trim();
        } else {
            personal = data.regionalForm(
                    data.baseForm(stats.getPersonalFirstElement(), stats.getPersonalSecondElement()), biome);
        }
        personal = data.affix(personal.toLowerCase(Locale.ROOT), data.ageSuffix(false, false));
        personal = capitalize(data.applyDrift(stats.getWanderingTraderDriftRules(), personal));
        String byname = data.byname("wanderingtrader", biome);
        byname = capitalize(data.applyDrift(stats.getWanderingTraderDriftRules(), byname));
        String rendered = byname.isEmpty() ? personal : personal + " " + byname;
        stats.setVillagerName(rendered);
        stats.setGeneratedWanderingTraderName(rendered);
        trader.setCustomName(Component.literal(rendered));
        trader.setCustomNameVisible(true);
    }

    private static List<String> selectRandomDriftRules(VillagerNamingData data, RandomSource random) {
        if (data.driftRules().isEmpty()) {
            return List.of();
        }
        List<String> available = data.driftRules().stream()
                .map(VillagerNamingData.DriftRule::id)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        int count = Math.min(3, available.size());
        java.util.Collections.shuffle(available, new java.util.Random(random.nextLong()));
        return List.copyOf(available.subList(0, count));
    }

    /** Rebuilds after an explicit dirty notification, with cached inputs as an idempotence guard. */
    public static void refreshNameIfNeeded(Villager villager) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return;
        }
        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        String professionKey = professionKey(villager);
        int ageStage = ageStage(villager);
        String originVillageName = resolveOriginVillageName(level, stats);
        if (stats.hasAssignedFirstName()
                && professionKey.equals(stats.getLastRenderedProfession())
                && ageStage == stats.getLastRenderedAgeStage()
                && Objects.equals(originVillageName, stats.getLastRenderedOriginVillageName())
                && stats.getVillagerName() != null
                && !stats.getVillagerName().isEmpty()) {
            return;
        }
        VillageRecord village = findVillageForName(level, villager, stats);
        if (!stats.hasAssignedFirstName()) {
            assignNameIfNeeded(villager, village);
            return;
        }
        refreshNameIfNeeded(villager, village);
    }

    private static void refreshNameIfNeeded(Villager villager, VillageRecord village) {
        if (!Config.enableVillagerNames || !(villager.level() instanceof ServerLevel level)) {
            return;
        }
        VillagerNamingData data = ensureData(level);
        if (data == null) {
            return;
        }
        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);

        // A name-tagged villager is player-owned. Once the entity name differs
        // from the last derived value, leave it alone.
        if (stats.getVillagerName() != null && villager.hasCustomName()
                && !stats.getVillagerName().equals(villager.getCustomName().getString())) {
            return;
        }

        if (village != null) {
            ensureVillageNamingState(level, village, data);
        }
        String originVillageName = resolveOriginVillageName(level, stats);
        String biome = village != null ? village.getVillagerNamingBiome()
                : biomeFromLevel(level, villager.blockPosition());
        List<String> driftRules = village != null ? village.getVillagerNamingDriftRules() : List.of();

        String personal;
        if (stats.hasSpecialFirstName()) {
            // Manual special names replace only slot 1. They do not receive
            // biome sound changes or village drift; slots 2-4 remain derived.
            personal = stats.getSpecialFirstName().trim();
        } else {
            String base = data.baseForm(stats.getPersonalFirstElement(), stats.getPersonalSecondElement());
            personal = data.regionalForm(base, biome);
        }
        String suffix = data.ageSuffix(villager.isBaby(), isNitwit(villager));
        String personalWord = data.affix(personal.toLowerCase(Locale.ROOT), suffix.toLowerCase(Locale.ROOT));
        personalWord = capitalize(data.applyDrift(driftRules, personalWord));

        String byname = data.byname(professionKey(villager), biome);
        if (!isMayor(villager)) {
            byname = data.applyDrift(driftRules, byname);
        } else {
            // Mayor is a title rather than a profession. Its defective muknek
            // root deliberately omits slot 3, like Nitwit and Unemployed.
            byname = "";
        }
        String rendered = byname.isEmpty() ? personalWord : personalWord + " " + capitalize(byname);
        String originParticle = isMayor(villager)
                ? data.mayorOriginParticleText(originVillageName)
                : data.originParticleText(originVillageName);
        if (!originParticle.isEmpty()) {
            rendered += " " + originParticle;
        }

        // Entity save/load retains the vanilla custom-name component while the
        // assembled naming string is deliberately transient. If the component
        // is not the name this pipeline would derive, it is a player name tag.
        if (stats.getVillagerName() == null && villager.hasCustomName()
                && !rendered.equals(villager.getCustomName().getString())) {
            return;
        }
        stats.setVillagerName(rendered);
        stats.setLastRenderedProfession(professionKey(villager));
        stats.setLastRenderedAgeStage(ageStage(villager));
        stats.setLastRenderedOriginVillageName(originVillageName);
        villager.setCustomName(Component.literal(rendered));
        villager.setCustomNameVisible(true);
    }

    /** Re-applies a derived name after conversion; entity join will retry if no village is loaded yet. */
    public static void applyStoredName(Villager villager) {
        if (!Config.enableVillagerNames) {
            return;
        }
        if (villager.level() instanceof ServerLevel level) {
            VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
            refreshNameIfNeeded(villager, findVillageForName(level, villager, stats));
        }
    }

    /** Initializes the persistent village drift state exactly once. */
    public static void ensureVillageNamingState(
            ServerLevel level, VillageRecord village, VillagerNamingData data) {
        if (village.hasVillagerNamingDriftRules()) {
            return;
        }
        String biome = biomeFromLevel(level, village.getBellPosition());
        List<String> driftRules = selectDriftRules(level.getSeed(), village, data);
        if (village.setVillagerNamingState(biome, driftRules)) {
            VillageRegistryData.get(level).setDirty();
        }
    }

    private static void allocatePersonalSlot(
            ServerLevel level,
            VillageRecord village,
            Villager villager,
            VillagerStatsAttachment stats,
            VillagerNamingData data
    ) {
        long seed = mix(level.getSeed()
                ^ villager.getUUID().getMostSignificantBits()
                ^ Long.rotateLeft(villager.getUUID().getLeastSignificantBits(), 17)
                ^ village.getVillageId().getMostSignificantBits());

        var specialFirstName = data.selectSpecialFirstName(seed);
        if (specialFirstName.isPresent()) {
            stats.setSpecialFirstName(specialFirstName.get());
            stats.setNamingVillageId(village.getVillageId());
            VillageRegistryData.get(level).setDirty();
            return;
        }

        VillagerNamingData.BiomePool pool = data.pool(village.getVillagerNamingBiome());
        int total = pool.first().size() * pool.second().size();
        int start = Math.floorMod(seed, total);
        boolean reserved = false;
        for (int offset = 0; offset < total; offset++) {
            int index = Math.floorMod(start + offset, total);
            String first = pool.first().get(index / pool.second().size());
            String second = pool.second().get(index % pool.second().size());
            if (village.reserveVillagerNamingPair(first, second)) {
                stats.setPersonalFirstElement(first);
                stats.setPersonalSecondElement(second);
                reserved = true;
                break;
            }
        }
        if (!reserved) {
            // The brief explicitly permits reuse once a village outgrows its
            // pool. The UUID-derived choice remains stable for this spawn.
            int index = Math.floorMod(seed, total);
            stats.setPersonalFirstElement(pool.first().get(index / pool.second().size()));
            stats.setPersonalSecondElement(pool.second().get(index % pool.second().size()));
        }
        stats.setNamingVillageId(village.getVillageId());
        VillageRegistryData.get(level).setDirty();
    }

    private static List<String> selectDriftRules(
            long worldSeed, VillageRecord village, VillagerNamingData data) {
        VillagerNamingData.DriftAssignment assignment = data.driftAssignment();
        double[] values = new double[data.driftRules().size()];
        for (int index = 0; index < values.length; index++) {
            int featureSize = assignment.featureSizes().get(index);
            values[index] = sampleNoise(worldSeed, village.getBellPosition().getX(),
                    village.getBellPosition().getZ(), featureSize, index);
        }

        boolean[] selected = new boolean[values.length];
        for (int index = 0; index < values.length; index++) {
            selected[index] = values[index] >= assignment.threshold();
        }
        for (java.util.Set<String> inverseGroup : assignment.inverseGroups()) {
            int winner = -1;
            for (String ruleId : inverseGroup) {
                int index = driftRuleIndex(data, ruleId);
                if (selected[index] && (winner < 0 || values[index] > values[winner])) {
                    winner = index;
                }
            }
            for (String ruleId : inverseGroup) {
                int index = driftRuleIndex(data, ruleId);
                if (selected[index] && index != winner) {
                    selected[index] = false;
                }
            }
        }

        List<Integer> ranked = new ArrayList<>();
        for (int index = 0; index < selected.length; index++) {
            if (selected[index]) {
                ranked.add(index);
            }
        }
        if (ranked.isEmpty()) {
            ranked.add(maxIndex(values));
        }
        ranked.sort(Comparator.comparingDouble((Integer index) -> values[index]).reversed());
        if (ranked.size() > assignment.maxRules()) {
            ranked = new ArrayList<>(ranked.subList(0, assignment.maxRules()));
        }
        ranked.sort(Integer::compareTo);

        List<String> result = new ArrayList<>(Math.max(assignment.minRules(), ranked.size()));
        for (int index : ranked) {
            result.add(data.driftRules().get(index).id());
        }
        return List.copyOf(result);
    }

    private static int driftRuleIndex(VillagerNamingData data, String ruleId) {
        for (int index = 0; index < data.driftRules().size(); index++) {
            if (data.driftRules().get(index).id().equals(ruleId)) {
                return index;
            }
        }
        throw new IllegalArgumentException("Unknown inverse drift rule: " + ruleId);
    }

    private static int maxIndex(double[] values) {
        int best = 0;
        for (int index = 1; index < values.length; index++) {
            if (values[index] > values[best]) {
                best = index;
            }
        }
        return best;
    }

    private static double sampleNoise(long worldSeed, int x, int z, int featureSize, int ruleIndex) {
        double gridX = (double) x / featureSize;
        double gridZ = (double) z / featureSize;
        long cellX = (long) Math.floor(gridX);
        long cellZ = (long) Math.floor(gridZ);
        double localX = smooth(gridX - cellX);
        double localZ = smooth(gridZ - cellZ);
        long salt = 0x9E3779B97F4A7C15L * (ruleIndex + 1L);
        double a = unitHash(worldSeed ^ salt, cellX, cellZ);
        double b = unitHash(worldSeed ^ salt, cellX + 1, cellZ);
        double c = unitHash(worldSeed ^ salt, cellX, cellZ + 1);
        double d = unitHash(worldSeed ^ salt, cellX + 1, cellZ + 1);
        double top = a + (b - a) * localX;
        double bottom = c + (d - c) * localX;
        return top + (bottom - top) * localZ;
    }

    private static double smooth(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double unitHash(long seed, long x, long z) {
        long value = seed ^ (x * 0x632BE59BD9B4E019L) ^ (z * 0x8CB92BA72F3D8DD7L);
        return (mix(value) >>> 11) * (1.0 / (1L << 53));
    }

    private static VillageRecord findVillage(ServerLevel level, Villager villager) {
        return VillageRegistryData.get(level).getVillageFor(villager.blockPosition());
    }

    private static VillageRecord findVillageForName(
            ServerLevel level, Villager villager, VillagerStatsAttachment stats) {
        VillageRegistryData data = VillageRegistryData.get(level);
        if (stats.getNamingVillageId() != null) {
            VillageRecord linked = data.getVillages().get(stats.getNamingVillageId());
            if (linked != null) {
                return linked;
            }
        }
        return data.getVillageFor(villager.blockPosition());
    }

    private static String resolveOriginVillageName(
            ServerLevel level, VillagerStatsAttachment stats) {
        UUID originVillageId = stats.getNamingVillageId();
        if (originVillageId == null) {
            return null;
        }
        VillageRecord originVillage = VillageRegistryData.get(level).getVillages().get(originVillageId);
        if (originVillage == null || originVillage.getName() == null
                || originVillage.getName().isBlank() || "Village".equals(originVillage.getName())) {
            return null;
        }
        return originVillage.getName().trim();
    }

    private static VillagerNamingData ensureData(ServerLevel level) {
        VillagerNamingData result = namingData;
        if (result == null) {
            loadNames(level.getServer().getResourceManager());
            result = namingData;
        }
        return result;
    }

    private static String professionKey(Villager villager) {
        return VillagerNamingData.normalizeProfessionKey(
                villager.getVillagerData().getProfession().name());
    }

    private static boolean isNitwit(Villager villager) {
        return villager.getVillagerData().getProfession().equals(VillagerProfession.NITWIT);
    }

    private static boolean isMayor(Villager villager) {
        return villager.getVillagerData().getProfession() == ECAPVillagerProfessions.MAYOR.get();
    }

    private static int ageStage(Villager villager) {
        // 1.21.1 exposes child and adult stages only; breeding cooldown is not an age stage.
        return villager.isBaby() ? 0 : 1;
    }

    private static String biomeFromLevel(ServerLevel level, net.minecraft.core.BlockPos pos) {
        String path = level.getBiome(pos).unwrapKey()
                .map(ResourceKey::location)
                .map(location -> location.getPath().toLowerCase(Locale.ROOT))
                .orElse("");
        if (path.contains("desert")) {
            return "desert";
        }
        if (path.contains("savanna")) {
            return "savanna";
        }
        if (path.contains("taiga")) {
            return "taiga";
        }
        if (path.contains("snow") || path.contains("ice")) {
            return "snowy";
        }
        return "plains";
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }
}
