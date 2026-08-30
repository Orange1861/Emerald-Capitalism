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

import java.util.ArrayList;
import java.util.List;

/** Server refresh for market stock after a committed bank trade. */
public record MarketDataPacket(BlockPos bankPos, List<BankMenu.MarketEntry> entries, int bankOpinion)
        implements CustomPacketPayload {
    public static final Type<MarketDataPacket> TYPE = new Type<>(ModIds.id("market_data"));

    public static final StreamCodec<FriendlyByteBuf, MarketDataPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public @NotNull MarketDataPacket decode(FriendlyByteBuf buf) {
                    BlockPos pos = buf.readBlockPos();
                    int count = buf.readVarInt();
                    if (count < 0 || count > BankMenuOpenData.MAX_MARKET_ENTRIES) {
                        throw new IllegalArgumentException("Invalid market update size: " + count);
                    }
                    List<BankMenu.MarketEntry> entries = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        entries.add(BankMenuOpenData.readMarketEntry(buf));
                    }
                    return new MarketDataPacket(pos, entries, buf.readInt());
                }

                @Override
                public void encode(FriendlyByteBuf buf, MarketDataPacket packet) {
                    buf.writeBlockPos(packet.bankPos());
                    buf.writeVarInt(Math.min(packet.entries().size(), BankMenuOpenData.MAX_MARKET_ENTRIES));
                    for (int i = 0; i < packet.entries().size() && i < BankMenuOpenData.MAX_MARKET_ENTRIES; i++) {
                        BankMenuOpenData.writeMarketEntry(buf, packet.entries().get(i));
                    }
                    buf.writeInt(packet.bankOpinion());
                }
            };

    public MarketDataPacket(BlockPos bankPos, List<BankMenu.MarketEntry> entries) {
        this(bankPos, entries, 0);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MarketDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            MarketDataClientCache.update(packet);
            if (context.player().containerMenu instanceof BankMenu menu
                    && menu.getBlockPos().equals(packet.bankPos())) {
                menu.applyMarketData(packet.entries(), packet.bankOpinion());
            }
        });
    }
}
