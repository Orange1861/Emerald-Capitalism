package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.entity.EmeraldSkrimisher;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

/**
 * Replaces the inherited Iron Golem melee pattern with the Skrimisher's
 * golem-assisted jump attack and villager-safe knockback attack.
 */
public final class EmeraldSkrimisherCombatGoal extends MeleeAttackGoal {

    private final EmeraldSkrimisher skrimisher;
    private boolean jumpAttack;
    private boolean jumpStarted;

    public EmeraldSkrimisherCombatGoal(EmeraldSkrimisher skrimisher) {
        super(skrimisher, 1.0D, true);
        this.skrimisher = skrimisher;
    }

    @Override
    public void start() {
        LivingEntity target = skrimisher.getTarget();
        jumpAttack = target != null && skrimisher.hasNearbyGolem(target);
        jumpStarted = false;
        super.start();
    }

    @Override
    public void stop() {
        jumpAttack = false;
        jumpStarted = false;
        super.stop();
    }

    @Override
    protected boolean canPerformAttack(LivingEntity target) {
        if (!super.canPerformAttack(target)) {
            return false;
        }
        if (!jumpAttack) {
            return true;
        }
        if (!jumpStarted) {
            skrimisher.getJumpControl().jump();
            jumpStarted = true;
            return false;
        }

        // The attack lands only after the jump has peaked and the Skrimisher
        // is descending toward the target.
        return !skrimisher.onGround() && skrimisher.getDeltaMovement().y < 0.0D;
    }

    @Override
    protected void checkAndPerformAttack(LivingEntity target) {
        if (!canPerformAttack(target)) {
            return;
        }

        resetAttackCooldown();
        skrimisher.swing(InteractionHand.MAIN_HAND);
        skrimisher.performCombatAttack(target, jumpAttack);
        jumpStarted = false;
        jumpAttack = skrimisher.hasNearbyGolem(target);
    }
}
