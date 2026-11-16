package com.orangevillager61.emeraldcapitalism.behavior;

import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.entity.schedule.Schedule;
import net.minecraft.world.entity.schedule.ScheduleBuilder;

/**
 * The adult villager's daily schedule: vanilla's {@code VILLAGER_DEFAULT} except that rest ends at
 * sunrise ({@link #WAKE_TIME}) instead of at tick 10 of the next day, so villagers are out of bed by
 * day time 0 rather than still asleep then. Bankers use {@link BankerSchedule}, which has the same
 * wake time.
 *
 * <p>Applied by {@code VillagerSpawnEvents.onVillagerTick} every 20 ticks because vanilla rebuilds
 * a Brain with the default schedule on load, aging and profession change. Not persisted.
 */
public final class VillagerSchedule {

    /** Day time at which resting ends (vanilla sunrise). */
    public static final int WAKE_TIME = 23_000;

    public static final Schedule SCHEDULE = new ScheduleBuilder(new Schedule())
            .changeActivityAt(10, Activity.IDLE)
            .changeActivityAt(2_000, Activity.WORK)
            .changeActivityAt(9_000, Activity.MEET)
            .changeActivityAt(11_000, Activity.IDLE)
            .changeActivityAt(12_000, Activity.REST)
            .changeActivityAt(WAKE_TIME, Activity.IDLE)
            .build();

    private VillagerSchedule() {
    }

    /** Gives an adult non-banker villager its schedule if its Brain does not have it yet. */
    public static void apply(Villager villager) {
        if (villager.isBaby()
                || villager.getVillagerData().getProfession() == ECAPVillagerProfessions.BANKER.get()) {
            return;
        }
        Brain<Villager> brain = villager.getBrain();
        if (brain.getSchedule() != Schedule.VILLAGER_DEFAULT && brain.getSchedule() != SCHEDULE) {
            return;
        }
        if (brain.getSchedule() != SCHEDULE) {
            brain.setSchedule(SCHEDULE);
        }
    }
}
