package com.orangevillager61.emeraldcapitalism.mixin;

import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes the sink's real path attempt before vanilla may consume WALK_TARGET. */
@Mixin(MoveToTargetSink.class)
public abstract class MoveToTargetSinkMixin {

    @Shadow
    @Nullable
    private Path path;

    @Unique
    private long emeraldCapitalism$capturedSinkEpoch = Long.MIN_VALUE;

    @Unique
    @Nullable
    private Path emeraldCapitalism$requestedDestinationPath;

    @Unique
    private boolean emeraldCapitalism$fallbackPathCreated;

    @Unique
    @Nullable
    private WalkTarget emeraldCapitalism$capturedWalkTarget;

    @Inject(method = "checkExtraStartConditions", at = @At("HEAD"))
    private void emeraldCapitalism$captureSinkTarget(ServerLevel level, Mob owner,
                                                      CallbackInfoReturnable<Boolean> callback) {
        emeraldCapitalism$capturedSinkEpoch =
                MovementRequestManager.captureVanillaSinkEpoch(owner);
        emeraldCapitalism$capturedWalkTarget = owner.getBrain().getMemory(
                net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET).orElse(null);
        MovementRequestManager.observeVanillaSinkStart(owner, level.getGameTime());
    }

    @Inject(method = "start", at = @At("HEAD"))
    private void emeraldCapitalism$captureStartedSink(ServerLevel level, Mob owner, long gameTime,
                                                       CallbackInfo callback) {
        if (emeraldCapitalism$capturedSinkEpoch == Long.MIN_VALUE) {
            emeraldCapitalism$capturedSinkEpoch =
                    MovementRequestManager.captureVanillaSinkEpoch(owner);
        }
        WalkTarget target = owner.getBrain().getMemory(
                net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET).orElse(null);
        if (target != null) {
            MovementRequestManager.observeVanillaSinkStarted(owner, target, path);
        }
    }

    @Inject(method = "checkExtraStartConditions", at = @At("RETURN"))
    private void emeraldCapitalism$observeSinkResult(ServerLevel level, Mob owner,
                                                      CallbackInfoReturnable<Boolean> callback) {
        MovementRequestManager.observeVanillaSinkResult(owner, level.getGameTime());
    }

    @Inject(method = "tryComputePath", at = @At("HEAD"))
    private void emeraldCapitalism$resetPathObservation(Mob mob, WalkTarget target, long gameTime,
                                                        CallbackInfoReturnable<Boolean> callback) {
        emeraldCapitalism$requestedDestinationPath = null;
        emeraldCapitalism$fallbackPathCreated = false;
    }

    @Redirect(method = "tryComputePath", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;"))
    private Path emeraldCapitalism$observeRequestedDestinationPath(
            PathNavigation navigation, BlockPos destination, int accuracy,
            Mob mob, WalkTarget target, long gameTime) {
        Path path = navigation.createPath(destination, accuracy);
        emeraldCapitalism$requestedDestinationPath = path;
        return path;
    }

    @Redirect(method = "tryComputePath", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;createPath(DDDI)Lnet/minecraft/world/level/pathfinder/Path;"))
    private Path emeraldCapitalism$observeFallbackPath(
            PathNavigation navigation, double x, double y, double z, int accuracy,
            Mob mob, WalkTarget target, long gameTime) {
        Path path = navigation.createPath(x, y, z, accuracy);
        emeraldCapitalism$fallbackPathCreated = path != null;
        return path;
    }

    @Inject(method = "tryComputePath", at = @At("RETURN"))
    private void emeraldCapitalism$observePathPlan(Mob mob, WalkTarget target, long gameTime,
                                                   CallbackInfoReturnable<Boolean> callback) {
        boolean pathCreated = emeraldCapitalism$requestedDestinationPath != null
                || emeraldCapitalism$fallbackPathCreated;
        boolean destinationReachable = emeraldCapitalism$requestedDestinationPath != null
                && emeraldCapitalism$requestedDestinationPath.canReach();
        MovementRequestManager.observeVanillaPathPlan(
                mob, target, gameTime, pathCreated,
                emeraldCapitalism$requestedDestinationPath != null,
                destinationReachable);
    }

    @Inject(method = "stop", at = @At("HEAD"), cancellable = true)
    private void emeraldCapitalism$ignoreStaleStop(ServerLevel level, Mob owner, long gameTime,
                                                   CallbackInfo callback) {
        if (MovementRequestManager.isStaleVanillaSink(
                owner, emeraldCapitalism$capturedSinkEpoch,
                emeraldCapitalism$capturedWalkTarget)) {
            callback.cancel();
        }
    }
}
