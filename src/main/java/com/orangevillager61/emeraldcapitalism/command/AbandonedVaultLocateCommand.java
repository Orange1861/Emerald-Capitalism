package com.orangevillager61.emeraldcapitalism.command;

import com.mojang.brigadier.CommandDispatcher;
import com.orangevillager61.emeraldcapitalism.world.structure.AbandonedVaultLocator;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

/** Internal locate aliases used by the abandoned-vault maps and operators. */
public final class AbandonedVaultLocateCommand {
    private AbandonedVaultLocateCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("locate")
                .then(Commands.literal("ecap")
                        .then(Commands.literal("structures")
                                .then(Commands.literal("abandoned_vault")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(context -> locate(context.getSource(), false)))
                                .then(Commands.literal("second_abandoned_vault")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(context -> locate(context.getSource(), true))))));

        dispatcher.register(Commands.literal("ecap")
                .then(Commands.literal("locate")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("abandoned_vault")
                                .executes(context -> locate(context.getSource(), false)))
                        .then(Commands.literal("second_abandoned_vault")
                                .executes(context -> locate(context.getSource(), true)))));
    }

    private static int locate(CommandSourceStack source, boolean second) {
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());
        Optional<BlockPos> result = second
                ? AbandonedVaultLocator.findSecondNearest(level, origin)
                : AbandonedVaultLocator.findNearest(level, origin);
        if (result.isEmpty()) {
            source.sendFailure(Component.literal(second
                    ? "Could not find a second abandoned vault within the search radius."
                    : "Could not find an abandoned vault within the search radius."));
            return 0;
        }

        BlockPos target = result.get();
        long deltaX = (long) target.getX() - origin.getX();
        long deltaZ = (long) target.getZ() - origin.getZ();
        int distance = (int) Math.sqrt((double) deltaX * deltaX + (double) deltaZ * deltaZ);
        Component coordinates = ComponentUtils.wrapInSquareBrackets(
                Component.translatable("chat.coordinates", target.getX(), "~", target.getZ()))
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                                "/tp @s " + target.getX() + " ~ " + target.getZ()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("chat.coordinates.tooltip"))));
        source.sendSuccess(() -> Component.translatable("commands.locate.structure.success",
                AbandonedVaultLocator.STRUCTURE_KEY.location(), coordinates, distance), false);
        return distance;
    }
}
