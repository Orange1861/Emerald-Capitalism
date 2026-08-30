package com.orangevillager61.emeraldcapitalism.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TogglePOIOverlayPacketTest {

    private static final UUID VILLAGE_ID = UUID.fromString("12345678-1234-5678-1234-567812345678");
    private static final UUID PLAYER_ID = UUID.fromString("87654321-4321-8765-4321-876543214321");

    @AfterEach
    void clearSubscriptions() {
        POIOverlaySubscriptions.clearAll();
    }

    @Test
    void explicitVillageTargetSurvivesPayloadRoundTrip() {
        TogglePOIOverlayPacket source = TogglePOIOverlayPacket.forVillage(VILLAGE_ID);
        TogglePOIOverlayPacket decoded = roundTrip(source);

        assertTrue(decoded.hasVillageId());
        assertEquals(VILLAGE_ID, decoded.villageId());
    }

    @Test
    void defaultToggleRetainsNearestVillageFallback() {
        TogglePOIOverlayPacket decoded = roundTrip(new TogglePOIOverlayPacket());

        assertFalse(decoded.hasVillageId());
        assertEquals(new UUID(0, 0), decoded.villageId());
    }

    @Test
    void existingOverlaySubscriptionRetargetsWithoutTogglingOff() {
        UUID earlierVillageId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        POIOverlaySubscriptions.subscribe(PLAYER_ID, earlierVillageId);

        POIOverlaySubscriptions.retargetIfSubscribed(PLAYER_ID, VILLAGE_ID);

        assertTrue(POIOverlaySubscriptions.isSubscribed(PLAYER_ID));
        assertTrue(POIOverlaySubscriptions.isSubscribedTo(PLAYER_ID, VILLAGE_ID));
        assertFalse(POIOverlaySubscriptions.isSubscribedTo(PLAYER_ID, earlierVillageId));
    }

    private static TogglePOIOverlayPacket roundTrip(TogglePOIOverlayPacket source) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            TogglePOIOverlayPacket.STREAM_CODEC.encode(buffer, source);
            return TogglePOIOverlayPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }
}
