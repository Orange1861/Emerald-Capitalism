package com.orangevillager61.emeraldcapitalism.util;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Debug aid behind {@code advanced.debug.villagerJumpLog}: warns when a villager moves farther in
 * one tick than walking, falling or a boat could explain, with what its Brain was doing.
 *
 * <p>Caller: {@code VillagerSpawnEvents.onVillagerTick}, only while the flag is on.
 */
public final class VillagerJumpLogger {

    private static final double JUMP_DISTANCE_SQ = 2.0D * 2.0D;
    private static final Map<UUID, Vec3> LAST_POSITIONS = new ConcurrentHashMap<>();

    private VillagerJumpLogger() {
    }

    public static void observe(Villager villager) {
        if (villager.isRemoved()) {
            LAST_POSITIONS.remove(villager.getUUID());
            return;
        }
        Vec3 now = villager.position();
        Vec3 before = LAST_POSITIONS.put(villager.getUUID(), now);
        if (before == null || villager.isPassenger() || before.distanceToSqr(now) <= JUMP_DISTANCE_SQ) {
            return;
        }
        // A fall of a few blocks in one tick is not a teleport.
        if (Math.abs(before.x - now.x) < 0.5D && Math.abs(before.z - now.z) < 0.5D && now.y < before.y) {
            return;
        }
        var brain = villager.getBrain();
        EmeraldCapitalism.LOGGER.warn(
                "[ECAP][VillagerJump] villager={} uuid={} from={} to={} distance={} dayTime={} activity={} "
                        + "sleeping={} home={} jobSite={} walkTarget={} tick={}",
                villager.getName().getString(), villager.getUUID(), format(before), format(now),
                String.format("%.1f", Math.sqrt(before.distanceToSqr(now))),
                villager.level().getDayTime() % 24_000L,
                brain.getActiveNonCoreActivity().map(Object::toString).orElse("none"),
                villager.isSleeping(),
                brain.getMemory(MemoryModuleType.HOME).map(VillagerJumpLogger::format).orElse("none"),
                brain.getMemory(MemoryModuleType.JOB_SITE).map(VillagerJumpLogger::format).orElse("none"),
                brain.getMemory(MemoryModuleType.WALK_TARGET)
                        .map(VillagerJumpLogger::format).orElse("none"),
                villager.level().getGameTime());
    }

    private static String format(Vec3 pos) {
        return String.format("(%.1f,%.1f,%.1f)", pos.x, pos.y, pos.z);
    }

    private static String format(GlobalPos pos) {
        return pos.pos().toShortString();
    }

    private static String format(WalkTarget target) {
        return target.getTarget().currentBlockPosition().toShortString();
    }
}
