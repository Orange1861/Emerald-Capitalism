package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.menu.BankMenu;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.world.village.VillageGovernance;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/** Client request to make a bank independent or controlled by the sender. */
public record SetBankControlPacket(BlockPos bankPos, boolean independent)
        implements CustomPacketPayload {

    public static final Type<SetBankControlPacket> TYPE =
            new Type<>(ModIds.id("set_bank_control"));

    public static final StreamCodec<FriendlyByteBuf, SetBankControlPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public @NotNull SetBankControlPacket decode(FriendlyByteBuf buf) {
                    return new SetBankControlPacket(buf.readBlockPos(), buf.readBoolean());
                }

                @Override
                public void encode(FriendlyByteBuf buf, SetBankControlPacket packet) {
                    buf.writeBlockPos(packet.bankPos());
                    buf.writeBoolean(packet.independent());
                }
            };

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetBankControlPacket packet, IPayloadContext context) {
        PacketHandlerUtil.withServerPlayer(context, "set_bank_control", player -> {
            ServerLevel level = PacketHandlerUtil.serverLevel(player);
            if (player.isSpectator() || !(player.containerMenu instanceof BankMenu menu)
                    || !menu.getBlockPos().equals(packet.bankPos())
                    || player.distanceToSqr(packet.bankPos().getX() + 0.5,
                    packet.bankPos().getY() + 0.5, packet.bankPos().getZ() + 0.5) > 64.0
                    || !level.getBlockState(packet.bankPos()).is(ECAPBlocks.BANK.get())
                    || !(level.getBlockEntity(packet.bankPos()) instanceof BankBlockEntity bank)) {
                return;
            }

            if (packet.independent()) {
                if (!bank.isControlledBy(player.getUUID())) {
                    player.sendSystemMessage(Component.literal(
                            "[ECAP] Only the bank controller can release bank control."));
                    return;
                }
                bank.setController(null);
            } else {
                if (!bank.isBankIndependent()) {
                    player.sendSystemMessage(Component.literal(
                            "[ECAP] This bank is already controlled by another player."));
                    return;
                }
                if (!bank.meetsTakeoverRequirements(level)) {
                    player.sendSystemMessage(Component.literal(
                            "[ECAP] Both vault golems and all three bank employee jobs must be vacant."));
                    return;
                }
                if (!bank.canPlayerTakeControl(level, player.getUUID())) {
                    player.sendSystemMessage(Component.literal(
                            "[ECAP] Only the player who killed the last bank employee may claim this bank right now."));
                    return;
                }
                bank.setController(player.getUUID());
            }

            if (bank.getVillageId() != null) {
                VillageRecord village = VillageRegistryData.get(level).getVillages()
                        .get(bank.getVillageId());
                if (village != null) {
                    VillageGovernance.refresh(level, village);
                    VillageRegistryData.get(level).setDirty();
                    VillagePOIDataCache.invalidateVillage(village.getVillageId());
                }
            }
            PacketDistributor.sendToPlayer(player,
                    new BankControlDataPacket(packet.bankPos(), bank.getControlSettings()));
            player.sendSystemMessage(Component.literal(packet.independent()
                    ? "[ECAP] Bank is now independent."
                    : "[ECAP] You now control this bank."));
        });
    }
}
