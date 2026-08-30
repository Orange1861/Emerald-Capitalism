package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.menu.BankMenu;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Client-received market snapshots kept free of client-only class references. */
public final class MarketDataClientCache {
    private static final Map<BlockPos, List<BankMenu.MarketEntry>> DATA = new ConcurrentHashMap<>();

    private MarketDataClientCache() {
    }

    static void update(MarketDataPacket packet) {
        DATA.put(packet.bankPos().immutable(), List.copyOf(packet.entries()));
    }

    public static Optional<List<BankMenu.MarketEntry>> get(BlockPos bankPos) {
        return Optional.ofNullable(DATA.get(bankPos));
    }

    public static void clear() {
        DATA.clear();
    }
}
