package com.orangevillager61.emeraldcapitalism.attachments;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumberjackProductionAttachmentCodecTest {

    @Test
    void quotaRoundTrips() {
        LumberjackProductionAttachment source = new LumberjackProductionAttachment();
        source.setCharcoalQuota(3.5D);

        LumberjackProductionAttachment decoded = roundTrip(source);

        assertEquals(3.5D, decoded.getCharcoalQuota(), 0.0001D);
    }

    @Test
    void malformedQuotaIsRejected() {
        CompoundTag malformed = new CompoundTag();
        malformed.putDouble("charcoal_quota", -1.0D);
        assertTrue(LumberjackProductionAttachment.CODEC.parse(NbtOps.INSTANCE, malformed)
                .error().isPresent());
    }

    private static LumberjackProductionAttachment roundTrip(LumberjackProductionAttachment source) {
        CompoundTag encoded = (CompoundTag) LumberjackProductionAttachment.CODEC
                .encodeStart(NbtOps.INSTANCE, source).result().orElseThrow();
        return LumberjackProductionAttachment.CODEC.parse(NbtOps.INSTANCE, encoded)
                .result().orElseThrow();
    }
}
