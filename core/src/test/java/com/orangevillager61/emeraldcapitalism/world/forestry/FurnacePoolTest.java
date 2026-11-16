package com.orangevillager61.emeraldcapitalism.world.forestry;

import com.orangevillager61.emeraldcapitalism.navigation.ClaimLedger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FurnacePoolTest {

    private static final UUID VILLAGE = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final long F1 = 100L;
    private static final long F2 = 200L;
    private static final long READY = FurnacePool.RELOAD_GRACE_TICKS + 1L;

    private final ClaimLedger ledger = new ClaimLedger();

    private FurnacePool pool(long... furnaces) {
        FurnacePool pool = new FurnacePool(VILLAGE, ledger, 0L);
        for (long furnace : furnaces) {
            pool.addFurnace(furnace);
        }
        return pool;
    }

    private static FurnaceClaim.Observation obs(int in, int out) {
        return new FurnaceClaim.Observation(in, out, false, false, false);
    }

    // ----- claims -----

    @Test
    void eachFurnaceHasAtMostOneClaim() {
        FurnacePool pool = pool(F1);

        assertTrue(pool.request(A, READY).isPresent());
        assertTrue(pool.request(B, READY).isEmpty(), "a second lumberjack must not get the only furnace");
        assertEquals(1, ledger.size());
    }

    @Test
    void askingAgainReturnsTheSameClaim() {
        FurnacePool pool = pool(F1, F2);
        FurnacePool.Grant first = pool.request(A, READY).orElseThrow();
        FurnacePool.Grant again = pool.request(A, READY + 100).orElseThrow();

        assertEquals(first.claim(), again.claim());
        assertEquals(1, ledger.size(), "a holder must never take a second furnace");
    }

    @Test
    void aRefusedLumberjackQueuesAndGetsNoFurnace() {
        FurnacePool pool = pool(F1);
        pool.request(A, READY);

        assertTrue(pool.request(B, READY).isEmpty());

        assertEquals(List.of(B), pool.queueOrder());
    }

    @Test
    void theLongestWaitingLumberjackGetsTheFreedFurnace() {
        FurnacePool pool = pool(F1);
        FurnacePool.Grant holder = pool.request(A, READY).orElseThrow();
        pool.request(B, READY + 10);
        pool.request(C, READY + 20);
        assertEquals(List.of(B, C), pool.queueOrder());

        assertTrue(pool.release(holder.claim()));

        assertTrue(pool.request(C, READY + 30).isEmpty(), "C is behind B and must not jump the queue");
        assertTrue(pool.request(B, READY + 30).isPresent());
        assertEquals(List.of(C), pool.queueOrder());
    }

    @Test
    void aLumberjackThatStillOwesCharcoalRejoinsBehindEveryoneAlreadyWaiting() {
        FurnacePool pool = pool(F1);
        FurnacePool.Grant a = pool.request(A, READY).orElseThrow();
        pool.request(B, READY + 10);
        pool.release(a.claim());

        pool.request(A, READY + 20);

        assertEquals(List.of(B, A), pool.queueOrder());
    }

    @Test
    void nothingIsOfferedBeforeTheReloadGraceEnds() {
        FurnacePool pool = pool(F1);
        assertTrue(pool.request(A, FurnacePool.RELOAD_GRACE_TICKS - 1L).isEmpty());
        assertTrue(pool.request(A, FurnacePool.RELOAD_GRACE_TICKS).isPresent());
    }

    @Test
    void aStaleQueueEntryDoesNotBlockTheQueue() {
        FurnacePool pool = pool(F1);
        pool.request(A, READY);
        FurnacePool.Grant a = pool.request(A, READY).orElseThrow();
        pool.request(B, READY + 10);
        pool.release(a.claim());

        long later = READY + 10 + FurnacePool.QUEUE_STALE_TICKS + 1L;
        assertTrue(pool.request(C, later).isPresent(), "B stopped asking, so it no longer holds the front");
    }

    @Test
    void aVillageWithoutFurnacesNeverGrantsOne() {
        FurnacePool pool = pool();
        assertTrue(pool.request(A, READY).isEmpty());
    }

    @Test
    void furnacesAreChosenInAStableOrder() {
        FurnacePool pool = pool(F2, F1);
        assertEquals(F1, pool.request(A, READY).orElseThrow().position());
        assertEquals(F2, pool.request(B, READY).orElseThrow().position());
    }

    // ----- removal, claims by generation -----

    @Test
    void aBrokenFurnaceDropsItsClaimAndItsOwnerRejoinsTheQueue() {
        FurnacePool pool = pool(F1);
        FurnacePool.Grant a = pool.request(A, READY).orElseThrow();

        pool.removeFurnace(F1, READY + 5);

        assertFalse(pool.containsFurnace(F1));
        assertFalse(ledger.isCurrent(a.claim()));
        assertEquals(List.of(A), pool.queueOrder());
    }

    @Test
    void lateCleanupWithAStaleClaimReleasesNothing() {
        FurnacePool pool = pool(F1);
        FurnacePool.Grant old = pool.request(A, READY).orElseThrow();
        pool.release(old.claim());
        FurnacePool.Grant fresh = pool.request(A, READY + 1).orElseThrow();

        assertNotEquals(old.claim().generation(), fresh.claim().generation());
        assertFalse(pool.release(old.claim()));
        assertTrue(ledger.isCurrent(fresh.claim()));
    }

    // ----- stages and items -----

    @Test
    void aClaimIsReleasedOnlyWhenTheFurnaceIsVerifiedEmpty() {
        FurnacePool pool = pool(F1);
        FurnacePool.Grant a = pool.request(A, READY).orElseThrow();
        pool.recordLoaded(a.claim(), 8, READY);
        a.batch().beginSmelting();

        FurnacePool.CheckResult smelting = pool.check(a.claim(), obs(5, 3)).orElseThrow();
        assertFalse(smelting.released());
        assertEquals(FurnaceClaim.Verdict.CONTINUE, smelting.reconciliation().verdict());

        FurnacePool.CheckResult done = pool.check(a.claim(), obs(0, 8)).orElseThrow();
        assertFalse(done.released(), "charcoal is still in the output slot");
        assertEquals(8, done.reconciliation().takeCharcoal());
        assertEquals(FurnaceClaim.Stage.COLLECTING, a.batch().stage());

        a.batch().recordCollected(8);
        FurnacePool.CheckResult empty = pool.check(a.claim(), obs(0, 0)).orElseThrow();
        assertTrue(empty.released());
        assertFalse(pool.isClaimed(F1));
    }

    @Test
    void aLogRemovedBeforeItSmeltedRefundsOneConversion() {
        FurnacePool pool = pool(F1);
        FurnacePool.Grant a = pool.request(A, READY).orElseThrow();
        pool.recordLoaded(a.claim(), 8, READY);

        FurnacePool.CheckResult result = pool.check(a.claim(), obs(7, 0)).orElseThrow();

        assertEquals(1, result.reconciliation().refundedConversions());
        assertEquals(0, result.reconciliation().removedCharcoalCounted());
        assertEquals(7, a.batch().charcoalOutstanding());
    }

    @Test
    void charcoalRemovedFromTheOutputCountsAsDoneAndRefundsNothing() {
        FurnacePool pool = pool(F1);
        FurnacePool.Grant a = pool.request(A, READY).orElseThrow();
        pool.recordLoaded(a.claim(), 8, READY);
        pool.check(a.claim(), obs(5, 3));

        FurnacePool.CheckResult result = pool.check(a.claim(), obs(5, 1)).orElseThrow();

        assertEquals(0, result.reconciliation().refundedConversions());
        assertEquals(2, result.reconciliation().removedCharcoalCounted());
        assertEquals(6, a.batch().charcoalOutstanding());
    }

    @Test
    void theHolderNeverTakesMoreCharcoalThanTheClaimPlaced() {
        FurnacePool pool = pool(F1);
        FurnacePool.Grant a = pool.request(A, READY).orElseThrow();
        pool.recordLoaded(a.claim(), 2, READY);
        pool.check(a.claim(), obs(1, 1));

        // Someone added ten charcoal of their own to the output.
        FurnacePool.CheckResult result = pool.check(a.claim(), obs(0, 11)).orElseThrow();

        assertEquals(2, result.reconciliation().takeCharcoal());
    }

    @Test
    void onlyForeignItemsRemainingReleasesTheClaimAndMarksTheFurnaceOccupied() {
        FurnacePool pool = pool(F1, F2);
        FurnacePool.Grant a = pool.request(A, READY).orElseThrow();
        pool.recordLoaded(a.claim(), 1, READY);
        pool.check(a.claim(), obs(0, 1));
        a.batch().recordCollected(1);

        FurnacePool.CheckResult result = pool.check(a.claim(),
                new FurnaceClaim.Observation(0, 0, true, false, false)).orElseThrow();

        assertTrue(result.released());
        assertTrue(pool.isOccupied(F1));
        assertEquals(F2, pool.request(B, READY + 1).orElseThrow().position(),
                "an occupied furnace is not offered to any claim");
        pool.clearOccupied(F1);
        assertTrue(pool.request(C, READY + 2).isPresent());
    }

    @Test
    void foreignItemsAlongsideTheClaimsOwnKeepTheClaim() {
        FurnacePool pool = pool(F1);
        FurnacePool.Grant a = pool.request(A, READY).orElseThrow();
        pool.recordLoaded(a.claim(), 4, READY);

        FurnacePool.CheckResult result = pool.check(a.claim(),
                new FurnaceClaim.Observation(4, 0, false, false, true)).orElseThrow();

        assertFalse(result.released());
        assertTrue(result.reconciliation().foreignRemains());
    }

    @Test
    void aStaleClaimCannotCheckAFurnace() {
        FurnacePool pool = pool(F1);
        FurnacePool.Grant old = pool.request(A, READY).orElseThrow();
        pool.release(old.claim());
        pool.request(B, READY + 1);

        assertTrue(pool.check(old.claim(), obs(0, 0)).isEmpty());
        assertTrue(pool.isClaimed(F1), "B's claim must survive A's late check");
    }

    // ----- orphans -----

    @Test
    void aHolderThatLeavesHandsTheBatchToTheVillageAndTheNextInLineCollectsIt() {
        FurnacePool pool = pool(F1);
        FurnacePool.Grant a = pool.request(A, READY).orElseThrow();
        pool.recordLoaded(a.claim(), 8, READY);
        pool.request(B, READY + 10);

        assertTrue(pool.handToVillage(A));

        assertTrue(pool.isOrphaned(F1));
        assertFalse(ledger.isCurrent(a.claim()), "the old holder's claim generation is gone");
        FurnacePool.Grant b = pool.request(B, READY + 20).orElseThrow();
        assertTrue(b.collectingOrphan());
        assertEquals(F1, b.position());
        assertFalse(pool.isOrphaned(F1));
        assertTrue(pool.check(a.claim(), obs(0, 8)).isEmpty(), "a returning holder finds its claim gone");
    }

    @Test
    void aBatchLeftUncollectedForFiveMinutesAfterItIsReadyPassesToTheVillage() {
        FurnacePool pool = pool(F1);
        FurnacePool.Grant a = pool.request(A, READY).orElseThrow();
        pool.recordLoaded(a.claim(), 8, READY);
        long ready = READY + 8 * FurnacePool.SMELT_TICKS_PER_ITEM;

        pool.prune(ready + FurnacePool.ORPHAN_TICKS - 1L);
        assertFalse(pool.isOrphaned(F1));
        pool.prune(ready + FurnacePool.ORPHAN_TICKS);
        assertTrue(pool.isOrphaned(F1));
    }

    @Test
    void anOrphanedFurnaceIsNotGivenToALumberjackWhoIsNotFirstInLine() {
        FurnacePool pool = pool(F1);
        FurnacePool.Grant a = pool.request(A, READY).orElseThrow();
        pool.recordLoaded(a.claim(), 8, READY);
        pool.request(B, READY + 1);
        pool.request(C, READY + 2);
        pool.handToVillage(A);

        assertTrue(pool.request(C, READY + 3).isEmpty());
        assertTrue(pool.request(B, READY + 3).isPresent());
    }

    // ----- reload -----

    @Test
    void aSavedFurnacePositionBecomesAClaimAgainAndNoOneElseGetsItFirst() {
        FurnacePool reloaded = new FurnacePool(VILLAGE, new ClaimLedger(), 5_000L);
        reloaded.addFurnace(F1);
        reloaded.addFurnace(F2);

        FurnacePool.Grant restored = reloaded.restoreClaim(A, F1, obs(0, 6)).orElseThrow();

        assertEquals(F1, restored.position());
        assertEquals(FurnaceClaim.Stage.COLLECTING, restored.batch().stage());
        assertEquals(6, restored.batch().charcoalOutstanding());
        assertTrue(reloaded.request(B, 5_000L + FurnacePool.RELOAD_GRACE_TICKS + 1).orElseThrow()
                .position() != F1, "B must not be offered the restored furnace");
    }

    @Test
    void aSavedPositionThatIsNoLongerInThePoolIsNotRestored() {
        FurnacePool reloaded = new FurnacePool(VILLAGE, new ClaimLedger(), 0L);
        assertEquals(Optional.empty(), reloaded.restoreClaim(A, F1, obs(0, 0)));
    }

    @Test
    void aSavedPositionHeldByAnotherLumberjackIsNotRestored() {
        FurnacePool pool = pool(F1);
        pool.request(B, READY);
        assertEquals(Optional.empty(), pool.restoreClaim(A, F1, obs(0, 0)));
    }

    @Test
    void aSavedPositionTheVillageTookOverIsNotRestoredToTheOldHolder() {
        FurnacePool pool = pool(F1);
        FurnacePool.Grant a = pool.request(A, READY).orElseThrow();
        pool.recordLoaded(a.claim(), 8, READY);
        pool.handToVillage(A);

        assertEquals(Optional.empty(), pool.restoreClaim(A, F1, obs(0, 8)));
    }

    // ----- quota cap -----

    @Test
    void aWaitingLumberjacksQuotaStopsGrowingAtTwoBatches() {
        double quota = 14.0D;
        quota = CharcoalProductionPolicy.accrueQuotaWhileWaiting(quota, 20, 0.5D);
        assertEquals(CharcoalProductionPolicy.WAITING_QUOTA_CAP, quota);
        assertEquals(16.0D, CharcoalProductionPolicy.accrueQuotaWhileWaiting(quota, 100, 0.5D));
    }

    @Test
    void aQuotaAlreadyAboveTheCapIsKeptNotCut() {
        assertEquals(30.0D, CharcoalProductionPolicy.accrueQuotaWhileWaiting(30.0D, 10, 0.5D));
    }

    @Test
    void belowTheCapAWaitingQuotaGrowsAsUsual() {
        assertEquals(CharcoalProductionPolicy.accrueQuota(2.0D, 4, 0.5D),
                CharcoalProductionPolicy.accrueQuotaWhileWaiting(2.0D, 4, 0.5D));
    }
}
