package com.orangevillager61.emeraldcapitalism.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.datafixers.util.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Optional;

/** Adds a biome-independent convenience alias for vanilla's village locate search. */
public final class VillageLocateCommand {

    private static final int STRUCTURE_SEARCH_RADIUS = 100;
    private static final TagKey<Structure> VILLAGE_STRUCTURE_TAG = TagKey.create(
            Registries.STRUCTURE,
            ResourceLocation.fromNamespaceAndPath("minecraft", "village")
    );

    private VillageLocateCommand() {
    }

    /** Registers the village search under the mod's locate command namespace. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("locate")
                        .then(Commands.literal("ecap")
                                .then(Commands.literal("structures")
                                        .then(Commands.literal("village")
                                                .requires(source -> source.hasPermission(2))
                                                .executes(ctx -> locateVillage(ctx.getSource())))))
        );
    }

    private static int locateVillage(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Registry<Structure> structureRegistry =
                com.orangevillager61.emeraldcapitalism.util.RegistryAccessCompat.get(
                        level.registryAccess(), Registries.STRUCTURE);
        Optional<HolderSet.Named<Structure>> villageStructures =
                com.orangevillager61.emeraldcapitalism.util.RegistryAccessCompat.getTag(
                        structureRegistry, VILLAGE_STRUCTURE_TAG);

        if (villageStructures.isEmpty()) {
            source.sendFailure(Component.literal(
                    "The vanilla village structure tag is unavailable in this world."
            ));
            return 0;
        }

        BlockPos sourcePosition = BlockPos.containing(source.getPosition());
        Pair<BlockPos, Holder<Structure>> result = level.getChunkSource()
                .getGenerator()
                .findNearestMapStructure(
                        level,
                        villageStructures.get(),
                        sourcePosition,
                        STRUCTURE_SEARCH_RADIUS,
                        false
                );

        if (result == null) {
            source.sendFailure(Component.literal("Could not find a village within the search radius."));
            return 0;
        }

        BlockPos villagePosition = result.getFirst();
        int distance = horizontalDistance(sourcePosition, villagePosition);
        Component coordinates = ComponentUtils.wrapInSquareBrackets(
                Component.translatable(
                        "chat.coordinates",
                        villagePosition.getX(),
                        "~",
                        villagePosition.getZ()
                )
        ).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(
                        ClickEvent.Action.SUGGEST_COMMAND,
                        "/tp @s " + villagePosition.getX() + " ~ " + villagePosition.getZ()
                ))
                .withHoverEvent(new HoverEvent(
                        HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("chat.coordinates.tooltip")
                ))
        );

        source.sendSuccess(
                () -> Component.translatable(
                        "commands.locate.structure.success",
                        "#minecraft:village",
                        coordinates,
                        distance
                ),
                false
        );
        return distance;
    }

    private static int horizontalDistance(BlockPos from, BlockPos to) {
        long dx = (long) to.getX() - from.getX();
        long dz = (long) to.getZ() - from.getZ();
        return (int) Math.sqrt((double) (dx * dx + dz * dz));
    }
}
