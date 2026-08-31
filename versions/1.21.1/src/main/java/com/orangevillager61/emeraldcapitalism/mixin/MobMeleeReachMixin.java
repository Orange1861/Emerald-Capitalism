package com.orangevillager61.emeraldcapitalism.mixin;

import com.orangevillager61.emeraldcapitalism.Config;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Extends iron-golem melee checks upward without changing horizontal reach or line of sight. */
@Mixin(Mob.class)
public class MobMeleeReachMixin {

    @Inject(method = "isWithinMeleeAttackRange", at = @At("RETURN"), cancellable = true, require = 1)
    private void emeraldcapitalism$extendIronGolemVerticalReach(
            LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            return;
        }

        Mob attacker = (Mob) (Object) this;
        if (!(attacker instanceof IronGolem)) {
            return;
        }

        AABB attackerBox = attacker.getBoundingBox();
        AABB targetBox = target.getBoundingBox();
        double verticalGapAboveHead = targetBox.minY - attackerBox.maxY;
        if (verticalGapAboveHead < 0.0D
                || verticalGapAboveHead > Config.ironGolemVerticalReachAboveHead) {
            return;
        }

        AABB horizontalReach = attackerBox.inflate(0.5D, 0.0D, 0.5D);
        if (horizontalReach.maxX >= targetBox.minX
                && horizontalReach.minX <= targetBox.maxX
                && horizontalReach.maxZ >= targetBox.minZ
                && horizontalReach.minZ <= targetBox.maxZ) {
            cir.setReturnValue(true);
        }
    }
}
