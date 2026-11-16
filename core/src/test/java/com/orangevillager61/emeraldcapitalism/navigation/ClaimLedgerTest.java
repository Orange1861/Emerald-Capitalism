package com.orangevillager61.emeraldcapitalism.navigation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimLedgerTest {

    private static final UUID VILLAGE = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID OTHER_VILLAGE = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static ClaimLedger.Key key(UUID village, String profession, ClaimLedger.Kind kind, long target) {
        return new ClaimLedger.Key(village, profession, kind, target);
    }

    @Test
    void aTargetHasOneHolder() {
        ClaimLedger ledger = new ClaimLedger();
        ClaimLedger.Key tree = key(VILLAGE, "lumberjack", ClaimLedger.Kind.TREE, 7L);

        assertTrue(ledger.tryClaim(tree, ALICE).isPresent());
        assertTrue(ledger.tryClaim(tree, BOB).isEmpty());
        assertEquals(Optional.of(ALICE), ledger.holder(tree));
    }

    @Test
    void claimingAgainAsTheHolderReturnsTheSameClaim() {
        ClaimLedger ledger = new ClaimLedger();
        ClaimLedger.Key tree = key(VILLAGE, "lumberjack", ClaimLedger.Kind.TREE, 7L);

        ClaimLedger.Claim first = ledger.tryClaim(tree, ALICE).orElseThrow();
        ClaimLedger.Claim again = ledger.tryClaim(tree, ALICE).orElseThrow();

        assertEquals(first, again);
        assertEquals(1, ledger.size());
    }

    @Test
    void identicalTargetsInTwoProfessionsDoNotMeet() {
        ClaimLedger ledger = new ClaimLedger();
        ClaimLedger.Claim farmer = ledger.tryClaim(
                key(VILLAGE, "farmer", ClaimLedger.Kind.WORK_CELL, 42L), ALICE).orElseThrow();
        ClaimLedger.Claim lumberjack = ledger.tryClaim(
                key(VILLAGE, "lumberjack", ClaimLedger.Kind.WORK_CELL, 42L), BOB).orElseThrow();

        assertTrue(ledger.release(farmer));

        assertTrue(ledger.isCurrent(lumberjack), "releasing the farmer's claim must not touch the lumberjack's");
        assertEquals(Optional.of(BOB), ledger.holder(lumberjack.key()));
    }

    @Test
    void identicalTargetsInTwoVillagesDoNotMeet() {
        ClaimLedger ledger = new ClaimLedger();
        assertTrue(ledger.tryClaim(key(VILLAGE, "farmer", ClaimLedger.Kind.MATURE_CROP, 9L), ALICE).isPresent());
        assertTrue(ledger.tryClaim(key(OTHER_VILLAGE, "farmer", ClaimLedger.Kind.MATURE_CROP, 9L), BOB).isPresent());
    }

    @Test
    void aLateReleaseFromAnOldTaskCannotReleaseTheNewClaim() {
        ClaimLedger ledger = new ClaimLedger();
        ClaimLedger.Key cell = key(VILLAGE, "farmer", ClaimLedger.Kind.WORK_CELL, 5L);
        ClaimLedger.Claim old = ledger.tryClaim(cell, ALICE).orElseThrow();
        assertTrue(ledger.release(old));
        ClaimLedger.Claim fresh = ledger.tryClaim(cell, ALICE).orElseThrow();

        assertNotEquals(old.generation(), fresh.generation());
        assertFalse(ledger.release(old), "cleanup carrying the old generation must release nothing");
        assertTrue(ledger.isCurrent(fresh));
    }

    @Test
    void releaseOwnerIsScopedToProfessionWhenAsked() {
        ClaimLedger ledger = new ClaimLedger();
        ledger.tryClaim(key(VILLAGE, "farmer", ClaimLedger.Kind.WORK_CELL, 1L), ALICE);
        ledger.tryClaim(key(VILLAGE, "lumberjack", ClaimLedger.Kind.TREE, 2L), ALICE);

        assertEquals(1, ledger.releaseOwner(ALICE, "farmer"));
        assertEquals(1, ledger.size());
        assertEquals(1, ledger.releaseOwner(ALICE));
        assertEquals(0, ledger.size());
    }

    @Test
    void severalClaimsAreTakenTreeFirstAndFurnaceSecondWhateverTheInputOrder() {
        ClaimLedger ledger = new ClaimLedger();
        ClaimLedger.Key furnace = key(VILLAGE, "lumberjack", ClaimLedger.Kind.FURNACE, 11L);
        ClaimLedger.Key tree = key(VILLAGE, "lumberjack", ClaimLedger.Kind.TREE, 12L);

        List<ClaimLedger.Claim> claims = ledger.tryClaimAll(List.of(furnace, tree), ALICE).orElseThrow();

        assertEquals(List.of(ClaimLedger.Kind.TREE, ClaimLedger.Kind.FURNACE),
                claims.stream().map(claim -> claim.key().kind()).toList());
        assertEquals(2, ledger.releaseAll(claims));
        assertEquals(0, ledger.size());
    }

    @Test
    void aFailedMultiClaimTakesNothingAndLeavesExistingClaimsAlone() {
        ClaimLedger ledger = new ClaimLedger();
        ClaimLedger.Key tree = key(VILLAGE, "lumberjack", ClaimLedger.Kind.TREE, 1L);
        ClaimLedger.Key furnace = key(VILLAGE, "lumberjack", ClaimLedger.Kind.FURNACE, 2L);
        ClaimLedger.Claim bobsFurnace = ledger.tryClaim(furnace, BOB).orElseThrow();

        assertTrue(ledger.tryClaimAll(List.of(tree, furnace), ALICE).isEmpty());

        assertTrue(ledger.holder(tree).isEmpty(), "the tree taken first must be rolled back");
        assertTrue(ledger.isCurrent(bobsFurnace));
    }

    @Test
    void aFailedMultiClaimKeepsAClaimTheOwnerAlreadyHeld() {
        ClaimLedger ledger = new ClaimLedger();
        ClaimLedger.Key tree = key(VILLAGE, "lumberjack", ClaimLedger.Kind.TREE, 1L);
        ClaimLedger.Key furnace = key(VILLAGE, "lumberjack", ClaimLedger.Kind.FURNACE, 2L);
        ClaimLedger.Claim heldTree = ledger.tryClaim(tree, ALICE).orElseThrow();
        ledger.tryClaim(furnace, BOB);

        assertTrue(ledger.tryClaimAll(List.of(tree, furnace), ALICE).isEmpty());

        assertTrue(ledger.isCurrent(heldTree));
    }

    @Test
    void anAttachedPumpkinIsClaimedByVillageFarmerCellPumpkinAndOwner() {
        ClaimLedger ledger = new ClaimLedger();
        ClaimLedger.Key pumpkin = key(VILLAGE, "farmer", ClaimLedger.Kind.ATTACHED_PUMPKIN, 99L);

        ClaimLedger.Claim claim = ledger.tryClaim(pumpkin, ALICE).orElseThrow();

        assertEquals(VILLAGE, claim.key().villageId());
        assertEquals("farmer", claim.key().profession());
        assertEquals(ALICE, claim.owner());
        assertTrue(claim.generation() > 0L);
        assertTrue(ledger.tryClaim(pumpkin, BOB).isEmpty());
    }

    @Test
    void invalidKeysAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> key(VILLAGE, " ", ClaimLedger.Kind.TREE, 1L));
        assertThrows(NullPointerException.class,
                () -> key(null, "farmer", ClaimLedger.Kind.TREE, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new ClaimLedger.Claim(key(VILLAGE, "farmer", ClaimLedger.Kind.TREE, 1L), ALICE, 0L));
    }
}
