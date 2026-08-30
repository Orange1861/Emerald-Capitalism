package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.market.MarketItem;
import com.orangevillager61.emeraldcapitalism.market.MarketRegistry;
import com.orangevillager61.emeraldcapitalism.market.MarketTradeService;
import com.orangevillager61.emeraldcapitalism.market.TradeSide;
import com.orangevillager61.emeraldcapitalism.menu.BankMenu;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.world.bank.BankReputationData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/** Client request to execute one validated marginally-priced market trade. */
public record MarketTradePacket(BlockPos bankPos, String itemId, int quantity, boolean buy,
                                boolean donation)
        implements CustomPacketPayload {
    public static final Type<MarketTradePacket> TYPE = new Type<>(ModIds.id("market_trade"));
    public static final StreamCodec<FriendlyByteBuf, MarketTradePacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public @NotNull MarketTradePacket decode(FriendlyByteBuf buf) {
                    return new MarketTradePacket(buf.readBlockPos(), buf.readUtf(128),
                            buf.readVarInt(), buf.readBoolean(), buf.readBoolean());
                }

                @Override
                public void encode(FriendlyByteBuf buf, MarketTradePacket packet) {
                    buf.writeBlockPos(packet.bankPos());
                    buf.writeUtf(packet.itemId(), 128);
                    buf.writeVarInt(packet.quantity());
                    buf.writeBoolean(packet.buy());
                    buf.writeBoolean(packet.donation());
                }
            };

    public MarketTradePacket(BlockPos bankPos, String itemId, int quantity, boolean buy) {
        this(bankPos, itemId, quantity, buy, false);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MarketTradePacket packet, IPayloadContext context) {
        PacketHandlerUtil.withServerPlayer(context, "market_trade", player -> {
            if (player.isSpectator() || !(player.containerMenu instanceof BankMenu menu)
                    || !menu.getBlockPos().equals(packet.bankPos())
                    || player.distanceToSqr(packet.bankPos().getX() + 0.5,
                    packet.bankPos().getY() + 0.5, packet.bankPos().getZ() + 0.5) > 64.0) {
                return;
            }
            ServerLevel level = PacketHandlerUtil.serverLevel(player);
            if (!level.getBlockState(packet.bankPos()).is(ECAPBlocks.BANK.get())
                    || !(level.getBlockEntity(packet.bankPos()) instanceof BankBlockEntity bank)) {
                return;
            }
            MarketItem item = MarketRegistry.get(packet.itemId()).orElse(null);
            if (item == null) {
                return;
            }
            MarketTradeService.Result result = MarketTradeService.execute(
                    player, bank, item, packet.quantity(), packet.buy() ? TradeSide.BUY : TradeSide.SELL,
                    packet.donation());
            if (!result.success()) {
                player.sendSystemMessage(Component.literal("[ECAP] " + result.message()));
                return;
            }
            PacketDistributor.sendToPlayer(player,
                    new MarketDataPacket(packet.bankPos(), bank.buildMarketEntries(),
                            BankReputationData.get(level).getReputation(player.getUUID())));
        });
    }
}
