package com.orangevillager61.emeraldcapitalism.test;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.scenario.MixedVillageScene;
import com.orangevillager61.emeraldcapitalism.scenario.MixedVillageScene.Actor;
import com.orangevillager61.emeraldcapitalism.scenario.MixedVillageScene.Checkpoint;
import com.orangevillager61.emeraldcapitalism.scenario.MixedVillageScene.Status;
import com.orangevillager61.emeraldcapitalism.scenario.ScenarioCommand;
import com.orangevillager61.emeraldcapitalism.scenario.ScenarioRunner;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * Natural-AI coverage for the step 1 scenario runner. The mixed-village test is the standing
 * vanilla safety check: it runs the same scene and runner as {@code /ecapscenario}, and after
 * {@code start} it only changes time, adds or removes a held hostile, and opens and closes a
 * trade the way a player does.
 */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class ScenarioRunnerGameTests {

    private static final int POLL_TICKS = 20;
    /** Lets the trade run for several observations before the player closes it. */
    private static final int TRADE_OPEN_TICKS = 100;
    /** Below the 1024-character lectern page limit, leaving room for the suffix. */
    private static final int FAILURE_MESSAGE_LIMIT = 900;

    private ScenarioRunnerGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void scenarioCommandsAreRegisteredOnlyOutsideProduction(GameTestHelper helper) {
        CommandDispatcher<CommandSourceStack> production = new CommandDispatcher<>();
        helper.assertFalse(ScenarioCommand.register(production, true),
                "a production game registered scenario commands");
        helper.assertTrue(production.getRoot().getChild(ScenarioCommand.ROOT) == null,
                "a production dispatcher has /" + ScenarioCommand.ROOT);

        CommandDispatcher<CommandSourceStack> development = new CommandDispatcher<>();
        helper.assertTrue(ScenarioCommand.register(development, false),
                "a development game did not register scenario commands");
        CommandNode<CommandSourceStack> root = development.getRoot().getChild(ScenarioCommand.ROOT);
        helper.assertTrue(root != null, "a development dispatcher lacks /" + ScenarioCommand.ROOT);
        for (String child : List.of("load", "start", "action", "inspect", "result", "reset", "state_reload")) {
            helper.assertTrue(root.getChild(child) != null, "/" + ScenarioCommand.ROOT + " lacks " + child);
        }
        // This GameTest server is a development run, so the real registration path took effect.
        helper.assertTrue(helper.getLevel().getServer().getCommands().getDispatcher().getRoot()
                .getChild(ScenarioCommand.ROOT) != null, "the development server did not register the commands");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_scenario_mixed_village", timeoutTicks = 6_000)
    public static void mixedVillageKeepsVanillaWorkMeetRestPanicAndTrade(GameTestHelper helper) {
        long previousDayTime = helper.getLevel().getDayTime();
        int half = MixedVillageScene.SIZE / 2;
        BlockPos centre = helper.absolutePos(new BlockPos(half, 1, half));
        MixedVillageScene scene;
        try {
            scene = ScenarioRunner.load(helper.getLevel(), centre, MixedVillageScene.ID);
        } catch (ScenarioRunner.ScenarioException exception) {
            helper.fail("load failed: " + exception.getMessage());
            return;
        }
        helper.assertTrue(VillageRegistryData.get(helper.getLevel()).getVillageFor(centre) != null,
                "the scene was not registered as a mod village");
        for (Actor actor : scene.actors()) {
            Villager villager = actor.villager();
            helper.assertTrue(villager.isNoAi(), actor.role() + " was not held before start");
            // Nothing handcrafted: no route, target, or job site exists before the Brain runs.
            for (MemoryModuleType<?> memory : List.of(MemoryModuleType.WALK_TARGET, MemoryModuleType.PATH,
                    MemoryModuleType.JOB_SITE, MemoryModuleType.HOME, MemoryModuleType.MEETING_POINT)) {
                helper.assertFalse(villager.getBrain().hasMemoryValue(memory),
                        actor.role() + " started with " + memory);
            }
        }
        Runnable finish = () -> {
            try {
                ScenarioRunner.reset();
            } catch (ScenarioRunner.ScenarioException ignored) {
                // Already reset by an earlier failure path.
            }
            helper.getLevel().setDayTime(previousDayTime);
        };
        try {
            ScenarioRunner.start();
            helper.assertTrue(scene.actors().stream().noneMatch(actor -> actor.villager().isNoAi()),
                    "start did not release every actor");
            ScenarioRunner.action("work", null);
        } catch (ScenarioRunner.ScenarioException exception) {
            finish.run();
            helper.fail("start failed: " + exception.getMessage());
            return;
        }

        await(helper, scene, Checkpoint.WORK, finish, () -> {
            Villager farmer = scene.actors().get(0).villager();
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            player.moveTo(farmer.getX() + 1.0D, farmer.getY(), farmer.getZ(), 0.0F, 0.0F);
            if (!act(helper, finish, "trade", player)) {
                return;
            }
            helper.runAfterDelay(TRADE_OPEN_TICKS, () -> {
                if (!farmer.isTrading()) {
                    String actors = String.join(" | ", scene.inspect(null));
                    finish.run();
                    failWithDetail(helper, "the farmer did not open a trade for the player", actors);
                    return;
                }
                // What closing the trading screen does: MerchantMenu.removed clears the player.
                farmer.setTradingPlayer(null);
                await(helper, scene, Checkpoint.TRADE, finish, () -> {
                    if (!act(helper, finish, "panic", null)) {
                        return;
                    }
                    await(helper, scene, Checkpoint.PANIC, finish, () -> {
                        if (!act(helper, finish, "calm", null)) {
                            return;
                        }
                        await(helper, scene, Checkpoint.CALM, finish, () -> {
                            if (!act(helper, finish, "meet", null)) {
                                return;
                            }
                            await(helper, scene, Checkpoint.MEET, finish, () -> {
                                if (!act(helper, finish, "rest", null)) {
                                    return;
                                }
                                await(helper, scene, Checkpoint.REST, finish, () -> {
                                    List<String> result;
                                    try {
                                        // Logs the RESULT line, with each phase's tick count, as run evidence.
                                        result = ScenarioRunner.result();
                                    } catch (ScenarioRunner.ScenarioException exception) {
                                        finish.run();
                                        helper.fail("result failed: " + exception.getMessage());
                                        return;
                                    }
                                    boolean passed = scene.allPassed();
                                    finish.run();
                                    if (!passed) {
                                        failWithDetail(helper, "mixed village: " + String.join(" | ", result), "");
                                        return;
                                    }
                                    helper.succeed();
                                });
                            });
                        });
                    });
                });
            });
        });
    }

    private static boolean act(GameTestHelper helper, Runnable finish, String action, Player player) {
        try {
            ScenarioRunner.action(action, player);
            return true;
        } catch (ScenarioRunner.ScenarioException exception) {
            finish.run();
            helper.fail(action + " failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * The GameTest framework copies a failure message into a lectern book page, which holds at
     * most 1024 characters, so the full inspection goes to the log and the message is cut.
     */
    private static void failWithDetail(GameTestHelper helper, String message, String detail) {
        EmeraldCapitalism.LOGGER.error("[ECAP][Scenario] TEST FAILURE {} || {}", message, detail);
        helper.fail(message.length() <= FAILURE_MESSAGE_LIMIT ? message
                : message.substring(0, FAILURE_MESSAGE_LIMIT) + "... (full text in the log)");
    }

    /** Waits for the runner's own observation to settle {@code checkpoint}. */
    private static void await(GameTestHelper helper, MixedVillageScene scene, Checkpoint checkpoint,
                              Runnable finish, Runnable next) {
        Status status = scene.status(checkpoint);
        if (status == Status.PASS) {
            next.run();
            return;
        }
        if (status == Status.FAIL || status == Status.NOT_RUN) {
            String result = String.join(" | ", scene.result());
            String actors = String.join(" | ", scene.inspect(null));
            finish.run();
            failWithDetail(helper, "mixed village " + checkpoint + " " + status + ": " + result, actors);
            return;
        }
        helper.runAfterDelay(POLL_TICKS, () -> await(helper, scene, checkpoint, finish, next));
    }
}
