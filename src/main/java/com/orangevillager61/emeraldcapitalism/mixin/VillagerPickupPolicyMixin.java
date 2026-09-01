package com.orangevillager61.emeraldcapitalism.mixin;

import com.orangevillager61.emeraldcapitalism.util.VillagerInventoryPolicy;
import com.orangevillager61.emeraldcapitalism.util.VillagerSkrimisherItemPool;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Enforces accepted items and preserves the final three inventory slots for food. */
@Mixin(Villager.class)
public class VillagerPickupPolicyMixin {
    private static final int RESERVED_FOOD_SLOTS = 3;

//? if >=1.21.4 {
    @Inject(method = "wantsToPickUp", at = @At("HEAD"), cancellable = true)
    private void emeraldcapitalism$wantsToPickUp(net.minecraft.server.level.ServerLevel level,
                                                 ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
//?} else {
/*    @Inject(method = "wantsToPickUp", at = @At("HEAD"), cancellable = true)
    private void emeraldcapitalism$wantsToPickUp(ItemStack stack,
                                                 CallbackInfoReturnable<Boolean> cir) {
 *///?}
        Villager villager = (Villager) (Object) this;
        SimpleContainer inventory = villager.getInventory();
        if (!VillagerSkrimisherItemPool.contains(stack)
                && !VillagerInventoryPolicy.isReservedForVillager(villager, stack)
                && !VillagerInventoryPolicy.isProfessionWorkItem(villager, stack)) {
            cir.setReturnValue(false);
            return;
        }
        cir.setReturnValue(stack.get(DataComponents.FOOD) != null
                ? hasSpace(inventory, inventory.getContainerSize(), stack)
                : hasSpace(inventory, Math.max(0, inventory.getContainerSize() - RESERVED_FOOD_SLOTS), stack));
    }

    @Unique
    private static boolean hasSpace(SimpleContainer inventory, int limit, ItemStack stack) {
        for (int slot = 0; slot < limit; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing.isEmpty() || ItemStack.isSameItemSameComponents(existing, stack)
                    && existing.getCount() < existing.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }
}
