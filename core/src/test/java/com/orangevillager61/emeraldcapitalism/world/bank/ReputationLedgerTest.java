package com.orangevillager61.emeraldcapitalism.world.bank;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReputationLedgerTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void reputationAdjustmentsSaturateAndRemoveZeroEntries() {
        ReputationLedger ledger = new ReputationLedger(java.util.Map.of(PLAYER, Integer.MAX_VALUE));

        assertEquals(Integer.MAX_VALUE, ledger.adjust(PLAYER, 1));
        assertEquals(Integer.MAX_VALUE - 1, ledger.adjust(PLAYER, -1));
        assertEquals(0, ledger.adjust(PLAYER, -(Integer.MAX_VALUE - 1)));
        assertEquals(0, ledger.reputations().size());
    }
}
