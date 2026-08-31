package com.orangevillager61.emeraldcapitalism.world.bank;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Encoder;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * World-level {@link SavedData} that stores emerald balances for registered villager bank accounts
 * and a counter used to auto-generate unique bank names ("Bank 1", "Bank 2", …).
 * <p>
 * A single instance lives on the overworld {@link net.minecraft.server.level.ServerLevel}.
 * Access it via {@link #get(ServerLevel)}.
 * <p>
 * Balances may go negative (via {@link #withdraw}). Physical emerald distribution into
 * linked chests is handled separately by the caller.
 */
public class BankAccountData extends SavedData {

    private static final String DATA_NAME = "emeraldcapitalism_bank_accounts";
    static final int MAX_PERSISTED_ACCOUNTS = 65_536;

    private record AccountEntry(UUID uuid, int balance) {
        private static final Codec<AccountEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("uuid").forGetter(AccountEntry::uuid),
                Codec.intRange(BankLedger.MIN_BALANCE, BankLedger.MAX_BALANCE)
                        .fieldOf("balance").forGetter(AccountEntry::balance)
        ).apply(instance, AccountEntry::new));
    }

    /** Keeps valid accounts when one persisted account entry is malformed. */
    private static final Codec<List<AccountEntry>> ACCOUNTS_CODEC = Codec.of(
            new Encoder<>() {
                @Override
                public <T> DataResult<T> encode(List<AccountEntry> input, DynamicOps<T> ops, T prefix) {
                    return AccountEntry.CODEC.sizeLimitedListOf(MAX_PERSISTED_ACCOUNTS)
                            .encode(input, ops, prefix);
                }
            },
            new Decoder<>() {
                @Override
                public <T> DataResult<Pair<List<AccountEntry>, T>> decode(DynamicOps<T> ops, T input) {
                    return ops.getStream(input).flatMap(elements -> {
                        List<T> encodedAccounts = elements.limit((long) MAX_PERSISTED_ACCOUNTS + 1).toList();
                        if (encodedAccounts.size() > MAX_PERSISTED_ACCOUNTS) {
                            return DataResult.error(() -> "Bank account data exceeds "
                                    + MAX_PERSISTED_ACCOUNTS + " persisted accounts");
                        }

                        List<AccountEntry> accounts = new ArrayList<>();
                        Set<UUID> seen = new HashSet<>();
                        for (T element : encodedAccounts) {
                            AccountEntry.CODEC.parse(ops, element)
                                    .resultOrPartial(message -> EmeraldCapitalism.LOGGER.warn(
                                            "[ECAP] Skipping corrupt bank account entry: {}", message))
                                    .ifPresent(account -> {
                                        if (seen.add(account.uuid())) {
                                            accounts.add(account);
                                        } else {
                                            EmeraldCapitalism.LOGGER.warn(
                                                    "[ECAP] Skipping duplicate bank account entry for {}", account.uuid());
                                        }
                                    });
                        }
                        return DataResult.success(Pair.of(accounts, ops.empty()));
                    });
                }
            }
    );

    /** Codec for durable bank balances and the bank-name sequence. */
    public static final Codec<BankAccountData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ACCOUNTS_CODEC
                    .optionalFieldOf("accounts", List.of())
                    .forGetter(BankAccountData::accountEntries),
            Codec.INT.optionalFieldOf("next_bank_number", 1)
            .forGetter(data -> data.ledger.nextBankNumber())
    ).apply(instance, BankAccountData::fromCodec));

    private final BankLedger ledger;

    public BankAccountData() {
        this(new BankLedger());
    }

    private BankAccountData(BankLedger ledger) {
        this.ledger = ledger;
    }

    private static BankAccountData fromCodec(List<AccountEntry> accounts, int nextBankNumber) {
        Map<UUID, Integer> balances = new HashMap<>();
        for (AccountEntry account : accounts) {
            if (balances.putIfAbsent(account.uuid(), account.balance()) != null) {
                EmeraldCapitalism.LOGGER.warn(
                        "[ECAP] Ignoring duplicate bank account for {} while constructing ledger", account.uuid());
            }
        }
        return new BankAccountData(new BankLedger(balances, nextBankNumber));
    }

    private List<AccountEntry> accountEntries() {
        List<AccountEntry> entries = new ArrayList<>(ledger.balances().size());
        for (Map.Entry<UUID, Integer> entry : ledger.balances().entrySet()) {
            entries.add(new AccountEntry(entry.getKey(), entry.getValue()));
        }
        return entries;
    }

    // Factory

    /**
     * Returns the single world-level {@link BankAccountData} instance, loading it from
     * the overworld's data storage or creating a new empty one.
     */
    public static BankAccountData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("[ECAP] Overworld not available when accessing BankAccountData");
        }
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(BankAccountData::new, BankAccountData::load, null),
                DATA_NAME
        );
    }

    // Account management

    /**
     * Opens an account for {@code uuid} with a starting balance of 0.
     * Does nothing if the account already exists.
     */
    public void openAccount(UUID uuid) {
        if (ledger.openAccount(uuid)) {
            setDirty();
        }
    }

    /** Returns {@code true} if an account exists for {@code uuid}. */
    public boolean hasAccount(UUID uuid) {
        return ledger.hasAccount(uuid);
    }

    /**
     * Returns the current balance for {@code uuid}, or 0 if no account exists.
     */
    public int getBalance(UUID uuid) {
        return ledger.getBalance(uuid);
    }

    /**
     * Credits {@code amount} emeralds to {@code uuid}'s account.
     * The account must already exist (call {@link #openAccount} first).
     * Physical chest deposit is the caller's responsibility.
     *
     * @param uuid   the villager whose balance is credited
     * @param amount positive number of emeralds to add
     */
    public void deposit(UUID uuid, int amount) {
        ledger.deposit(uuid, amount);
        setDirty();
    }

    /**
     * Debits {@code amount} emeralds from {@code uuid}'s account.
     * The account must already exist (call {@link #openAccount} first).
     * Balance may go negative.
     *
     * @param uuid   the villager whose balance is debited
     * @param amount positive number of emeralds to subtract
     */
    public void withdraw(UUID uuid, int amount) {
        ledger.withdraw(uuid, amount);
        setDirty();
    }

    /** Returns an unmodifiable view of all balances, keyed by villager UUID. */
    public Map<UUID, Integer> getBalances() {
        return ledger.balances();
    }

    // Bank name generation

    /**
     * Generates and returns the next unique bank name ("Bank 1", "Bank 2", …).
     * The counter is persisted so names remain unique across server restarts.
     */
    public String generateBankName() {
        String name = ledger.generateBankName();
        setDirty();
        return name;
    }

    // SavedData

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        return encodeToTag(this, tag);
    }

    public static BankAccountData load(CompoundTag tag, HolderLookup.Provider registries) {
        return decodeFromTag(tag);
    }

    private static CompoundTag encodeToTag(BankAccountData data, CompoundTag target) {
        DataResult<net.minecraft.nbt.Tag> encoded = CODEC.encodeStart(NbtOps.INSTANCE, data);
        return encoded.resultOrPartial(message -> EmeraldCapitalism.LOGGER.error(
                        "[ECAP] Could not encode bank account data: {}", message))
                .filter(CompoundTag.class::isInstance)
                .map(CompoundTag.class::cast)
                .map(encodedTag -> {
                    target.merge(encodedTag);
                    return target;
                })
                .orElse(target);
    }

    private static BankAccountData decodeFromTag(CompoundTag tag) {
        return CODEC.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial(message -> EmeraldCapitalism.LOGGER.error(
                        "[ECAP] Could not decode bank account data: {}", message))
                .orElseGet(BankAccountData::new);
    }
}
