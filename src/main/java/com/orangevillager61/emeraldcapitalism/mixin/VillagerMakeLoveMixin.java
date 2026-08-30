package com.orangevillager61.emeraldcapitalism.mixin;

import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.event.VillagerBreedingEvents;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import com.orangevillager61.emeraldcapitalism.util.VillagerFamilyUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.behavior.VillagerMakeLove;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Mixin for the VillagerMakeLove behavior to modify breeding mechanics.
 */
@Mixin(VillagerMakeLove.class)
public class VillagerMakeLoveMixin {

    /**
     * The breeding cooldown in ticks (10 minutes = 12000 ticks).
     */
    @Unique
    private static final int BREEDING_COOLDOWN_TICKS = 12000;

    /**
     * Minimum hunger level required for a villager to breed.
     */
    @Unique
    private static final int MINIMUM_HUNGER_TO_BREED = VillagerBreedingSessions.MINIMUM_HUNGER;

    /** Performs all preflight checks before vanilla emits breeding hearts. */
    @Inject(method = "checkExtraStartConditions", at = @At("HEAD"), cancellable = true)
    private void onCheckExtraStartConditions(ServerLevel level, Villager villager, CallbackInfoReturnable<Boolean> cir) {
        AgeableMob breedTarget = villager.getBrain()
                .getMemory(MemoryModuleType.BREED_TARGET)
                .orElse(null);

        if (!(breedTarget instanceof Villager partner)) {
            return;
        }

        if (VillagerBreedingSessions.isPairBlocked(level, villager, partner)) {
            cir.setReturnValue(false);
            return;
        }

        if (!villager.isAlive() || !partner.isAlive()
                || villager.getAge() != 0 || partner.getAge() != 0
                || villager.isSleeping() || partner.isSleeping()
                || !BehaviorUtils.targetIsValid(villager.getBrain(), MemoryModuleType.BREED_TARGET, EntityType.VILLAGER)
                || !BehaviorUtils.entityIsVisible(partner.getBrain(), villager)) {
            cir.setReturnValue(false);
            return;
        }

        // InteractWith selects breeding targets within an eight-block radius;
        // reject stale targets that have moved beyond that preflight range.
        if (villager.distanceToSqr(partner) > 64.0D) {
            cir.setReturnValue(false);
            return;
        }

        if (VillagerBreedingSessions.isActivePair(level, villager, partner)) {
            cir.setReturnValue(true);
            return;
        }

        VillagerStatsAttachment selfStats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        VillagerStatsAttachment partnerStats = partner.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);

        if (VillagerFamilyUtils.areRelated(villager, partner)) {
            VillagerBreedingSessions.rejectPair(level, villager, partner,
                    VillagerBreedingSessions.AbortReason.BLOCKED_RELATED);
            cir.setReturnValue(false);
            return;
        }

        if (selfStats.getHungerLevel() < MINIMUM_HUNGER_TO_BREED
                || partnerStats.getHungerLevel() < MINIMUM_HUNGER_TO_BREED) {
            VillagerBreedingSessions.rejectPair(level, villager, partner,
                    VillagerBreedingSessions.AbortReason.BLOCKED_HUNGER);
            cir.setReturnValue(false);
            return;
        }

        VillagerBreedingSessions.StartResult result = VillagerBreedingSessions.tryStart(level, villager, partner);
        if (result != VillagerBreedingSessions.StartResult.STARTED
                && result != VillagerBreedingSessions.StartResult.ALREADY_ACTIVE) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Prevents vanilla from consuming inventory food through its hidden food
     * counter. Custom hunger is charged after a child is successfully created.
     */
    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/npc/Villager;eatAndDigestFood()V")
    )
    private void skipVanillaFoodConsumption(Villager villager) {
        // Custom hunger cost is applied only after successful birth bookkeeping.
    }

    /** Supplies the bed reserved before courtship; vanilla must not search again. */
    @Inject(method = "takeVacantBed", at = @At("HEAD"), cancellable = true)
    private void useReservedBed(ServerLevel level, Villager villager, CallbackInfoReturnable<Optional<BlockPos>> cir) {
        cir.setReturnValue(VillagerBreedingSessions.getReservedBed(level, villager));
    }

    /** Releases a reservation when vanilla stops the behavior for any interruption. */
    @Inject(method = "stop", at = @At("HEAD"))
    private void onStop(ServerLevel level, Villager villager, long gameTime, CallbackInfo ci) {
        VillagerBreedingSessions.abortFromStop(level, villager);
    }

    /**
     * Vanilla villager breeding does not fire BabyEntitySpawnEvent in 1.21.1, so
     * record family relationships and hunger costs directly after a successful
     * VillagerMakeLove birth.
     */
    @Inject(method = "breed", at = @At("RETURN"))
    private void onBreedReturn(ServerLevel level, Villager parent, Villager partner, CallbackInfoReturnable<Optional<Villager>> cir) {
        Optional<Villager> child = cir.getReturnValue();
        if (child.isPresent()) {
            parent.setAge(BREEDING_COOLDOWN_TICKS);
            partner.setAge(BREEDING_COOLDOWN_TICKS);
            VillagerBreedingSessions.commitBirth(level, parent, partner);
            VillagerBreedingEvents.applySuccessfulVillagerBirth(parent, partner, child.get());
        } else {
            VillagerBreedingSessions.handleSpawnRejected(level, parent, partner);
        }
    }

}
