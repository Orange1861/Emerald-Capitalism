package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters.Operation;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Times whole server ticks and villager entity ticks so a busy village can be compared before and
 * after later steps (work package B4). Both counters are opt-in: with detailed timing off,
 * {@link PerformanceTimingCounters#startTiming} reads no clock. The totals appear in the
 * {@code [ECAP][Performance]} line logged at server stop.
 *
 * <p>Server ticks and entity ticks run one at a time on the server thread, so one start value for
 * each is enough. Client-side villagers are ignored because they tick on the client thread.
 */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class PerformanceTimingEvents {

    private static long serverTickStart = Long.MIN_VALUE;
    private static long villagerTickStart = Long.MIN_VALUE;

    private PerformanceTimingEvents() {
    }

    @SubscribeEvent
    public static void onServerTickPre(ServerTickEvent.Pre event) {
        serverTickStart = PerformanceTimingCounters.startTiming(Operation.SERVER_TICK);
    }

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        PerformanceTimingCounters.finishTiming(Operation.SERVER_TICK, serverTickStart);
        serverTickStart = Long.MIN_VALUE;
    }

    @SubscribeEvent
    public static void onEntityTickPre(EntityTickEvent.Pre event) {
        if (event.getEntity() instanceof Villager villager && !villager.level().isClientSide()) {
            villagerTickStart = PerformanceTimingCounters.startTiming(Operation.VILLAGER_ENTITY_TICK);
        }
    }

    @SubscribeEvent
    public static void onEntityTickPost(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof Villager villager && !villager.level().isClientSide()) {
            PerformanceTimingCounters.finishTiming(Operation.VILLAGER_ENTITY_TICK, villagerTickStart);
            villagerTickStart = Long.MIN_VALUE;
        }
    }
}
