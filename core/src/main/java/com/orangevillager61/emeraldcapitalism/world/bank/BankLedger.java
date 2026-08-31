package com.orangevillager61.emeraldcapitalism.world.bank;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Platform-free bank accounts and durable bank-name sequencing. */
public final class BankLedger {
    /** Keeps persisted balances and arithmetic within a deliberately bounded domain. */
    public static final int MIN_BALANCE = -1_000_000_000;
    public static final int MAX_BALANCE = 1_000_000_000;
    public static final int MAX_BANK_NUMBER = 2_000_000_000;

    private final Map<UUID, Integer> balances = new HashMap<>();
    private int nextBankNumber = 1;

    public BankLedger() {
    }

    public BankLedger(Map<UUID, Integer> balances, int nextBankNumber) {
        Objects.requireNonNull(balances, "balances");
        balances.forEach((uuid, balance) -> this.balances.put(
                Objects.requireNonNull(uuid, "balance uuid"),
                requireBalance(balance)));
        this.nextBankNumber = normalizeNextBankNumber(nextBankNumber);
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
        balances.compute(uuid, (account, balance) -> checkedBalanceChange(balance, amount));
    }

    public void withdraw(UUID uuid, int amount) {
        requireAccountMutation(uuid, amount);
        balances.compute(uuid, (account, balance) -> checkedBalanceChange(balance, -amount));
    }

    public Map<UUID, Integer> balances() {
        return Collections.unmodifiableMap(balances);
    }

    public int nextBankNumber() {
        return nextBankNumber;
    }

    public String generateBankName() {
        if (nextBankNumber >= MAX_BANK_NUMBER) {
            throw new IllegalStateException("Bank name sequence exhausted");
        }
        String name = "Bank " + nextBankNumber;
        nextBankNumber++;
        return name;
    }

    private static int checkedBalanceChange(int currentBalance, int change) {
        long updated = (long) currentBalance + change;
        if (updated < MIN_BALANCE || updated > MAX_BALANCE) {
            throw new IllegalStateException("Bank balance would exceed the supported range");
        }
        return (int) updated;
    }

    private static int requireBalance(Integer balance) {
        Objects.requireNonNull(balance, "balance");
        if (balance < MIN_BALANCE || balance > MAX_BALANCE) {
            throw new IllegalArgumentException("Bank balance is outside the supported range");
        }
        return balance;
    }

    private static int normalizeNextBankNumber(int value) {
        return value < 1 || value > MAX_BANK_NUMBER ? 1 : value;
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
