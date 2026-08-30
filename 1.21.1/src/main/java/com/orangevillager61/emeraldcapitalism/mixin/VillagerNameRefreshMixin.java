package com.orangevillager61.emeraldcapitalism.mixin;

import com.orangevillager61.emeraldcapitalism.util.VillagerNameRefreshScheduler;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Refreshes generated villager names at vanilla's profession and age mutation boundaries. */
@Mixin(Villager.class)
public class VillagerNameRefreshMixin {
    @Inject(method = "setVillagerData", at = @At("HEAD"))
    private void emeraldcapitalism$villagerDataChanged(VillagerData newData, CallbackInfo ci) {
        Villager villager = (Villager) (Object) this;
        if (villager.getVillagerData().getProfession() != newData.getProfession()) {
            VillagerNameRefreshScheduler.requestRefresh(villager);
        }
    }

    @Inject(method = "ageBoundaryReached", at = @At("TAIL"))
    private void emeraldcapitalism$ageBoundaryReached(CallbackInfo ci) {
        VillagerNameRefreshScheduler.requestRefresh((Villager) (Object) this);
    }
}
