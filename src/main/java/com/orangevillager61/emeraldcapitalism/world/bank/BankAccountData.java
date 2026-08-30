package com.orangevillager61.emeraldcapitalism.world.bank;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
                Codec.INT.fieldOf("balance").forGetter(AccountEntry::balance)
        ).apply(instance, AccountEntry::new));
    }

    /** Codec for durable bank balances and the bank-name sequence. */
    public static final Codec<BankAccountData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            AccountEntry.CODEC.sizeLimitedListOf(MAX_PERSISTED_ACCOUNTS)
                    .optionalFieldOf("accounts", List.of())
                    .forGetter(BankAccountData::accountEntries),
            Codec.INT.optionalFieldOf("next_bank_number", 1)
                    .forGetter(data -> data.nextBankNumber)
    ).apply(instance, BankAccountData::fromCodec));

    /** Maps villager UUID → emerald balance. */
    private final Map<UUID, Integer> balances = new HashMap<>();

    /** Counter used to generate unique bank names; incremented each time a name is generated. */
    private int nextBankNumber = 1;

    public BankAccountData() {}

    private static BankAccountData fromCodec(List<AccountEntry> accounts, int nextBankNumber) {
        BankAccountData data = new BankAccountData();
        for (AccountEntry account : accounts) {
            data.balances.put(account.uuid(), account.balance());
        }
        data.nextBankNumber = Math.max(1, nextBankNumber);
        return data;
    }

    private List<AccountEntry> accountEntries() {
        List<AccountEntry> entries = new ArrayList<>(balances.size());
        for (Map.Entry<UUID, Integer> entry : balances.entrySet()) {
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
        Objects.requireNonNull(uuid, "uuid");
        if (!balances.containsKey(uuid)) {
            balances.put(uuid, 0);
            setDirty();
        }
    }

    /** Returns {@code true} if an account exists for {@code uuid}. */
    public boolean hasAccount(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return balances.containsKey(uuid);
    }

    /**
     * Returns the current balance for {@code uuid}, or 0 if no account exists.
     */
    public int getBalance(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return balances.getOrDefault(uuid, 0);
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
        requireAccountMutation(uuid, amount);
        balances.merge(uuid, amount, Integer::sum);
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
        requireAccountMutation(uuid, amount);
        balances.merge(uuid, -amount, Integer::sum);
        setDirty();
    }

    private void requireAccountMutation(UUID uuid, int amount) {
        Objects.requireNonNull(uuid, "uuid");
        if (amount <= 0) {
            throw new IllegalArgumentException("Bank account mutation amount must be positive");
        }
        if (!balances.containsKey(uuid)) {
            throw new IllegalStateException("Bank account does not exist for " + uuid);
        }
    }

    /** Returns an unmodifiable view of all balances, keyed by villager UUID. */
    public Map<UUID, Integer> getBalances() {
        return Collections.unmodifiableMap(balances);
    }

    // Bank name generation

    /**
     * Generates and returns the next unique bank name ("Bank 1", "Bank 2", …).
     * The counter is persisted so names remain unique across server restarts.
     */
    public String generateBankName() {
        String name = "Bank " + nextBankNumber;
        nextBankNumber++;
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
