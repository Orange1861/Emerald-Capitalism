package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.menu.BankMenu;
import com.orangevillager61.emeraldcapitalism.menu.BankMenuOpenData;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/** Server refresh for control settings while the bank menu is open. */
public record BankControlDataPacket(BlockPos bankPos, boolean bankIndependent,
                                    @Nullable UUID controllerId,
                                    BankMenuOpenData.ControlSettings settings)
        implements CustomPacketPayload {

    public BankControlDataPacket {
        bankPos = Objects.requireNonNull(bankPos, "bankPos").immutable();
        settings = Objects.requireNonNull(settings, "settings");
        if (bankIndependent && controllerId != null) {
            throw new IllegalArgumentException("Independent banks cannot have a controller");
        }
    }

    public static final Type<BankControlDataPacket> TYPE =
            new Type<>(ModIds.id("bank_control_data"));

    public static final StreamCodec<FriendlyByteBuf, BankControlDataPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public @NotNull BankControlDataPacket decode(FriendlyByteBuf buf) {
                    BlockPos bankPos = buf.readBlockPos();
                    boolean bankIndependent = buf.readBoolean();
                    UUID controllerId = buf.readBoolean() ? buf.readUUID() : null;
                    return new BankControlDataPacket(bankPos, bankIndependent, controllerId,
                            BankMenuOpenData.readControlSettings(buf));
                }

                @Override
                public void encode(FriendlyByteBuf buf, BankControlDataPacket packet) {
                    buf.writeBlockPos(packet.bankPos());
                    buf.writeBoolean(packet.bankIndependent());
                    buf.writeBoolean(packet.controllerId() != null);
                    if (packet.controllerId() != null) {
                        buf.writeUUID(packet.controllerId());
                    }
                    BankMenuOpenData.writeControlSettings(buf, packet.settings());
                }
            };

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BankControlDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player().containerMenu instanceof BankMenu menu
                    && menu.getBlockPos().equals(packet.bankPos())) {
                menu.applyBankControlState(packet.bankIndependent(), packet.controllerId(), packet.settings());
            }
        });
    }
}
