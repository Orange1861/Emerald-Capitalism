package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers all custom network payloads for the mod.
 */
public final class ECAPNetworking {

    private ECAPNetworking() {}

    public static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(EmeraldCapitalism.MODID).versioned("5");

        // Client → Server: request POI data for a village
        registrar.playToServer(
                RequestVillagePOIsPacket.TYPE,
                RequestVillagePOIsPacket.STREAM_CODEC,
                RequestVillagePOIsPacket::handle
        );

        // Server → Client: village POI data response
        registrar.playToClient(
                VillagePOIDataPacket.TYPE,
                VillagePOIDataPacket.STREAM_CODEC,
                VillagePOIDataPacket::handle
        );

        // The open ledger refreshes live health, opinion, relationship, and golem values.
        registrar.playToServer(
                RequestVillagePOIDynamicDataPacket.TYPE,
                RequestVillagePOIDynamicDataPacket.STREAM_CODEC,
                RequestVillagePOIDynamicDataPacket::handle
        );
        registrar.playToClient(
                VillagePOIDynamicDataPacket.TYPE,
                VillagePOIDynamicDataPacket.STREAM_CODEC,
                VillagePOIDynamicDataPacket::handle
        );

        // Client → Server: toggle POI overlay subscription
        registrar.playToServer(
                TogglePOIOverlayPacket.TYPE,
                TogglePOIOverlayPacket.STREAM_CODEC,
                TogglePOIOverlayPacket::handle
        );

        // Client → Server: request a full bounding-box re-scan
        registrar.playToServer(
                RequestFullScanPacket.TYPE,
                RequestFullScanPacket.STREAM_CODEC,
                RequestFullScanPacket::handle
        );

        // Client → Server: rename a village
        registrar.playToServer(
                RenameVillagePacket.TYPE,
                RenameVillagePacket.STREAM_CODEC,
                RenameVillagePacket::handle
        );

        // Client → Server: expand village bounds and re-scan
        registrar.playToServer(
                RequestExpandBoundsPacket.TYPE,
                RequestExpandBoundsPacket.STREAM_CODEC,
                RequestExpandBoundsPacket::handle
        );

        // Client → Server: update village welcome message
        registrar.playToServer(
                UpdateWelcomeMessagePacket.TYPE,
                UpdateWelcomeMessagePacket.STREAM_CODEC,
                UpdateWelcomeMessagePacket::handle
        );

        // Client → Server: change a village-owned repair setting
        registrar.playToServer(
                SetVillageRepairPacket.TYPE,
                SetVillageRepairPacket.STREAM_CODEC,
                SetVillageRepairPacket::handle
        );

        // Client → Server: reset a village block cache
        registrar.playToServer(
                ResetVillageCachePacket.TYPE,
                ResetVillageCachePacket.STREAM_CODEC,
                ResetVillageCachePacket::handle
        );

        // Client → Server: apply to become a governor candidate
        registrar.playToServer(
                BecomeGovernorCandidatePacket.TYPE,
                BecomeGovernorCandidatePacket.STREAM_CODEC,
                BecomeGovernorCandidatePacket::handle
        );

        // Client → Server: change the bank's independent/controller state
        registrar.playToServer(
                SetBankControlPacket.TYPE,
                SetBankControlPacket.STREAM_CODEC,
                SetBankControlPacket::handle
        );

        // Client → Server: rename a bank
        registrar.playToServer(
                RenameBankPacket.TYPE,
                RenameBankPacket.STREAM_CODEC,
                RenameBankPacket::handle
        );

        registrar.playToServer(
                MarketTradePacket.TYPE,
                MarketTradePacket.STREAM_CODEC,
                MarketTradePacket::handle
        );
        registrar.playToClient(
                MarketDataPacket.TYPE,
                MarketDataPacket.STREAM_CODEC,
                MarketDataPacket::handle
        );
    }
}
