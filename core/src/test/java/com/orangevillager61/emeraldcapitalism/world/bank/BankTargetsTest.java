package com.orangevillager61.emeraldcapitalism.world.bank;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BankTargetsTest {

    @Test
    void pumpkinTargetUsesGolemCapacityPlusBaseReserve() {
        assertEquals(2, BankTargets.pumpkinTarget(0));
        assertEquals(17, BankTargets.pumpkinTarget(3));
    }

    @Test
    void plankTargetUsesGolemsChestsBedsDoorsAndBaseReserve() {
        assertEquals(128, BankTargets.plankTarget(0, 0, 0, 0));
        assertEquals(4 * 3 + 12 * 2 + 8 * 5 + 6 * 7 + 128,
                BankTargets.plankTarget(3, 2, 5, 7));
    }

    @Test
    void skrimisherLimitIsTwiceTheEmeraldGolemCount() {
        assertEquals(0, BankTargets.emeraldSkrimisherLimit(0));
        assertEquals(6, BankTargets.emeraldSkrimisherLimit(3));
        assertEquals(Integer.MAX_VALUE, BankTargets.emeraldSkrimisherLimit(Integer.MAX_VALUE));
    }

    @Test
    void breadTargetUsesRegisteredVillagerCount() {
        assertEquals(0, BankTargets.breadTarget(0));
        assertEquals(15, BankTargets.breadTarget(1));
        assertEquals(75, BankTargets.breadTarget(5));
    }

    @Test
    void targetsClampNegativeAndOverflowingInputs() {
        assertEquals(2, BankTargets.pumpkinTarget(-1));
        assertEquals(0, BankTargets.breadTarget(-1));
        assertEquals(Integer.MAX_VALUE, BankTargets.pumpkinTarget(Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, BankTargets.breadTarget(Integer.MAX_VALUE));
        assertEquals(128, BankTargets.plankTarget(-1, -1, -1, -1));
        assertEquals(Integer.MAX_VALUE,
                BankTargets.plankTarget(Integer.MAX_VALUE, Integer.MAX_VALUE,
                        Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void breadTradeQuantitiesUseTheRequestedPersonalThresholds() {
        assertEquals(30, BankTargets.breadPurchaseQuantity(0));
        assertEquals(16, BankTargets.breadPurchaseQuantity(14));
        assertEquals(0, BankTargets.breadPurchaseQuantity(15));
        assertEquals(0, BankTargets.breadSaleQuantity(37));
        assertEquals(8, BankTargets.breadSaleQuantity(38));
        assertEquals(10, BankTargets.breadSaleQuantity(40));
    }

    @Test
    void foodDaysScaleBreadReserveAndTradeQuantities() {
        assertEquals(30, BankTargets.breadTarget(2, 5));
        assertEquals(60, BankTargets.breadTarget(2, 10));
        assertEquals(60, BankTargets.breadPurchaseQuantity(0, 10));
        assertEquals(40, BankTargets.breadSaleQuantity(100, 10));
    }

    @Test
    void coalTargetUsesBaseReservePlusEmeraldOreCount() {
        assertEquals(192, BankTargets.coalTarget(0));
        assertEquals(197, BankTargets.coalTarget(5));
    }

    @Test
    void coalTargetClampsNegativeAndOverflowingOreCounts() {
        assertEquals(192, BankTargets.coalTarget(-1));
        assertEquals(Integer.MAX_VALUE, BankTargets.coalTarget(Integer.MAX_VALUE));
    }
}
