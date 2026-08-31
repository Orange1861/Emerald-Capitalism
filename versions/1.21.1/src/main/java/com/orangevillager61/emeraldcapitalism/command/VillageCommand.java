package com.orangevillager61.emeraldcapitalism.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.VillageManagerBlockEntity;
import com.orangevillager61.emeraldcapitalism.event.VillageRegistryEvents;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import com.orangevillager61.emeraldcapitalism.world.bank.BankReputationData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageManagerPlacement;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryManager;
import com.orangevillager61.emeraldcapitalism.world.village.VillagerPOIRecord;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.Map;
import java.util.Locale;
import java.util.UUID;

/**
 * Operator utilities for managing the village registry.
 * <p>
 * Usage:
 * <ul>
 *   <li>{@code /ecap village list}: list all registered villages</li>
 *   <li>{@code /ecap village info <id>}: detailed POI data for a village</li>
 *   <li>{@code /ecap village rescan <id>}: force re-scan of a village</li>
 *   <li>{@code /ecap village create}: create a village at the nearest bell</li>
 *   <li>{@code /ecap village link <id>}: link a nearby manager block to a village</li>
 *   <li>{@code /ecap village rename <id> <name>}: rename a village</li>
 *   <li>{@code /ecap village reset}: clear all village data</li>
 *   <li>{@code /ecap reputation <amount> nearestbank}: adjust bank reputation</li>
 *   <li>{@code /ecap reputation <amount> nearestvillage}: adjust village reputation</li>
 * </ul>
 */
public class VillageCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("ecap")
                        .requires(source -> source.hasPermission(Config.villageCommandPermissionLevel))
                        .then(Commands.literal("village")
                                .then(Commands.literal("list")
                                        .executes(ctx -> listVillages(ctx.getSource())))
                                .then(Commands.literal("info")
                                        .then(Commands.argument("villageId", StringArgumentType.string())
                                                .executes(ctx -> villageInfo(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "villageId")))))
                                .then(Commands.literal("rescan")
                                        .then(Commands.argument("villageId", StringArgumentType.string())
                                                .executes(ctx -> rescanVillage(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "villageId")))))
                                .then(Commands.literal("create")
                                        .executes(ctx -> createVillage(ctx.getSource())))
                                .then(Commands.literal("link")
                                        .then(Commands.argument("villageId", StringArgumentType.string())
                                                .executes(ctx -> linkManagerBlock(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "villageId")))))
                                .then(Commands.literal("rename")
                                        .then(Commands.argument("villageId", StringArgumentType.string())
                                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                                        .executes(ctx -> renameVillage(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "villageId"),
                                                                StringArgumentType.getString(ctx, "name"))))))
                                .then(Commands.literal("reset")
                                        .executes(ctx -> resetVillages(ctx.getSource())))
                        )
                        .then(Commands.literal("reputation")
                                .then(Commands.argument("amount", IntegerArgumentType.integer())
                                        .then(Commands.literal("nearestbank")
                                                .executes(ctx -> adjustNearestBankReputation(
                                                        ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "amount"))))
                                        .then(Commands.literal("nearestvillage")
                                                .executes(ctx -> adjustNearestVillageReputation(
                                                        ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "amount"))))))
                        .then(Commands.literal("performance")
                                .executes(ctx -> showPerformance(ctx.getSource())))
        );
    }

    private static int showPerformance(CommandSourceStack source) {
        Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> snapshots =
                PerformanceTimingCounters.snapshot();
        source.sendSuccess(() -> Component.literal("=== ECAP timing counters ==="), false);
        snapshots.forEach((operation, snapshot) -> source.sendSuccess(() -> Component.literal(
                String.format(Locale.ROOT, "  %s: calls=%d avg=%.3f ms max=%.3f ms",
                        operation.name().toLowerCase(Locale.ROOT), snapshot.calls(),
                        snapshot.averageMillis(), snapshot.maximumMillis())), false));
        return snapshots.size();
    }

    private static int adjustNearestBankReputation(CommandSourceStack source, int amount) {
        ServerPlayer player = getCommandPlayer(source);
        if (player == null) {
            return 0;
        }

        ServerLevel level = player.serverLevel();
        BankBlockEntity bank = BankBlockEntity.findNearestLoadedBank(level, player.blockPosition());
        if (bank == null) {
            source.sendFailure(Component.literal("No loaded bank could be found."));
            return 0;
        }

        BankReputationData reputation = BankReputationData.get(level);
        int before = reputation.getReputation(player.getUUID());
        int after = reputation.adjustReputation(player.getUUID(), amount);
        BlockPos bankPos = bank.getBlockPos();
        source.sendSuccess(() -> Component.literal(
                "Changed bank reputation by " + amount + " with the nearest bank at ("
                        + bankPos.getX() + ", " + bankPos.getY() + ", " + bankPos.getZ()
                        + "): " + before + " -> " + after
        ), true);
        return 1;
    }

    private static int adjustNearestVillageReputation(CommandSourceStack source, int amount) {
        ServerPlayer player = getCommandPlayer(source);
        if (player == null) {
            return 0;
        }

        ServerLevel level = player.serverLevel();
        VillageRegistryData data = getData(level);
        VillageRecord village = data.getNearestVillage(player.blockPosition());
        if (village == null) {
            source.sendFailure(Component.literal("No registered village could be found."));
            return 0;
        }

        int before = village.getOpinionModifier(player.getUUID());
        int after = village.adjustOpinionModifier(player.getUUID(), amount);
        data.setDirty();
        BlockPos bellPos = village.getBellPosition();
        source.sendSuccess(() -> Component.literal(
                "Changed village reputation by " + amount + " with " + village.getName()
                        + " at (" + bellPos.getX() + ", " + bellPos.getY() + ", " + bellPos.getZ()
                        + "): " + before + " -> " + after
        ), true);
        return 1;
    }

    private static ServerPlayer getCommandPlayer(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return player;
        }
        source.sendFailure(Component.literal("This command must be run by a player."));
        return null;
    }

    // Helpers

    private static VillageRegistryData getData(ServerLevel level) {
        ServerLevel overworld = level.getServer().getLevel(Level.OVERWORLD);
        return VillageRegistryData.get(overworld != null ? overworld : level);
    }

    @javax.annotation.Nullable
    private static UUID parseUUID(CommandSourceStack source, String input) {
        // Accept full UUID or short prefix (first 8 chars)
        if (input.length() == 8) {
            VillageRegistryData data = getData(source.getLevel());
            for (UUID id : data.getVillages().keySet()) {
                if (id.toString().startsWith(input)) {
                    return id;
                }
            }
            source.sendFailure(Component.literal("No village found with prefix: " + input));
            return null;
        }
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Invalid UUID: " + input));
            return null;
        }
    }

    // list

    private static int listVillages(CommandSourceStack source) {
        VillageRegistryData data = getData(source.getLevel());
        Map<UUID, VillageRecord> villages = data.getVillages();

        if (villages.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No villages registered."), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("=== Registered Villages (" + villages.size() + ") ==="), false);
        for (VillageRecord village : villages.values()) {
            BlockPos bell = village.getBellPosition();
            String shortId = village.getVillageId().toString().substring(0, 8);
            String villageName = village.getName();
            int members = village.getMembers().size();
            source.sendSuccess(() -> Component.literal(
                    "  " + shortId + " | " + villageName + " | bell=(" + bell.getX() + ", " + bell.getY() + ", " + bell.getZ()
                            + ") | " + members + " villager" + (members != 1 ? "s" : "")
            ), false);
        }

        int pending = data.getPendingManagerPlacements().size();
        if (pending > 0) {
            source.sendSuccess(() -> Component.literal(
                    "Pending manager placements: " + pending
            ), false);
        }

        return villages.size();
    }

    // info

    private static int villageInfo(CommandSourceStack source, String idStr) {
        UUID villageId = parseUUID(source, idStr);
        if (villageId == null) return 0;

        VillageRegistryData data = getData(source.getLevel());
        VillageRecord village = data.getVillages().get(villageId);
        if (village == null) {
            source.sendFailure(Component.literal("Village not found: " + villageId));
            return 0;
        }

        BlockPos bell = village.getBellPosition();
        AABB bb = village.getBoundingBox();

        String villageName = village.getName();
        source.sendSuccess(() -> Component.literal("=== " + villageName + " (" + villageId.toString().substring(0, 8) + ") ==="), false);
        source.sendSuccess(() -> Component.literal("  Full ID: " + villageId), false);
        source.sendSuccess(() -> Component.literal("  Name: " + villageName), false);
        source.sendSuccess(() -> Component.literal("  Bell: (" + bell.getX() + ", " + bell.getY() + ", " + bell.getZ() + ")"), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "  Bounds: (%.0f, %.0f, %.0f) to (%.0f, %.0f, %.0f)",
                bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ
        )), false);
        source.sendSuccess(() -> Component.literal("  Cache initialized: " + village.isCacheInitialized()), false);

        Map<UUID, VillagerPOIRecord> members = village.getMembers();
        source.sendSuccess(() -> Component.literal("  Villagers (" + members.size() + "):"), false);

        if (members.isEmpty()) {
            source.sendSuccess(() -> Component.literal("    (none)"), false);
        } else {
            for (VillagerPOIRecord member : members.values()) {
                String bed = member.getBedPos() != null
                        ? "(" + member.getBedPos().getX() + "," + member.getBedPos().getY() + "," + member.getBedPos().getZ() + ")"
                        : "none";
                String job = member.getJobSitePos() != null
                        ? "(" + member.getJobSitePos().getX() + "," + member.getJobSitePos().getY() + "," + member.getJobSitePos().getZ() + ")"
                        : "none";
                source.sendSuccess(() -> Component.literal(
                        "    " + member.getDisplayName()
                                + " [" + member.getProfession() + "]"
                                + " " + member.getStatus()
                                + " bed=" + bed
                                + " job=" + job
                ), false);
            }
        }

        return 1;
    }

    // rescan

    private static int rescanVillage(CommandSourceStack source, String idStr) {
        UUID villageId = parseUUID(source, idStr);
        if (villageId == null) return 0;

        ServerLevel level = source.getLevel();
        VillageRegistryData data = getData(level);
        VillageRecord village = data.getVillages().get(villageId);
        if (village == null) {
            source.sendFailure(Component.literal("Village not found: " + villageId));
            return 0;
        }

        ServerLevel overworld = level.getServer().getLevel(Level.OVERWORLD);
        ServerLevel targetLevel = overworld != null ? overworld : level;
        VillageRegistryManager manager = VillageRegistryEvents.getManager(targetLevel);
        manager.requestFullScan(village);

        source.sendSuccess(() -> Component.literal(
                "Queued rescan for village " + villageId.toString().substring(0, 8)
        ), true);
        return 1;
    }

    // create

    private static int createVillage(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos playerPos = BlockPos.containing(source.getPosition());

        BlockPos bellPos = VillageManagerPlacement.findNearestBell(level, playerPos, 64);
        if (bellPos == null) {
            source.sendFailure(Component.literal("No bell found within 64 blocks."));
            return 0;
        }

        VillageRegistryData data = getData(level);
        UUID villageId = UUID.randomUUID();
        AABB bounds = new AABB(bellPos).inflate(128, 48, 128);
        data.getOrCreateVillage(villageId, bellPos, bounds);
        data.setDirty();

        source.sendSuccess(() -> Component.literal(
                "Created village " + villageId.toString().substring(0, 8)
                        + " at bell=(" + bellPos.getX() + ", " + bellPos.getY() + ", " + bellPos.getZ() + ")"
        ), true);
        return 1;
    }

    // link

    private static int linkManagerBlock(CommandSourceStack source, String idStr) {
        UUID villageId = parseUUID(source, idStr);
        if (villageId == null) return 0;

        ServerLevel level = source.getLevel();
        BlockPos playerPos = BlockPos.containing(source.getPosition());

        for (BlockPos pos : BlockPos.betweenClosed(playerPos.offset(-5, -5, -5), playerPos.offset(5, 5, 5))) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof VillageManagerBlockEntity villageManager) {
                villageManager.setVillageId(villageId);
                BlockPos foundPos = pos.immutable();
                source.sendSuccess(() -> Component.literal(
                        "Linked Village Manager at (" + foundPos.getX() + ", " + foundPos.getY() + ", " + foundPos.getZ()
                                + ") to village " + villageId.toString().substring(0, 8)
                ), true);
                return 1;
            }
        }

        source.sendFailure(Component.literal("No Village Manager block found within 5 blocks."));
        return 0;
    }

    // rename

    private static int renameVillage(CommandSourceStack source, String idStr, String newName) {
        UUID villageId = parseUUID(source, idStr);
        if (villageId == null) return 0;

        VillageRegistryData data = getData(source.getLevel());
        VillageRecord village = data.getVillages().get(villageId);
        if (village == null) {
            source.sendFailure(Component.literal("Village not found: " + villageId));
            return 0;
        }

        String trimmed = newName.trim();
        if (trimmed.isEmpty()) {
            source.sendFailure(Component.literal("Village name cannot be empty."));
            return 0;
        }

        int maxLen = Config.maxVillageNameLength;
        if (trimmed.length() > maxLen) {
            trimmed = trimmed.substring(0, maxLen);
        }

        String oldName = village.getName();
        data.renameVillage(source.getLevel(), village, trimmed);

        String finalName = trimmed;
        source.sendSuccess(() -> Component.literal(
                "Renamed village from \"" + oldName + "\" to \"" + finalName + "\""
        ), true);
        return 1;
    }

    // reset

    private static int resetVillages(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        ServerLevel overworld = level.getServer().getLevel(Level.OVERWORLD);
        ServerLevel targetLevel = overworld != null ? overworld : level;

        VillageRegistryData data = VillageRegistryData.get(targetLevel);
        int count = data.getVillages().size();
        int pending = data.getPendingManagerPlacements().size();

        data.clearAll();

        source.sendSuccess(() -> Component.literal(
                "Cleared all village data: " + count + " village" + (count != 1 ? "s" : "")
                        + " and " + pending + " pending placement" + (pending != 1 ? "s" : "") + " removed."
        ), true);
        return 1;
    }
}
