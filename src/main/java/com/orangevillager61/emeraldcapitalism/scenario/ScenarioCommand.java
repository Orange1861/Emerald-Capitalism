package com.orangevillager61.emeraldcapitalism.scenario;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.orangevillager61.emeraldcapitalism.command.StateReloadScenarioCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.UUID;

/**
 * {@code /ecapscenario}: the development-only QA commands (load, start, action, inspect, result,
 * reset) and the older {@code state_reload} scene.
 *
 * <p>Caller: {@code EmeraldCapitalism.onRegisterCommands}, which passes
 * {@code FMLEnvironment.production}. In a production game nothing is registered.
 */
public final class ScenarioCommand {

    public static final String ROOT = "ecapscenario";

    private ScenarioCommand() {
    }

    /**
     * Registers the scenario commands unless {@code production} is true.
     *
     * @return whether anything was registered
     */
    public static boolean register(CommandDispatcher<CommandSourceStack> dispatcher, boolean production) {
        if (production) {
            return false;
        }
        dispatcher.register(Commands.literal(ROOT)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("load")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) ->
                                        SharedSuggestionProvider.suggest(ScenarioRunner.SCENARIOS, builder))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    String id = StringArgumentType.getString(context, "id");
                                    return run(source, () -> {
                                        ScenarioRunner.load(source.getLevel(),
                                                BlockPos.containing(source.getPosition()), id);
                                        return List.of(id + " loaded and held; run start to release the villagers");
                                    });
                                })))
                .then(Commands.literal("start").executes(context -> run(context.getSource(), () -> {
                    ScenarioRunner.start();
                    return List.of("started; the villagers now run their own AI");
                })))
                .then(Commands.literal("action")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) ->
                                        SharedSuggestionProvider.suggest(MixedVillageScene.ACTIONS, builder))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    String id = StringArgumentType.getString(context, "id");
                                    return run(source, () -> List.of(
                                            ScenarioRunner.action(id, source.getPlayer())));
                                })))
                .then(Commands.literal("inspect")
                        .executes(context -> run(context.getSource(), () -> ScenarioRunner.inspect(null)))
                        .then(Commands.argument("entity", EntityArgument.entity())
                                .executes(context -> {
                                    UUID target = entity(context).getUUID();
                                    return run(context.getSource(), () -> ScenarioRunner.inspect(target));
                                })))
                .then(Commands.literal("result").executes(context -> run(context.getSource(),
                        ScenarioRunner::result)))
                .then(Commands.literal("reset").executes(context -> run(context.getSource(), () -> {
                    ScenarioRunner.reset();
                    return List.of("scenario removed");
                }))));
        StateReloadScenarioCommand.register(dispatcher);
        return true;
    }

    private static Entity entity(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return EntityArgument.getEntity(context, "entity");
    }

    @FunctionalInterface
    private interface Step {
        List<String> run() throws ScenarioRunner.ScenarioException;
    }

    private static int run(CommandSourceStack source, Step step) {
        List<String> lines;
        try {
            lines = step.run();
        } catch (ScenarioRunner.ScenarioException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return lines.isEmpty() ? 0 : 1;
    }
}
