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
        assertTrue(original.becomeGovernorCandidate(candidate, 100));
        assertFalse(original.becomeGovernorCandidate(governor, 100));

        VillageRecord restored = VillageRecord.CODEC.parse(NbtOps.INSTANCE,
                        VillageRecord.CODEC.encodeStart(NbtOps.INSTANCE, original).result().orElseThrow())
                .result().orElseThrow();

        assertTrue(restored.isGovernor(governor));
        assertTrue(restored.isGovernorCandidate(candidate));
        assertFalse(restored.isGovernorCandidate(governor));
    }

    @Test
    void villageAllowsOnlyOneCandidate() {
        VillageRecord village = new VillageRecord(
                UUID.randomUUID(), BlockPos.ZERO, new AABB(-2, -2, -2, 2, 2, 2));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(village.becomeGovernorCandidate(first, 100));
        assertFalse(village.becomeGovernorCandidate(second, 100));
        assertTrue(village.isGovernorCandidate(first));
        assertFalse(village.isGovernorCandidate(second));
    }
}
