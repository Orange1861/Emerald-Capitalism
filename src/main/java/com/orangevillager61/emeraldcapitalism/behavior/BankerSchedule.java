package com.orangevillager61.emeraldcapitalism.behavior;

import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.entity.schedule.Schedule;
import net.minecraft.world.entity.schedule.ScheduleBuilder;

/**
 * The banker's daily schedule: the banker stays at the bank all day (WORK from day time 10 to
 * 12000, never IDLE or MEET) and rests at vanilla's bedtime.
 *
 * <p>Callers: {@code VillagerSpawnEvents.onVillagerTick} reconciles every 20 ticks, because vanilla
 * rebuilds a Brain with the default schedule on load, aging and profession change, and
 * {@link BankAwareAssignProfessionFromJobSite} applies it at the moment the profession is assigned.
 * The schedule is not persisted and no new mixin is needed.
 */
public final class BankerSchedule {

    /** Work from the start of the day until vanilla's bedtime; rest is vanilla's. */
    public static final Schedule SCHEDULE = new ScheduleBuilder(new Schedule())
            .changeActivityAt(10, Activity.WORK)
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
