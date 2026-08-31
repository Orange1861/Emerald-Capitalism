package com.orangevillager61.emeraldcapitalism.client;

import com.orangevillager61.emeraldcapitalism.client.renderer.VillagePOIOverlayRenderer;
import com.orangevillager61.emeraldcapitalism.network.VillagePOIClientCache;
import com.orangevillager61.emeraldcapitalism.network.VillagePOIDataPacket;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientBoundaryTest {

    private static final String CLIENT_PACKAGE_PATH =
            "com/orangevillager61/emeraldcapitalism/client/";

    @AfterEach
    void clearClientState() {
        VillagePOIClientCache.clear();
        VillagePOIOverlayRenderer.clear();
    }

    @Test
    void commonSourcesContainNoClientOnlyReferences() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        assertTrue(Files.isDirectory(sourceRoot), "main source root is unavailable");

        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !sourceRoot.relativize(path).toString().replace('\\', '/')
                            .startsWith(CLIENT_PACKAGE_PATH))
                    .forEach(path -> {
                        try {
                            String source = Files.readString(path);
                            assertFalse(source.contains("net.minecraft.client"),
                                    "common source references Minecraft client code: " + path);
                            assertFalse(source.contains("net.neoforged.neoforge.client"),
                                    "common source references NeoForge client code: " + path);
                        } catch (IOException exception) {
                            throw new AssertionError("Could not read " + path, exception);
                        }
                    });
        }
    }

    @Test
    void emeraldGolemModelImplementationExistsOnlyUnderClientPackage() throws IOException {
        Path clientModel = Path.of("src/main/java", CLIENT_PACKAGE_PATH, "model", "EmeraldGolemModel.java");
        Path commonModel = Path.of("src/main/java", "com/orangevillager61/emeraldcapitalism",
                "entity", "model", "EmeraldGolemModel.java");
        assertTrue(Files.isRegularFile(clientModel), "client golem model is missing");
        assertFalse(Files.exists(commonModel), "common golem model still exists");

        String source = Files.readString(clientModel);
        assertTrue(source.contains("extends IronGolemModel"),
                "client golem model no longer owns the real implementation");
        assertFalse(source.contains("@Deprecated"), "obsolete compatibility wrapper remains");
        assertFalse(source.contains("entity.model.EmeraldGolemModel"),
                "client golem model delegates to the old common implementation");
    }

    @Test
    void clientLifecycleClearsPoiDataAndOverlayState() {
        UUID villageId = UUID.randomUUID();
        VillagePOIClientCache.update(new VillagePOIDataPacket(
                true,
                new VillagePOIDataPacket.Status(false, true),
                new VillagePOIDataPacket.Identity(villageId, "Boundary Test", false,
                        new BlockPos(4, 64, -3)),
                List.of(),
                new VillagePOIDataPacket.Totals(1, 1, List.of(), List.of()),
                new VillagePOIDataPacket.RepairData(0, 0, 0, true, true, List.of()),
                new VillagePOIDataPacket.EntityCounts(0, 0, 0, 0),
                new VillagePOIDataPacket.RelationshipData(0,
                        com.orangevillager61.emeraldcapitalism.world.village.VillageRelationship.NEUTRAL,
                        false),
                new VillagePOIDataPacket.Bounds(3, 63, -4, 5, 70, -2),
                new VillagePOIDataPacket.Messages("", "")));
        VillagePOIOverlayRenderer.toggle();

        assertTrue(VillagePOIClientCache.hasData());
        assertTrue(VillagePOIOverlayRenderer.isEnabled());

        VillagePOIClientCache.clear();
        VillagePOIOverlayRenderer.clear();

        assertFalse(VillagePOIClientCache.hasData(), "stale POI data survived client teardown");
        assertFalse(VillagePOIOverlayRenderer.isEnabled(), "overlay stayed enabled after client teardown");
        assertTrue(VillagePOIClientCache.getRecords().isEmpty());
        assertTrue(VillagePOIClientCache.getUpdateTimestamp() == 0L);
    }

    @Test
    void lifecycleHandlersUseCurrentOnePointClientTeardownBoundary() throws IOException {
        Path clientBootstrap = Path.of("src/main/java", CLIENT_PACKAGE_PATH,
                "EmeraldCapitalismClient.java");
        String source = Files.readString(clientBootstrap);

        assertTrue(source.contains("LevelEvent.Unload"), "client level unload is not handled");
        assertTrue(source.contains("ClientPlayerNetworkEvent.LoggingIn"),
                "client login is not handled");
        assertTrue(source.contains("ClientPlayerNetworkEvent.LoggingOut"),
                "client logout is not handled");
        assertTrue(source.contains("VillagePOIClientCache.clear()"),
                "client POI cache is not cleared at lifecycle boundaries");
        assertTrue(source.contains("VillagePOIOverlayRenderer.clear()"),
                "client overlay state is not cleared at lifecycle boundaries");
    }

    @Test
    void mutatingScreenPayloadsRemainBehindServerPlayerValidation() throws IOException {
        Path packetRoot = Path.of("src/main/java/com/orangevillager61/emeraldcapitalism/network");
        List<String> mutatingPayloads = List.of(
                "TogglePOIOverlayPacket.java",
                "RenameVillagePacket.java",
                "RenameBankPacket.java",
                "RequestFullScanPacket.java",
                "RequestExpandBoundsPacket.java",
                "UpdateWelcomeMessagePacket.java"
        );

        for (String payload : mutatingPayloads) {
            String source = Files.readString(packetRoot.resolve(payload));
            assertTrue(source.contains("PacketHandlerUtil.withServerPlayer(context"),
                    payload + " does not enforce a server-player packet boundary");
        }

        String bankSource = Files.readString(packetRoot.resolve("RenameBankPacket.java"));
        assertTrue(bankSource.contains("menu.getBlockPos().equals(pos)"),
                "bank screen rename does not bind the request to the open server menu");
        assertTrue(bankSource.contains("level.getBlockEntity(pos)"),
                "bank screen rename does not perform authoritative block-entity lookup");
        assertTrue(bankSource.contains("bank.isControlledBy(player.getUUID())"),
                "bank screen rename is not restricted to the current bank controller");
    }
}
