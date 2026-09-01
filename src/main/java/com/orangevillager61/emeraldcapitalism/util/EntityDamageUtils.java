package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** Version bridge for the server/client split of entity damage. */
public final class EntityDamageUtils {
    private EntityDamageUtils() {
    }

    public static boolean hurt(LivingEntity target, DamageSource source, float amount) {
//? if >=1.21.4 {
        if (!(target.level() instanceof ServerLevel level)) {
            return false;
        }
        return target.hurtServer(level, source, amount);
//?} else {
/*        return target.hurt(source, amount);
 *///?}
    }
}
