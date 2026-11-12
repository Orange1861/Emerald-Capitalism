package com.orangevillager61.emeraldcapitalism.scenario;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Holds the one development scenario that is loaded on this server and observes it.
 *
 * <p>Callers: {@link ScenarioCommand} for {@code /ecapscenario} in development runs, and
 * {@code ScenarioRunnerGameTests}. Observation is read-only, so a running scenario judges the
 * villagers' own AI without steering it.
 */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class ScenarioRunner {

    /** The scenes {@code load} accepts. Step 7 adds the banker and lumberjack scenes. */
    public static final List<String> SCENARIOS = List.of(MixedVillageScene.ID);

    /**
     * Sampling once a second follows the AI polling rule; every checkpoint holds its state for
     * several seconds (working, meeting, sleeping, panicking), so a sample cannot miss one.
     */
    static final int OBSERVE_INTERVAL_TICKS = 20;

    /** Session-local; the session cleanup owner clears it when the server stops. */
    @Nullable
    private static MixedVillageScene active;

    private ScenarioRunner() {
    }

    /** Thrown for a request the current scenario state cannot accept; the message is for the player. */
    public static final class ScenarioException extends Exception {
        ScenarioException(String message) {
            super(message);
        }
    }

    /**
     * Builds {@code id} centred on {@code centre}, whose block is the first air block above the
     * scene's floor. The scene's actors stay held until {@link #start()}.
     */
    public static MixedVillageScene load(ServerLevel level, BlockPos centre, String id) throws ScenarioException {
        if (active != null) {
            throw new ScenarioException("scenario " + MixedVillageScene.ID + " is already loaded; run reset first");
        }
        if (!SCENARIOS.contains(id)) {
            throw new ScenarioException("unknown scenario " + id + "; known: " + SCENARIOS);
        }
        int half = MixedVillageScene.SIZE / 2;
        BlockPos origin = centre.offset(-half, -1, -half);
        VillageRegistryData registry = VillageRegistryData.get(level);
        for (BlockPos corner : List.of(origin, origin.offset(MixedVillageScene.SIZE - 1, 0, 0),
                origin.offset(0, 0, MixedVillageScene.SIZE - 1),
                origin.offset(MixedVillageScene.SIZE - 1, 0, MixedVillageScene.SIZE - 1), centre)) {
            if (registry.getVillageFor(corner) != null) {
                throw new ScenarioException(id + " needs a 20x20 area outside any village");
            }
        }
        active = MixedVillageScene.build(level, origin);
        EmeraldCapitalism.LOGGER.info("[ECAP][Scenario] LOAD {} origin={}", id, origin.toShortString());
        return active;
    }

    public static void start() throws ScenarioException {
        MixedVillageScene scene = require();
        if (scene.released()) {
            throw new ScenarioException("scenario already started");
        }
        scene.release();
        EmeraldCapitalism.LOGGER.info("[ECAP][Scenario] START {}", MixedVillageScene.ID);
    }

    public static String action(String id, @Nullable Player player) throws ScenarioException {
        MixedVillageScene scene = require();
        if (!scene.released()) {
            throw new ScenarioException("run start before actions");
        }
        String message = scene.action(id, player);
        if (message == null) {
            throw new ScenarioException("unknown action " + id + "; known: " + MixedVillageScene.ACTIONS);
        }
        EmeraldCapitalism.LOGGER.info("[ECAP][Scenario] ACTION {}: {}", id, message);
        return message;
    }

    public static List<String> inspect(@Nullable UUID target) throws ScenarioException {
        return require().inspect(target);
    }

    public static List<String> result() throws ScenarioException {
        List<String> lines = require().result();
        EmeraldCapitalism.LOGGER.info("[ECAP][Scenario] RESULT {}", String.join(" | ", lines));
        return lines;
    }

    public static void reset() throws ScenarioException {
        MixedVillageScene scene = require();
        active = null;
        scene.reset();
        EmeraldCapitalism.LOGGER.info("[ECAP][Scenario] RESET {}", MixedVillageScene.ID);
    }

    @Nullable
    public static MixedVillageScene active() {
        return active;
    }

    private static MixedVillageScene require() throws ScenarioException {
        if (active == null) {
            throw new ScenarioException("no scenario loaded; run load " + MixedVillageScene.ID);
        }
        return active;
    }

    /** Called by the session cleanup owner. The world keeps the scene; reset is a player action. */
    public static void clearActive() {
        active = null;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MixedVillageScene scene = active;
        if (scene == null || scene.level().getGameTime() % OBSERVE_INTERVAL_TICKS != 0L) {
            return;
        }
        scene.observe();
    }
}
