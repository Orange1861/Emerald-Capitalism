package com.orangevillager61.emeraldcapitalism.world.villagefarms;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One farm of a village's planned farm set, as the farm journal records it.
 *
 * <p>The journal exists so that a stop, crash, or failed placement cannot leave a village with
 * half-built terrain and no record: the exact placement is saved before any block is written and
 * is replayed until it succeeds or runs out of attempts.
 *
 * @param placement the planned site, replayed unchanged on retry
 * @param attempts  how many times placement of this farm has started
 * @param done      whether placement completed successfully
 */
public record FarmJournalEntry(FarmPlacement placement, int attempts, boolean done) {

    public static final Codec<FarmJournalEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            FarmPlacement.CODEC.fieldOf("placement").forGetter(FarmJournalEntry::placement),
            Codec.intRange(0, 1_000).optionalFieldOf("attempts", 0).forGetter(FarmJournalEntry::attempts),
            Codec.BOOL.optionalFieldOf("done", false).forGetter(FarmJournalEntry::done)
    ).apply(instance, FarmJournalEntry::new));

    FarmJournalEntry begun() {
        return new FarmJournalEntry(placement, attempts + 1, done);
    }

    FarmJournalEntry completed() {
        return new FarmJournalEntry(placement, attempts, true);
    }

    /** Whether this farm still needs placing: not done and attempts remain. */
    boolean pending() {
        return !done && attempts < VillageFarmSavedData.MAX_PLACEMENT_ATTEMPTS;
    }
}
