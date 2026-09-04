package com.orangevillager61.emeraldcapitalism.menu;

import com.orangevillager61.emeraldcapitalism.market.MarketDemandSource;
import com.orangevillager61.emeraldcapitalism.market.MarketItemConfig;
import com.orangevillager61.emeraldcapitalism.market.MarketMetric;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BankMenuOpenDataCodecTest {

    @Test
    void roundTripPreservesGroupedScalarsNullabilityListsAndMarketConfigs() {
        MarketItemConfig dynamic = config("dynamic", 9.0, 13.0, MarketMetric.DAYS,
                null, 0, 0);
        MarketItemConfig fixedBuy = config("fixed-buy", 1.0, 1.0, MarketMetric.DAYS,
                null, 0, 128);
        MarketItemConfig fixedSell = config("fixed-sell", 1.0, 1.0, MarketMetric.TARGET_RATIO,
                3.0, 4, 0);
        BankMenuOpenData source = new BankMenuOpenData(
                new BlockPos(3, 64, -5), "Central Bank",
                UUID.fromString("11111111-1111-1111-1111-111111111111"), "Central Village",
                false, UUID.fromString("22222222-2222-2222-2222-222222222222"), -17,
                new BankMenuOpenData.EntityCounts(3, 1, 4),
                new BankMenuOpenData.Targets(5, 6, 7, 8),
                new BankMenuOpenData.ControlSettings(true, 12, 7, 9,
                        true, false, true, true, true),
                new BankMenuOpenData.Totals(9, 10, 11, 12, 13, 14, 15, 16), 4,
                List.of(new BlockPos(1, 2, 3), new BlockPos(-4, 5, 6)),
                List.of(new BankMenu.AccountEntry("Alice", 42),
                        new BankMenu.AccountEntry("Bob", -3)),
                List.of(new BankMenu.EmployeeEntry("Alice", "Villager", "Farmer"),
                        new BankMenu.EmployeeEntry("Emerald Golem", "Emerald Golem", "—")),
                List.of(
                        new BankMenu.MarketEntry("dynamic", "minecraft:bread", "Bread", 19,
                                10, 1, dynamic),
                        new BankMenu.MarketEntry("fixed-buy", "minecraft:map", "Map", 1,
                                10, 1, fixedBuy),
                        new BankMenu.MarketEntry("fixed-sell", "minecraft:chest", "Chest", 0,
                                10, 1, fixedSell)));

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        BankMenuOpenData.write(buffer, source);
        BankMenuOpenData decoded = BankMenuOpenData.read(buffer);

        assertEquals(source, decoded);
    }

    @Test
    void roundTripPreservesNullIdsAndEmptyLists() {
        BankMenuOpenData source = new BankMenuOpenData(
                BlockPos.ZERO, "Independent", null, "", true, null, 0,
                new BankMenuOpenData.EntityCounts(0, 0, 0),
                new BankMenuOpenData.Targets(0, 0, 0, 0),
                new BankMenuOpenData.ControlSettings(false, 0, 0, 5,
                        true, true, true, false, false),
                new BankMenuOpenData.Totals(0, 0, 0, 0, 0, 0, 0, 0), 0,
                List.of(), List.of(), List.of(), List.of());

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        BankMenuOpenData.write(buffer, source);

        assertEquals(source, BankMenuOpenData.read(buffer));
    }

    @Test
    void rejectsNegativeOrExcessiveListCountsBeforeAllocation() {
        assertThrows(IllegalArgumentException.class,
                () -> BankMenuOpenData.read(bankBuffer(-1, 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> BankMenuOpenData.read(bankBuffer(0, BankMenuOpenData.MAX_ACCOUNT_ENTRIES + 1, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> BankMenuOpenData.read(employeeBuffer(BankMenuOpenData.MAX_EMPLOYEE_ENTRIES + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> BankMenuOpenData.read(bankBuffer(0, 0, -1)));
        assertThrows(IllegalArgumentException.class,
                () -> BankMenuOpenData.read(bankBuffer(0, 0, BankMenuOpenData.MAX_MARKET_ENTRIES + 1)));
    }

    @Test
    void rejectsMalformedMarketEnumsAndTruncatedPayloads() {
        assertThrows(IllegalArgumentException.class,
                () -> BankMenuOpenData.read(marketBuffer("NOT_A_DEMAND_SOURCE", "DAYS")));
        assertThrows(IllegalArgumentException.class,
                () -> BankMenuOpenData.read(marketBuffer("POPULATION", "NOT_A_METRIC")));
        assertThrows(IndexOutOfBoundsException.class,
                () -> BankMenuOpenData.read(new FriendlyByteBuf(Unpooled.buffer())));
    }

    private static MarketItemConfig config(String id, double lowEdge, double highEdge,
                                           MarketMetric metric, Double hardStop,
                                           int fixedOutput, int fixedCost) {
        return new MarketItemConfig(id, 2.0, 1.0, false, lowEdge, highEdge,
                0.1, 1.0, 0.1, 4.0, 0.0, 1,
                MarketDemandSource.POPULATION, metric, hardStop, fixedOutput, fixedCost);
    }

    private static FriendlyByteBuf bankBuffer(int chestCount, int accountCount, int marketCount) {
        FriendlyByteBuf buffer = bankPrefix();
        buffer.writeVarInt(chestCount);
        if (chestCount == 0) {
            buffer.writeVarInt(accountCount);
            if (accountCount == 0) {
                buffer.writeVarInt(0);
                buffer.writeVarInt(marketCount);
            }
        }
        return buffer;
    }

    private static FriendlyByteBuf marketBuffer(String demandSource, String metric) {
        FriendlyByteBuf buffer = bankPrefix();
        buffer.writeVarInt(0);
        buffer.writeVarInt(0);
        buffer.writeVarInt(0);
        buffer.writeVarInt(1);
        buffer.writeUtf("entry", 128);
        buffer.writeUtf("minecraft:bread", 128);
        buffer.writeUtf("Bread", 256);
        buffer.writeVarInt(1);
        buffer.writeVarInt(10);
        buffer.writeVarInt(1);
        buffer.writeUtf("bread", 128);
        buffer.writeDouble(2.0);
        buffer.writeDouble(1.0);
        buffer.writeBoolean(false);
        buffer.writeDouble(1.0);
        buffer.writeDouble(2.0);
        buffer.writeBoolean(false);
        buffer.writeDouble(1.0);
        buffer.writeBoolean(false);
        buffer.writeDouble(4.0);
        buffer.writeDouble(0.0);
        buffer.writeVarInt(1);
        buffer.writeUtf(demandSource, 64);
        buffer.writeUtf(metric, 64);
        buffer.writeBoolean(false);
        buffer.writeVarInt(0);
        buffer.writeVarInt(0);
        return buffer;
    }

    private static FriendlyByteBuf employeeBuffer(int employeeCount) {
        FriendlyByteBuf buffer = bankPrefix();
        buffer.writeVarInt(0);
        buffer.writeVarInt(0);
        buffer.writeVarInt(employeeCount);
        return buffer;
    }

    private static FriendlyByteBuf bankPrefix() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeBlockPos(BlockPos.ZERO);
        buffer.writeUtf("", 64);
        buffer.writeBoolean(false);
        buffer.writeBoolean(true);
        buffer.writeBoolean(false);
        buffer.writeInt(0);
        for (int i = 0; i < 3 + 4; i++) {
            buffer.writeVarInt(0);
        }
        buffer.writeBoolean(false);
        buffer.writeVarInt(0);
        buffer.writeVarInt(0);
        buffer.writeVarInt(5);
        buffer.writeBoolean(true);
        buffer.writeBoolean(true);
        buffer.writeBoolean(true);
        buffer.writeBoolean(false);
        buffer.writeBoolean(false);
        for (int i = 0; i < 8; i++) {
            buffer.writeVarInt(0);
        }
        buffer.writeVarInt(0);
        return buffer;
    }
}
