package com.orangevillager61.emeraldcapitalism.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagePOIDynamicPacketTest {

    private static final UUID VILLAGE_ID =
            UUID.fromString("12345678-1234-5678-1234-567812345678");
    private static final UUID VILLAGER_ID =
            UUID.fromString("87654321-4321-8765-4321-876543214321");

    @Test
    void requestRetainsScanTransitionState() {
        RequestVillagePOIDynamicDataPacket source =
                new RequestVillagePOIDynamicDataPacket(VILLAGE_ID, true);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            RequestVillagePOIDynamicDataPacket.STREAM_CODEC.encode(buffer, source);
            assertEquals(source, RequestVillagePOIDynamicDataPacket.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void dynamicResponseRoundTripsViewerSpecificValues() {
        VillagePOIDynamicDataPacket source = new VillagePOIDynamicDataPacket(
                true, VILLAGE_ID, false, true,
                List.of(new VillagePOIDynamicDataPacket.VillagerState(VILLAGER_ID, 17.5F, -12)),
                2, 1, 3, -25);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            VillagePOIDynamicDataPacket.STREAM_CODEC.encode(buffer, source);
            VillagePOIDynamicDataPacket decoded =
                    VillagePOIDynamicDataPacket.STREAM_CODEC.decode(buffer);
            assertEquals(source, decoded);
            assertTrue(decoded.hasCompletedScan());
        } finally {
            buffer.release();
        }
    }
}
