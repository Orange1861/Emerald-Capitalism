package com.orangevillager61.emeraldcapitalism.world.village;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageRelationshipTest {

    @Test
    void persistsGovernorAndCandidateRoles() {
        VillageRecord original = new VillageRecord(
                UUID.randomUUID(), BlockPos.ZERO, new AABB(-2, -2, -2, 2, 2, 2));
        UUID governor = UUID.randomUUID();
        UUID candidate = UUID.randomUUID();

        assertTrue(original.setGovernor(governor));
        assertTrue(original.becomeGovernorCandidate(candidate, 100, 40L));
        assertFalse(original.isGovernorCandidateAttackGraceElapsed(1_039L));
        assertTrue(original.isGovernorCandidateAttackGraceElapsed(1_040L));
        assertFalse(original.becomeGovernorCandidate(governor, 100, 40L));

        VillageRecord restored = VillageRecord.CODEC.parse(NbtOps.INSTANCE,
                        VillageRecord.CODEC.encodeStart(NbtOps.INSTANCE, original).result().orElseThrow())
                .result().orElseThrow();

        assertTrue(restored.isGovernor(governor));
        assertTrue(restored.isGovernorCandidate(candidate));
        assertFalse(restored.isGovernorCandidate(governor));
        assertFalse(restored.isGovernorCandidateAttackGraceElapsed(1_039L));
        assertTrue(restored.isGovernorCandidateAttackGraceElapsed(1_040L));
    }

    @Test
    void candidateActionEndsAttackGracePeriod() {
        VillageRecord village = new VillageRecord(
                UUID.randomUUID(), BlockPos.ZERO, new AABB(-2, -2, -2, 2, 2, 2));
        UUID candidate = UUID.randomUUID();

        assertTrue(village.becomeGovernorCandidate(candidate, 100, 40L));
        assertFalse(village.isGovernorCandidateAttackGraceElapsed(40L));
        assertTrue(village.endGovernorCandidateAttackGrace(candidate));
        assertTrue(village.isGovernorCandidateAttackGraceElapsed(40L));
        assertFalse(village.endGovernorCandidateAttackGrace(candidate));
    }

    @Test
    void villageAllowsOnlyOneCandidate() {
        VillageRecord village = new VillageRecord(
                UUID.randomUUID(), BlockPos.ZERO, new AABB(-2, -2, -2, 2, 2, 2));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(village.becomeGovernorCandidate(first, 100, 0L));
        assertFalse(village.becomeGovernorCandidate(second, 100, 0L));
        assertTrue(village.isGovernorCandidate(first));
        assertFalse(village.isGovernorCandidate(second));
    }
}
