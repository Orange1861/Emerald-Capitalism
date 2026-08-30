package com.orangevillager61.emeraldcapitalism.world.bank;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Platform-free bank accounts and durable bank-name sequencing. */
public final class BankLedger {
    private final Map<UUID, Integer> balances = new HashMap<>();
    private int nextBankNumber = 1;

    public BankLedger() {
    }

    public BankLedger(Map<UUID, Integer> balances, int nextBankNumber) {
        Objects.requireNonNull(balances, "balances");
        balances.forEach((uuid, balance) -> this.balances.put(
                Objects.requireNonNull(uuid, "balance uuid"),
                Objects.requireNonNull(balance, "balance")));
        this.nextBankNumber = Math.max(1, nextBankNumber);
    }

    public boolean openAccount(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        if (balances.containsKey(uuid)) {
            return false;
        }
        balances.put(uuid, 0);
        return true;
    }

    public boolean hasAccount(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return balances.containsKey(uuid);
    }

    public int getBalance(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return balances.getOrDefault(uuid, 0);
    }

    public void deposit(UUID uuid, int amount) {
        requireAccountMutation(uuid, amount);
        balances.merge(uuid, amount, Integer::sum);
    }

    public void withdraw(UUID uuid, int amount) {
        requireAccountMutation(uuid, amount);
        balances.merge(uuid, -amount, Integer::sum);
    }

    public Map<UUID, Integer> balances() {
        return Collections.unmodifiableMap(balances);
    }

    public int nextBankNumber() {
        return nextBankNumber;
    }

    public String generateBankName() {
        String name = "Bank " + nextBankNumber;
        nextBankNumber++;
        return name;
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
}
