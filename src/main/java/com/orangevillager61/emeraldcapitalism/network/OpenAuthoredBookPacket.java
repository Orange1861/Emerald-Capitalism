package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.event.SteveGraveEvents;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookDefinition;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookStackFactory;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** Requests a server-authoritative open for a mod-authored written book. */
public record OpenAuthoredBookPacket(InteractionHand hand) implements CustomPacketPayload {

    public OpenAuthoredBookPacket {
        Objects.requireNonNull(hand, "hand");
    }

    public static final Type<OpenAuthoredBookPacket> TYPE =
            new Type<>(ModIds.id("open_authored_book"));

    public static final StreamCodec<FriendlyByteBuf, OpenAuthoredBookPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public @NotNull OpenAuthoredBookPacket decode(FriendlyByteBuf buf) {
                    return new OpenAuthoredBookPacket(buf.readEnum(InteractionHand.class));
                }

                @Override
                public void encode(FriendlyByteBuf buf, OpenAuthoredBookPacket packet) {
                    buf.writeEnum(packet.hand());
                }
            };

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenAuthoredBookPacket packet, IPayloadContext context) {
        PacketHandlerUtil.withServerPlayer(context, "open_authored_book", player -> {
            ItemStack stack = player.getItemInHand(packet.hand());
            if (!LibraryBookStackFactory.isAuthoredBook(stack)) {
                return;
            }

            LibraryBookDefinition definition = LibraryBookStackFactory.definitionFor(stack).orElse(null);
            if (definition != null && definition.type().isDynamic()) {
                SteveGraveEvents.ensureTargetResolved(player.serverLevel());
                if (!LibraryBookStackFactory.refreshWorldData(definition, stack, player.serverLevel())) {
                    return;
                }
                syncHeldStack(player, packet.hand(), stack);
            }

            // This sends the vanilla open-book packet after the refreshed stack
            // has been sent to the client, so BookViewScreen reads current data.
            player.openItemGui(stack, packet.hand());
        });
    }

    /** Sends the held stack before vanilla asks the client to open its book screen. */
    public static void syncHeldStack(ServerPlayer player, InteractionHand hand, ItemStack stack) {
        int inventorySlot = hand == InteractionHand.MAIN_HAND
                ? player.getInventory().selected : Inventory.SLOT_OFFHAND;
        player.connection.send(new ClientboundContainerSetSlotPacket(
                -2, // ClientboundContainerSetSlotPacket's player-inventory container id.
                0,
                inventorySlot,
                stack));
    }
}
