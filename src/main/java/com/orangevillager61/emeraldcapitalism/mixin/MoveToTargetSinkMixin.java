package com.orangevillager61.emeraldcapitalism.mixin;

import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestManager;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes the sink's real path attempt before vanilla may consume WALK_TARGET. */
@Mixin(MoveToTargetSink.class)
public abstract class MoveToTargetSinkMixin {

    @Inject(method = "checkExtraStartConditions", at = @At("HEAD"))
    private void emeraldCapitalism$captureSinkTarget(ServerLevel level, Mob owner,
                                                      CallbackInfoReturnable<Boolean> callback) {
        MovementRequestManager.observeVanillaSinkStart(owner, level.getGameTime());
    }

    @Inject(method = "checkExtraStartConditions", at = @At("RETURN"))
    private void emeraldCapitalism$observeSinkResult(ServerLevel level, Mob owner,
                                                      CallbackInfoReturnable<Boolean> callback) {
        MovementRequestManager.observeVanillaSinkResult(owner, level.getGameTime());
    }

    @Inject(method = "tryComputePath", at = @At("RETURN"))
    private void emeraldCapitalism$observePathPlan(Mob mob, WalkTarget target, long gameTime,
                                                   CallbackInfoReturnable<Boolean> callback) {
        MovementRequestManager.observeVanillaPathPlan(
                mob, target, gameTime, callback.getReturnValue());
    }
}
