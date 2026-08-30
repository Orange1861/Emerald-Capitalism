package com.orangevillager61.emeraldcapitalism.mixin;

import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.util.EmeraldConsolidationUtils;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Credits only the emerald value that vanilla actually inserted during a pickup. */
@Mixin(Villager.class)
public class VillagerPickupAccountingMixin {
    @Unique private int emeraldcapitalism$pendingPickupEmeralds;
    @Unique private int emeraldcapitalism$inventoryEmeraldsBeforePickup;

    @Inject(method = "pickUpItem", at = @At("HEAD"))
    private void emeraldcapitalism$beforePickup(ItemEntity itemEntity, CallbackInfo ci) {
        ItemStack stack = itemEntity.getItem();
        emeraldcapitalism$pendingPickupEmeralds = EmeraldConsolidationUtils.countEmeraldValue(stack);
        emeraldcapitalism$inventoryEmeraldsBeforePickup = EmeraldConsolidationUtils.countEmeraldValue(
                ((Villager) (Object) this).getInventory());
    }

    @Inject(method = "pickUpItem", at = @At("TAIL"))
    private void emeraldcapitalism$afterPickup(ItemEntity itemEntity, CallbackInfo ci) {
        Villager villager = (Villager) (Object) this;
        int attempted = emeraldcapitalism$pendingPickupEmeralds;
        int before = emeraldcapitalism$inventoryEmeraldsBeforePickup;
        emeraldcapitalism$pendingPickupEmeralds = 0;
        emeraldcapitalism$inventoryEmeraldsBeforePickup = 0;
        if (villager.level().isClientSide() || attempted <= 0) {
            return;
        }
        int inserted = Math.max(0, EmeraldConsolidationUtils.countEmeraldValue(villager.getInventory()) - before);
        int collected = Math.min(attempted, inserted);
        if (collected > 0) {
            EmeraldConsolidationUtils.consolidateEmeralds(villager.getInventory());
            VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
            stats.addEmeralds(collected);
        }
    }
}
