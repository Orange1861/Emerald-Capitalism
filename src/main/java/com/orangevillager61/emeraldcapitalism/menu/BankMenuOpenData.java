package com.orangevillager61.emeraldcapitalism.menu;

import com.orangevillager61.emeraldcapitalism.market.MarketDemandSource;
import com.orangevillager61.emeraldcapitalism.market.MarketItemConfig;
import com.orangevillager61.emeraldcapitalism.market.MarketMetric;
import com.orangevillager61.emeraldcapitalism.network.ProtocolStringLimits;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable server snapshot used to open a bank menu on the client. */
public record BankMenuOpenData(
        BlockPos blockPos,
        String bankName,
        @Nullable UUID villageId,
        String villageName,
        boolean bankIndependent,
        @Nullable UUID controllerId,
        int bankOpinion,
        EntityCounts entityCounts,
        Targets targets,
        Totals totals,
        int chestCount,
        List<BlockPos> chestPositions,
        List<BankMenu.AccountEntry> accounts,
        List<BankMenu.MarketEntry> marketEntries
) {
    public static final int MAX_CHEST_POSITIONS = 64;
    public static final int MAX_ACCOUNT_ENTRIES = 256;
    public static final int MAX_MARKET_ENTRIES = 256;

    public BankMenuOpenData {
        blockPos = Objects.requireNonNull(blockPos, "blockPos").immutable();
        bankName = Objects.requireNonNull(bankName, "bankName");
        villageName = Objects.requireNonNull(villageName, "villageName");
        entityCounts = Objects.requireNonNull(entityCounts, "entityCounts");
        targets = Objects.requireNonNull(targets, "targets");
        totals = Objects.requireNonNull(totals, "totals");
        chestPositions = List.copyOf(chestPositions);
        accounts = List.copyOf(accounts);
        marketEntries = List.copyOf(marketEntries);
    }

    public static BankMenuOpenData empty(BlockPos blockPos) {
        return new BankMenuOpenData(blockPos, "", null, "", true, null, 0,
                new EntityCounts(0, 0, 0, 0), new Targets(0, 0, 0, 0),
                new Totals(0, 0, 0, 0, 0, 0, 0, 0), 0,
                List.of(), List.of(), List.of());
    }

    public static void write(FriendlyByteBuf buf, BankMenuOpenData data) {
        buf.writeBlockPos(data.blockPos());
        buf.writeUtf(clamp(data.bankName(), ProtocolStringLimits.MAX_BANK_NAME_LENGTH),
                ProtocolStringLimits.MAX_BANK_NAME_LENGTH);

        UUID villageId = data.villageId();
        buf.writeBoolean(villageId != null);
        if (villageId != null) {
            buf.writeUUID(villageId);
            buf.writeUtf(clamp(data.villageName(), ProtocolStringLimits.MAX_VILLAGE_NAME_LENGTH),
                    ProtocolStringLimits.MAX_VILLAGE_NAME_LENGTH);
        }

        buf.writeBoolean(data.bankIndependent());
        UUID controllerId = data.controllerId();
        buf.writeBoolean(controllerId != null);
        if (controllerId != null) {
            buf.writeUUID(controllerId);
        }
        buf.writeInt(data.bankOpinion());

        EntityCounts entityCounts = data.entityCounts();
        buf.writeVarInt(entityCounts.depositQueueSize());
        buf.writeVarInt(entityCounts.employeeCount());
        buf.writeVarInt(entityCounts.emeraldGolemCount());
        buf.writeVarInt(entityCounts.expectedEmeraldGolemCount());

        Targets targets = data.targets();
        buf.writeVarInt(targets.pumpkin());
        buf.writeVarInt(targets.bread());
        buf.writeVarInt(targets.plank());
        buf.writeVarInt(targets.coal());

        Totals totals = data.totals();
        buf.writeVarInt(totals.emerald());
        buf.writeVarInt(totals.emeraldOre());
        buf.writeVarInt(totals.pumpkin());
        buf.writeVarInt(totals.wheat());
        buf.writeVarInt(totals.bread());
        buf.writeVarInt(totals.coal());
        buf.writeVarInt(totals.emeraldGreenDye());
        buf.writeVarInt(totals.plank());
        buf.writeVarInt(data.chestCount());

        writePositions(buf, data.chestPositions(), MAX_CHEST_POSITIONS);
        writeAccounts(buf, data.accounts());
        int marketCount = Math.min(data.marketEntries().size(), MAX_MARKET_ENTRIES);
        buf.writeVarInt(marketCount);
        for (int i = 0; i < marketCount; i++) {
            writeMarketEntry(buf, data.marketEntries().get(i));
        }
    }

    public static BankMenuOpenData read(FriendlyByteBuf buf) {
        BlockPos blockPos = buf.readBlockPos();
        String bankName = buf.readUtf(ProtocolStringLimits.MAX_BANK_NAME_LENGTH);
        UUID villageId = buf.readBoolean() ? buf.readUUID() : null;
        String villageName = villageId == null ? ""
                : buf.readUtf(ProtocolStringLimits.MAX_VILLAGE_NAME_LENGTH);
        boolean bankIndependent = buf.readBoolean();
        UUID controllerId = buf.readBoolean() ? buf.readUUID() : null;
        int bankOpinion = buf.readInt();

        EntityCounts entityCounts = new EntityCounts(
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        Targets targets = new Targets(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        Totals totals = new Totals(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        int chestCount = buf.readVarInt();
        List<BlockPos> chestPositions = readPositions(buf, "chest positions", MAX_CHEST_POSITIONS);
        List<BankMenu.AccountEntry> accounts = readAccounts(buf);
        int marketCount = readCount(buf, "market entries", MAX_MARKET_ENTRIES);
        List<BankMenu.MarketEntry> marketEntries = new ArrayList<>(marketCount);
        for (int i = 0; i < marketCount; i++) {
            marketEntries.add(readMarketEntry(buf));
        }
        return new BankMenuOpenData(blockPos, bankName, villageId, villageName,
                bankIndependent, controllerId, bankOpinion, entityCounts, targets, totals,
                chestCount, chestPositions, accounts, marketEntries);
    }

    public static void writeMarketEntry(FriendlyByteBuf buf,
                                        BankMenu.MarketEntry entry) {
        buf.writeUtf(clamp(entry.id(), 128), 128);
        buf.writeUtf(clamp(entry.itemId(), 128), 128);
        buf.writeUtf(clamp(entry.displayName(), 256), 256);
        buf.writeVarInt(Math.max(0, entry.stock()));
        buf.writeVarInt(Math.max(1, entry.population()));
        buf.writeVarInt(Math.max(1, entry.bankTarget()));
        MarketItemConfig config = entry.config();
        buf.writeUtf(clamp(config.id(), 128), 128);
        buf.writeDouble(config.baseRate());
        buf.writeDouble(config.dailyConsumptionRate());
        buf.writeBoolean(config.dailyConsumptionScalesWithPopulation());
        buf.writeDouble(config.lowEdge());
        buf.writeDouble(config.highEdge());
        buf.writeBoolean(config.kScarcity() != null);
        if (config.kScarcity() != null) {
            buf.writeDouble(config.kScarcity());
        }
        buf.writeDouble(config.floorRate());
        buf.writeBoolean(config.kGlut() != null);
        if (config.kGlut() != null) {
            buf.writeDouble(config.kGlut());
        }
        buf.writeDouble(config.ceilingRate());
        buf.writeDouble(config.bidAskSpread());
        buf.writeVarInt(config.minimumTradeSize());
        buf.writeUtf(config.demandSource().name(), 64);
        buf.writeUtf(config.metric().name(), 64);
        buf.writeBoolean(config.hardStopMult() != null);
        if (config.hardStopMult() != null) {
            buf.writeDouble(config.hardStopMult());
        }
        buf.writeVarInt(config.fixedEmeraldOutput());
        buf.writeVarInt(config.fixedEmeraldCost());
    }

    public static BankMenu.MarketEntry readMarketEntry(FriendlyByteBuf buf) {
        String id = buf.readUtf(128);
        String itemId = buf.readUtf(128);
        String displayName = buf.readUtf(256);
        int stock = buf.readVarInt();
        int population = buf.readVarInt();
        int bankTarget = buf.readVarInt();
        MarketItemConfig config = new MarketItemConfig(
                buf.readUtf(128), buf.readDouble(), buf.readDouble(), buf.readBoolean(),
                buf.readDouble(), buf.readDouble(), buf.readBoolean() ? buf.readDouble() : null,
                buf.readDouble(), buf.readBoolean() ? buf.readDouble() : null,
                buf.readDouble(), buf.readDouble(), buf.readVarInt(),
                MarketDemandSource.valueOf(buf.readUtf(64)),
                MarketMetric.valueOf(buf.readUtf(64)),
                buf.readBoolean() ? buf.readDouble() : null,
                buf.readVarInt(), buf.readVarInt());
        return new BankMenu.MarketEntry(id, itemId, displayName, stock, population, bankTarget, config);
    }

    private static void writePositions(FriendlyByteBuf buf,
                                       List<BlockPos> positions, int max) {
        int count = Math.min(positions.size(), max);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeBlockPos(positions.get(i));
        }
    }

    private static List<BlockPos> readPositions(FriendlyByteBuf buf,
                                                String description, int max) {
        int count = readCount(buf, description, max);
        List<BlockPos> positions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            positions.add(buf.readBlockPos());
        }
        return List.copyOf(positions);
    }

    private static void writeAccounts(FriendlyByteBuf buf,
                                      List<BankMenu.AccountEntry> accounts) {
        int count = Math.min(accounts.size(), MAX_ACCOUNT_ENTRIES);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            BankMenu.AccountEntry entry = accounts.get(i);
            buf.writeUtf(clamp(entry.name(), ProtocolStringLimits.MAX_ACCOUNT_NAME_LENGTH),
                    ProtocolStringLimits.MAX_ACCOUNT_NAME_LENGTH);
            buf.writeInt(entry.balance());
            buf.writeInt(entry.queuePosition());
        }
    }

    private static List<BankMenu.AccountEntry> readAccounts(FriendlyByteBuf buf) {
        int count = readCount(buf, "account entries", MAX_ACCOUNT_ENTRIES);
        List<BankMenu.AccountEntry> accounts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            accounts.add(new BankMenu.AccountEntry(
                    buf.readUtf(ProtocolStringLimits.MAX_ACCOUNT_NAME_LENGTH),
                    buf.readInt(), buf.readInt()));
        }
        return List.copyOf(accounts);
    }

    private static int readCount(FriendlyByteBuf buf,
                                 String description, int max) {
        int count = buf.readVarInt();
        if (count < 0 || count > max) {
            throw new IllegalArgumentException("Invalid " + description + " count: " + count);
        }
        return count;
    }

    private static String clamp(String value, int maxLength) {
        return ProtocolStringLimits.clamp(value, maxLength);
    }

    public record EntityCounts(int depositQueueSize, int employeeCount,
                               int emeraldGolemCount, int expectedEmeraldGolemCount) {
    }

    public record Targets(int pumpkin, int bread, int plank, int coal) {
    }

    public record Totals(int emerald, int emeraldOre, int pumpkin, int wheat,
                         int bread, int coal, int emeraldGreenDye, int plank) {
    }
}
