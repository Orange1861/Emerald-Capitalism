package com.orangevillager61.emeraldcapitalism.world.bank;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankReputationDataCodecTest {

    @Test
    void codecRoundTripPreservesIndependentPlayerReputations() {
        BankReputationData original = new BankReputationData();
        UUID positive = UUID.randomUUID();
        UUID negative = UUID.randomUUID();
        original.adjustReputation(positive, 12);
        original.adjustReputation(negative, -100);

        BankReputationData restored = BankReputationData.CODEC.parse(NbtOps.INSTANCE,
                        BankReputationData.CODEC.encodeStart(NbtOps.INSTANCE, original).result().orElseThrow())
                .result().orElseThrow();
        assertEquals(12, restored.getReputation(positive));
        assertEquals(-100, restored.getReputation(negative));
        assertEquals(0, restored.getReputation(UUID.randomUUID()));
    }

    @Test
    void currentSavedDataNbtAdapterRoundTripsTheCodec() {
        BankReputationData original = new BankReputationData();
        UUID player = UUID.randomUUID();
        original.adjustReputation(player, -100);

        CompoundTag saved = original.save(new CompoundTag(), null);
        BankReputationData restored = BankReputationData.load(saved, null);

        assertEquals(-100, restored.getReputation(player));
    }

    @Test
    void zeroAndNoOpDeltasDoNotCreateEntriesOrDirtyData() {
        BankReputationData data = new BankReputationData();
        UUID player = UUID.randomUUID();

        assertEquals(0, data.adjustReputation(player, 0));
        assertFalse(data.isDirty());
        data.adjustReputation(player, -100);
        data.setDirty(false);
        data.adjustReputation(player, 100);
        assertTrue(data.getReputations().isEmpty());
        assertTrue(data.isDirty());
        data.setDirty(false);
        assertFalse(data.getReputations().containsKey(player));
    }

    @Test
    void oversizedPersistedReputationCollectionIsRejectedAndRecoveryIsBounded() {
        CompoundTag root = new CompoundTag();
        ListTag reputations = new ListTag();
        for (int i = 0; i <= BankReputationData.MAX_PERSISTED_REPUTATIONS; i++) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("player_id", new UUID(0L, i));
            entry.putInt("reputation", -i - 1);
            reputations.add(entry);
        }
        root.put("reputations", reputations);

        assertTrue(BankReputationData.CODEC.parse(NbtOps.INSTANCE, root).error().isPresent());
        assertTrue(BankReputationData.load(root, null).getReputations().size()
                <= BankReputationData.MAX_PERSISTED_REPUTATIONS);
    }
}
