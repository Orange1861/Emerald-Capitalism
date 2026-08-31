package com.orangevillager61.emeraldcapitalism.mixin;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import com.orangevillager61.emeraldcapitalism.behavior.BankAwareAssignProfessionFromJobSite;
import com.orangevillager61.emeraldcapitalism.behavior.BankAwarePotentialJobSiteBehavior;
import com.orangevillager61.emeraldcapitalism.behavior.VillagerGoalPackageIntegration;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.AssignProfessionFromJobSite;
import net.minecraft.world.entity.ai.behavior.GoToPotentialJobSite;
import net.minecraft.world.entity.ai.behavior.VillagerGoalPackages;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Required 1.21.1 adapter for the mod's villager goal-package integration.
 *
 * <p>Retained invariant: every returned core package has exactly one
 * fence-gate interaction and one boat-avoidance behavior, and every returned
 * idle/meet package has exactly one begging behavior, without removing vanilla
 * entries or changing their order.</p>
 *
 * <p>Target fragility: these static vanilla package factories change their
 * profession signature in later Minecraft versions. NeoForge 21.1.219 exposes
 * no equivalent public hook, so all composition lives in
 * {@link VillagerGoalPackageIntegration} and this mixin remains the smallest
 * version-sensitive adapter.</p>
 */
@Mixin(VillagerGoalPackages.class)
public class VillagerGoalPackagesMixin {

    private VillagerGoalPackagesMixin() {
    }

    @Inject(
        method = "getCorePackage",
        at = @At("RETURN"),
        cancellable = true,
        require = 1
    )
    private static void addCoreBehaviors(
            VillagerProfession profession,
            float speedModifier,
            CallbackInfoReturnable<ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>>> cir
    ) {
        cir.setReturnValue(VillagerGoalPackageIntegration.addCoreBehaviors(cir.getReturnValue()));
    }

    /** Routes potential bank job sites through the bank-aware work-side behavior. */
    @Redirect(
            method = "getCorePackage",
            at = @At(value = "NEW",
                    target = "Lnet/minecraft/world/entity/ai/behavior/GoToPotentialJobSite;"),
            require = 1
    )
    private static GoToPotentialJobSite useBankAwarePotentialJobSite(float speedModifier) {
        return new BankAwarePotentialJobSiteBehavior(speedModifier);
    }

    /** Prevents a bank POI from assigning its profession on the deposit side. */
    @Redirect(
            method = "getCorePackage",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/behavior/AssignProfessionFromJobSite;create()Lnet/minecraft/world/entity/ai/behavior/BehaviorControl;"),
            require = 1
    )
    private static BehaviorControl<Villager> useBankAwareProfessionAssignment() {
        return new BankAwareAssignProfessionFromJobSite();
    }

    @Inject(
        method = "getIdlePackage",
        at = @At("RETURN"),
        cancellable = true,
        require = 1
    )
    private static void addIdleBehaviors(
            VillagerProfession profession,
            float speedModifier,
            CallbackInfoReturnable<ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>>> cir
    ) {
        cir.setReturnValue(VillagerGoalPackageIntegration.addIdleBehaviors(cir.getReturnValue()));
    }

    @Inject(
        method = "getMeetPackage",
        at = @At("RETURN"),
        cancellable = true,
        require = 1
    )
    private static void addMeetBehaviors(
            VillagerProfession profession,
            float speedModifier,
            CallbackInfoReturnable<ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>>> cir
    ) {
        cir.setReturnValue(VillagerGoalPackageIntegration.addMeetBehaviors(cir.getReturnValue()));
    }
}
