package com.orangevillager61.emeraldcapitalism.mixin;

import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Uses the mod's hunger system as the sole villager breeding-food gate. */
@Mixin(Villager.class)
public class VillagerBreedingEligibilityMixin {
    @Inject(method = "canBreed", at = @At("HEAD"), cancellable = true)
    private void emeraldcapitalism$canBreed(CallbackInfoReturnable<Boolean> cir) {
        Villager villager = (Villager) (Object) this;
        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        cir.setReturnValue(villager.getAge() == 0 && !villager.isSleeping()
                && stats.getHungerLevel() >= VillagerBreedingSessions.MINIMUM_HUNGER);
    }
}
