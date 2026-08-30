package com.orangevillager61.emeraldcapitalism.mixin;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.entity.ai.ZombieDoorBreakingPolicy;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.monster.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Predicate;

/** Applies the server-configured difficulty rule to zombies' vanilla door goal. */
@Mixin(Zombie.class)
public abstract class ZombieDoorBreakingMixin {

    @ModifyArg(
            method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/goal/BreakDoorGoal;<init>(Lnet/minecraft/world/entity/Mob;Ljava/util/function/Predicate;)V"
            ),
            index = 1
    )
    private Predicate<Difficulty> emeraldcapitalism$configuredDoorBreakingDifficulty(
            Predicate<Difficulty> vanillaPredicate) {
        return difficulty -> ZombieDoorBreakingPolicy.allowsDifficulty(
                difficulty, Config.zombiesCanBreakDoorsOnAnyDifficulty);
    }
}
