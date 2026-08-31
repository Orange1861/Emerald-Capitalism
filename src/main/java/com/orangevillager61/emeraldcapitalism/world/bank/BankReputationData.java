package com.orangevillager61.emeraldcapitalism.world.bank;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.Encoder;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * World-level player reputation with the banking system.
 *
 * <p>This data is intentionally separate from {@code VillageRecord}: bank
 * security is a world-wide player relationship and must not affect the
 * village ledger's opinion calculation.</p>
 */
public class BankReputationData extends SavedData {

    public static final int HOSTILITY_THRESHOLD = -100;
    public static final int BANK_OWNER_OPINION = 1_000;
    public static final int GOLEM_KILLED_PENALTY = -100;
    public static final int EMERALD_CHEST_WITHDRAWAL_PENALTY = -100;
    public static final int DONATION_OPINION_PER_EMERALD = 1;

    private static final String DATA_NAME = "emeraldcapitalism_bank_reputation";
    static final int MAX_PERSISTED_REPUTATIONS = 65_536;

    private record ReputationEntry(UUID playerId, int reputation) {
        private static final Codec<ReputationEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("player_id").forGetter(ReputationEntry::playerId),
                Codec.INT.fieldOf("reputation").forGetter(ReputationEntry::reputation)
        ).apply(instance, ReputationEntry::new));
    }

    private static final Codec<List<ReputationEntry>> REPUTATIONS_CODEC = Codec.of(
            new Encoder<>() {
                @Override
                public <T> DataResult<T> encode(List<ReputationEntry> input, DynamicOps<T> ops, T prefix) {
                    return ReputationEntry.CODEC.sizeLimitedListOf(MAX_PERSISTED_REPUTATIONS)
                            .encode(input, ops, prefix);
                }
            },
            new Decoder<>() {
                @Override
                public <T> DataResult<Pair<List<ReputationEntry>, T>> decode(
                        DynamicOps<T> ops, T input) {
                    return ops.getStream(input).flatMap(elements -> {
                        List<T> encoded = elements.limit((long) MAX_PERSISTED_REPUTATIONS + 1).toList();
                        if (encoded.size() > MAX_PERSISTED_REPUTATIONS) {
                            return DataResult.error(() -> "Bank reputation exceeds "
                                    + MAX_PERSISTED_REPUTATIONS + " persisted players");
                        }
                        List<ReputationEntry> entries = new ArrayList<>();
                        for (T element : encoded) {
                            ReputationEntry.CODEC.parse(ops, element)
                                    .resultOrPartial(message -> EmeraldCapitalism.LOGGER.warn(
                                            "[ECAP] Skipping corrupt bank reputation entry: {}",
                                            message))
                                    .ifPresent(entries::add);
                        }
                        return DataResult.success(Pair.of(entries, ops.empty()));
                    });
                }
            }
    );

    public static final Codec<BankReputationData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            REPUTATIONS_CODEC.optionalFieldOf("reputations", List.of())
                    .forGetter(BankReputationData::reputationEntries)
    ).apply(instance, BankReputationData::fromCodec));

    private final ReputationLedger ledger;

    public BankReputationData() {
        this(new ReputationLedger());
    }

    private BankReputationData(ReputationLedger ledger) {
        this.ledger = ledger;
    }

    private static BankReputationData fromCodec(List<ReputationEntry> entries) {
        Map<UUID, Integer> reputations = new java.util.HashMap<>();
        for (ReputationEntry entry : entries) {
            if (entry.reputation() != 0) {
                reputations.put(entry.playerId(), entry.reputation());
            }
        }
        return new BankReputationData(new ReputationLedger(reputations));
    }

    private List<ReputationEntry> reputationEntries() {
        List<ReputationEntry> entries = new ArrayList<>(ledger.reputations().size());
        for (Map.Entry<UUID, Integer> entry : ledger.reputations().entrySet()) {
            entries.add(new ReputationEntry(entry.getKey(), entry.getValue()));
        }
        return entries;
    }

    /** Returns the single overworld-owned bank reputation data instance. */
    public static BankReputationData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException(
                    "[ECAP] Overworld not available when accessing BankReputationData");
        }
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(BankReputationData::new, BankReputationData::load, null),
                DATA_NAME
        );
    }

    public int getReputation(UUID playerId) {
        return ledger.get(playerId);
    }

    /**
     * Returns the effective opinion of a player for one bank. Ownership is an
     * internal gameplay override; callers displaying reputation must use
     * {@link #getReputation(UUID)} so this value remains hidden.
     */
    public int getBankOpinion(BankBlockEntity bank, UUID playerId) {
        return bank.isControlledBy(playerId) ? BANK_OWNER_OPINION : getReputation(playerId);
    }

    /**
     * Applies a bank-reputation delta and returns the new value. Arithmetic is
     * saturated so malformed or repeated gameplay events cannot wrap the score.
     */
    public int adjustReputation(UUID playerId, int delta) {
        int updated = ledger.adjust(playerId, delta);
        if (delta != 0) {
            setDirty();
        }
        return updated;
    }

    public Map<UUID, Integer> getReputations() {
        return ledger.reputations();
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag,
                                     HolderLookup.@NotNull Provider registries) {
        DataResult<net.minecraft.nbt.Tag> encoded = CODEC.encodeStart(NbtOps.INSTANCE, this);
        return encoded.resultOrPartial(message -> EmeraldCapitalism.LOGGER.error(
                        "[ECAP] Could not encode bank reputation data: {}", message))
                .filter(CompoundTag.class::isInstance)
                .map(CompoundTag.class::cast)
                .map(encodedTag -> {
                    tag.merge(encodedTag);
                    return tag;
                })
                .orElse(tag);
    }

    public static BankReputationData load(CompoundTag tag, HolderLookup.Provider registries) {
        return CODEC.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial(message -> EmeraldCapitalism.LOGGER.error(
                        "[ECAP] Could not decode bank reputation data: {}", message))
                .orElseGet(BankReputationData::new);
    }
}
