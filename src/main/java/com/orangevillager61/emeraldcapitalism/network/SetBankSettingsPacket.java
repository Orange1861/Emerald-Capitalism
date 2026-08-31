package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.menu.BankMenu;
import com.orangevillager61.emeraldcapitalism.menu.BankMenuOpenData;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** Client request to update the settings of a bank controlled by the sender. */
public record SetBankSettingsPacket(BlockPos bankPos, BankMenuOpenData.ControlSettings settings)
        implements CustomPacketPayload {

    public SetBankSettingsPacket {
        bankPos = Objects.requireNonNull(bankPos, "bankPos").immutable();
        settings = Objects.requireNonNull(settings, "settings");
    }

    public static final Type<SetBankSettingsPacket> TYPE =
            new Type<>(ModIds.id("set_bank_settings"));

    public static final StreamCodec<FriendlyByteBuf, SetBankSettingsPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public @NotNull SetBankSettingsPacket decode(FriendlyByteBuf buf) {
                    return new SetBankSettingsPacket(buf.readBlockPos(),
                            BankMenuOpenData.readControlSettings(buf));
                }

                @Override
                public void encode(FriendlyByteBuf buf, SetBankSettingsPacket packet) {
                    buf.writeBlockPos(packet.bankPos());
                    BankMenuOpenData.writeControlSettings(buf, packet.settings());
                }
            };

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetBankSettingsPacket packet, IPayloadContext context) {
        PacketHandlerUtil.withServerPlayer(context, "set_bank_settings", player -> {
            ServerLevel level = PacketHandlerUtil.serverLevel(player);
            if (player.isSpectator() || !(player.containerMenu instanceof BankMenu menu)
                    || !menu.getBlockPos().equals(packet.bankPos())
                    || player.distanceToSqr(packet.bankPos().getX() + 0.5,
                    packet.bankPos().getY() + 0.5, packet.bankPos().getZ() + 0.5) > 64.0
                    || !level.getBlockState(packet.bankPos()).is(ECAPBlocks.BANK.get())
                    || !(level.getBlockEntity(packet.bankPos()) instanceof BankBlockEntity bank)) {
                return;
            }

            if (!bank.isControlledBy(player.getUUID())) {
                player.sendSystemMessage(Component.literal(
                        "[ECAP] Only the player controlling this bank can change its settings."));
                return;
            }

            bank.setControlSettings(packet.settings());
            PacketDistributor.sendToPlayer(player,
                    new BankControlDataPacket(packet.bankPos(), bank.isBankIndependent(),
                            bank.getControllerId(), bank.getControlSettings()));
        });
    }
}
