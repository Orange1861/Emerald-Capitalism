package com.orangevillager61.emeraldcapitalism.behavior;

import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.entity.schedule.Schedule;
import net.minecraft.world.entity.schedule.ScheduleBuilder;

/**
 * The banker's daily schedule: vanilla's villager schedule with the bell hours (9000 to 11000)
 * worked at the bank instead of spent at the bell. Work, idle and rest hours are vanilla's.
 *
 * <p>Callers: {@code VillagerSpawnEvents.onVillagerTick} reconciles every 20 ticks, because vanilla
 * rebuilds a Brain with the default schedule on load, aging and profession change, and
 * {@link BankAwareAssignProfessionFromJobSite} applies it at the moment the profession is assigned.
 * The schedule is not persisted and no new mixin is needed.
 */
public final class BankerSchedule {

    /** Vanilla's {@code VILLAGER_DEFAULT} with WORK in place of MEET. */
    public static final Schedule SCHEDULE = new ScheduleBuilder(new Schedule())
            .changeActivityAt(10, Activity.IDLE)
            .changeActivityAt(2_000, Activity.WORK)
            .changeActivityAt(11_000, Activity.IDLE)
            .changeActivityAt(12_000, Activity.REST)
            .build();

    private BankerSchedule() {
    }

    /** Gives an adult banker its schedule if its Brain does not have it yet. */
    public static void apply(Villager villager) {
        if (villager.isBaby()
                || villager.getVillagerData().getProfession() != ECAPVillagerProfessions.BANKER.get()) {
            return;
        }
        Brain<Villager> brain = villager.getBrain();
        if (brain.getSchedule() != SCHEDULE) {
            brain.setSchedule(SCHEDULE);
        }
    }
}
