package com.orangevillager61.emeraldcapitalism.mixin;

import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestManager;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestHandle;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes the sink's real path attempt before vanilla may consume WALK_TARGET. */
@Mixin(MoveToTargetSink.class)
public abstract class MoveToTargetSinkMixin {

    @Unique
    @Nullable
    private MovementRequestHandle emeraldCapitalism$capturedHandle;

    @Inject(method = "checkExtraStartConditions", at = @At("HEAD"))
    private void emeraldCapitalism$captureSinkTarget(ServerLevel level, Mob owner,
                                                      CallbackInfoReturnable<Boolean> callback) {
        emeraldCapitalism$capturedHandle =
                MovementRequestManager.captureVanillaSinkHandle(owner);
        MovementRequestManager.observeVanillaSinkStart(owner, level.getGameTime());
    }

    @Inject(method = "start", at = @At("HEAD"))
    private void emeraldCapitalism$captureStartedSink(ServerLevel level, Mob owner, long gameTime,
                                                       CallbackInfo callback) {
        if (emeraldCapitalism$capturedHandle == null) {
            emeraldCapitalism$capturedHandle =
                    MovementRequestManager.captureVanillaSinkHandle(owner);
        }
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

    @Inject(method = "stop", at = @At("HEAD"), cancellable = true)
    private void emeraldCapitalism$ignoreStaleStop(ServerLevel level, Mob owner, long gameTime,
                                                   CallbackInfo callback) {
        if (MovementRequestManager.isStaleVanillaSink(owner, emeraldCapitalism$capturedHandle)) {
            callback.cancel();
        }
    }
}
