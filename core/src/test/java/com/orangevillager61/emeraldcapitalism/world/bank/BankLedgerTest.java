package com.orangevillager61.emeraldcapitalism.world.bank;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BankLedgerTest {

    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void accountsAllowDebtAndNamesContinueFromPersistedSequence() {
        BankLedger ledger = new BankLedger(java.util.Map.of(ACCOUNT, 4), 7);

        ledger.withdraw(ACCOUNT, 9);

        assertEquals(-5, ledger.getBalance(ACCOUNT));
        assertEquals("Bank 7", ledger.generateBankName());
        assertEquals("Bank 8", ledger.generateBankName());
    }

    @Test
    void mutationsRequireAnExistingAccountAndPositiveAmount() {
        BankLedger ledger = new BankLedger();
        assertThrows(IllegalStateException.class, () -> ledger.deposit(ACCOUNT, 1));
        ledger.openAccount(ACCOUNT);
        assertThrows(IllegalArgumentException.class, () -> ledger.withdraw(ACCOUNT, 0));
    }

    @Test
    void balanceAndBankNameSequencesFailBeforeIntegerOverflow() {
        BankLedger positive = new BankLedger(
                java.util.Map.of(ACCOUNT, BankLedger.MAX_BALANCE), 1);
        assertThrows(IllegalStateException.class, () -> positive.deposit(ACCOUNT, 1));

        BankLedger negative = new BankLedger(
                java.util.Map.of(ACCOUNT, BankLedger.MIN_BALANCE), 1);
        assertThrows(IllegalStateException.class, () -> negative.withdraw(ACCOUNT, 1));

        BankLedger exhausted = new BankLedger(
                java.util.Map.of(), BankLedger.MAX_BANK_NUMBER);
        assertThrows(IllegalStateException.class, exhausted::generateBankName);
    }
}
