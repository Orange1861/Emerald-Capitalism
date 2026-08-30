package com.orangevillager61.emeraldcapitalism.mixin;

import com.orangevillager61.emeraldcapitalism.Config;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.monster.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Applies the configured all-difficulty villager conversion rule to zombies. */
@Mixin(Zombie.class)
public abstract class ZombieVillagerConversionMixin {

    /**
     * Vanilla checks difficulty before it attempts villager conversion. Treat
     * the survival difficulties as Hard only within killedEntity, where
     * that preserves the existing conversion path and its side effects.
     */
    @Redirect(
            method = "killedEntity",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getDifficulty()Lnet/minecraft/world/Difficulty;"
            )
    )
    private Difficulty emeraldcapitalism$conversionDifficulty(ServerLevel level) {
        return configuredConversionDifficulty(level);
    }

    @Unique
    private static Difficulty configuredConversionDifficulty(ServerLevel level) {
        Difficulty difficulty = level.getDifficulty();
        return Config.alwaysConvertVillagersToZombieVillagers && difficulty != Difficulty.PEACEFUL
                ? Difficulty.HARD
                : difficulty;
    }
}
