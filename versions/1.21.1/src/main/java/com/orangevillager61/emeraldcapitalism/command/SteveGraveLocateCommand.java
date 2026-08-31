package com.orangevillager61.emeraldcapitalism.command;

import com.mojang.brigadier.CommandDispatcher;
import com.orangevillager61.emeraldcapitalism.world.structure.SteveGraveSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Displays the persisted Steve grave location without scanning the world. */
public final class SteveGraveLocateCommand {
    private SteveGraveLocateCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("locate")
                .then(Commands.literal("ecap")
                        .then(Commands.literal("structures")
                                .then(Commands.literal("the_grave")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(context -> locate(context.getSource()))))));

        dispatcher.register(Commands.literal("ecap")
                .then(Commands.literal("locate")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("the_grave")
                                .executes(context -> locate(context.getSource())))));
    }

    private static int locate(CommandSourceStack source) {
        ServerLevel overworld = source.getServer().overworld();
        SteveGraveSavedData data = SteveGraveSavedData.get(overworld);
        BlockPos target = data.target();
        if (target == null) {
            source.sendFailure(Component.literal(data.searchFailed()
                    ? "Steve's grave could not be resolved in this world."
                    : "Steve's grave location has not been resolved yet."));
            return 0;
        }

        BlockPos from = source.getLevel().dimension() == Level.OVERWORLD
                ? BlockPos.containing(source.getPosition())
                : overworld.getSharedSpawnPos();
        int distance = horizontalDistance(from, target);
        Component coordinates = ComponentUtils.wrapInSquareBrackets(
                Component.translatable("chat.coordinates", target.getX(), "~", target.getZ()))
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(
                                ClickEvent.Action.SUGGEST_COMMAND,
                                "/tp @s " + target.getX() + " ~ " + target.getZ()))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("chat.coordinates.tooltip"))));

        source.sendSuccess(() -> Component.literal("The grave is at ")
                .append(coordinates)
                .append(Component.literal(" (" + distance + " blocks away).")), false);
        return distance;
    }

    private static int horizontalDistance(BlockPos from, BlockPos to) {
        long dx = (long) to.getX() - from.getX();
        long dz = (long) to.getZ() - from.getZ();
        return (int) Math.sqrt((double) dx * dx + (double) dz * dz);
    }
}
