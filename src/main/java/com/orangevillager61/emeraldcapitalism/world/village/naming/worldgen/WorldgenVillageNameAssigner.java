package com.orangevillager61.emeraldcapitalism.world.village.naming.worldgen;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.VillageNamingProfile;
import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.VillageNamingProfileAnalyzer;
import com.orangevillager61.emeraldcapitalism.world.village.naming.analysis.VillageSignalSnapshot;
import com.orangevillager61.emeraldcapitalism.world.village.naming.data.RootLexicon;
import com.orangevillager61.emeraldcapitalism.world.village.naming.data.RootLexiconRegistry;
import com.orangevillager61.emeraldcapitalism.world.village.naming.generation.NameCandidate;
import com.orangevillager61.emeraldcapitalism.world.village.naming.generation.VillageNameGenerationResult;
import com.orangevillager61.emeraldcapitalism.world.village.naming.generation.VillageNameGenerator;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

public final class WorldgenVillageNameAssigner {
    private final WorldgenVillageSignalExtractor extractor = new WorldgenVillageSignalExtractor();
    private final VillageNamingProfileAnalyzer analyzer = new VillageNamingProfileAnalyzer();
    private final VillageNameGenerator generator = new VillageNameGenerator();

    public Optional<String> assignGeneratedName(ServerLevel level, VillageRecord village) {
        Optional<RootLexicon> lexiconOpt = RootLexiconRegistry.get();
        if (lexiconOpt.isEmpty()) {
            EmeraldCapitalism.LOGGER.warn("Skipping worldgen village naming: canonical root lexicon is not loaded");
            return Optional.empty();
        }
        if (!village.isCacheInitialized()) {
            EmeraldCapitalism.LOGGER.debug(
                    "Skipping worldgen village naming for {} because village cache is not initialized yet",
                    village.getVillageId()
            );
            return Optional.empty();
        }

        VillageSignalSnapshot signals = extractor.extract(level, village);
        VillageNamingProfile profile = analyzer.analyze(signals);
        long seed = deriveVillageNameSeed(level, village);
        VillageNameGenerationResult result = generator.generate(profile, lexiconOpt.get(), seed, 4);

        if (result.candidates().isEmpty()) {
            EmeraldCapitalism.LOGGER.warn(
                    "Worldgen naming produced no candidate for village {}. profile={} trace={} reason={}",
                    village.getVillageId(),
                    profile.debugSummary(),
                    result.trace().debugSummary(),
                    result.failureReason()
            );
            return Optional.empty();
        }

        NameCandidate chosen = chooseCandidate(result, seed);
        VillageRegistryData.get(level).renameVillage(level, village, chosen.renderedName());

        EmeraldCapitalism.LOGGER.info(
                "Worldgen village name selected id={} name={} roots={} score={} profile={} trace={}",
                village.getVillageId(),
                chosen.renderedName(),
                chosen.rootParts(),
                String.format("%.4f", chosen.score()),
                profile.debugSummary(),
                result.trace().debugSummary()
        );
        EmeraldCapitalism.LOGGER.debug(
                "Worldgen naming details id={} boostedSections={} consideredRoots={} candidates={}",
                village.getVillageId(),
                result.trace().boostedSections(),
                result.trace().consideredRoots(),
                result.candidates()
        );

        return Optional.of(chosen.renderedName());
    }

    private static NameCandidate chooseCandidate(VillageNameGenerationResult result, long seed) {
        int candidateCount = result.candidates().size();
        if (candidateCount == 1) {
            return result.candidates().getFirst();
        }
        int pool = Math.min(3, candidateCount);
        int index = (int) Math.floorMod(seed, pool);
        return result.candidates().get(index);
    }

    private static long deriveVillageNameSeed(ServerLevel level, VillageRecord village) {
        long seed = level.getSeed();
        seed = mix(seed ^ village.getBellPosition().asLong());
        seed = mix(seed ^ Double.doubleToLongBits(village.getBoundingBox().minX));
        seed = mix(seed ^ Double.doubleToLongBits(village.getBoundingBox().minZ));
        return seed;
    }

    private static long mix(long value) {
        value ^= (value >>> 33);
        value *= 0xff51afd7ed558ccdL;
        value ^= (value >>> 33);
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= (value >>> 33);
        return value;
    }
}
