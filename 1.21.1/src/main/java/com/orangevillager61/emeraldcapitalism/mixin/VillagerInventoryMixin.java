package com.orangevillager61.emeraldcapitalism.mixin;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ensures villagers allocate a larger inventory container.
 */
@Mixin(AbstractVillager.class)
public class VillagerInventoryMixin {

    private static final int VILLAGER_INVENTORY_SIZE = 18;

    @Mutable
    @Shadow
    @Final
    private SimpleContainer inventory;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void emeraldcapitalism$resizeInventory(CallbackInfo ci) {
        if ((Object) this instanceof Villager) {
            this.inventory = new SimpleContainer(VILLAGER_INVENTORY_SIZE);
        }
    }

}
