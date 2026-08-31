package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.menu.BankMenu;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * Client → Server: rename a bank block entity.
 */
public record RenameBankPacket(BlockPos bankPos, String newName) implements CustomPacketPayload {

    /** Maximum allowed bank name length (characters). */
    public static final int MAX_NAME_LENGTH = ProtocolStringLimits.MAX_BANK_NAME_LENGTH;

    public static final Type<RenameBankPacket> TYPE =
            new Type<>(ModIds.id("rename_bank"));

    public static final StreamCodec<FriendlyByteBuf, RenameBankPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public @NotNull RenameBankPacket decode(FriendlyByteBuf buf) {
                    BlockPos pos = buf.readBlockPos();
                    String name = buf.readUtf(MAX_NAME_LENGTH);
                    return new RenameBankPacket(pos, name);
                }

                @Override
                public void encode(FriendlyByteBuf buf, RenameBankPacket packet) {
                    buf.writeBlockPos(packet.bankPos());
                    buf.writeUtf(ProtocolStringLimits.clamp(packet.newName(), MAX_NAME_LENGTH), MAX_NAME_LENGTH);
                }
            };

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RenameBankPacket packet, IPayloadContext context) {
        PacketHandlerUtil.withServerPlayer(context, "rename_bank", player -> {
            ServerLevel level = PacketHandlerUtil.serverLevel(player);

            if (player.isSpectator()) {
                player.sendSystemMessage(Component.literal("[ECAP] Spectators cannot rename banks."));
                return;
            }

            if (!(player.containerMenu instanceof BankMenu menu)) {
                return;
            }

            // Verify the player is within interaction range of the bank
            BlockPos pos = packet.bankPos();
            if (!menu.getBlockPos().equals(pos)) {
                return;
            }
            double distSq = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (distSq > 64.0) {
                player.sendSystemMessage(Component.literal("[ECAP] Too far from the bank to rename it."));
                return;
            }

            if (!level.getBlockState(pos).is(ECAPBlocks.BANK.get())
                    || !(level.getBlockEntity(pos) instanceof BankBlockEntity bank)) {
                return;
            }

            if (!bank.isControlledBy(player.getUUID())) {
                player.sendSystemMessage(Component.literal(
                        "[ECAP] Only the player controlling this bank can rename it."));
                return;
            }

            String name = packet.newName() == null ? "" : packet.newName().trim();
            if (name.isEmpty()) {
                player.sendSystemMessage(Component.literal("[ECAP] Bank name cannot be empty."));
                return;
            }
            if (name.length() > MAX_NAME_LENGTH) {
                name = name.substring(0, MAX_NAME_LENGTH);
            }

            bank.setBankName(name);
            EmeraldCapitalism.LOGGER.info("[ECAP] Bank at {} renamed to '{}' by {}",
                    pos, name, player.getName().getString());
        });
    }
}
