package com.orangevillager61.emeraldcapitalism.mixin;

import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.util.EmeraldConsolidationUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps villager emerald balances in sync with successful item drops. */
@Mixin(Entity.class)
public class VillagerDropAccountingMixin {

    /**
     * Entity owns both spawnAtLocation overloads; the single-argument method
     * delegates here, so one return hook accounts for each spawned stack once.
     * The direct Villager API is part of the accounting contract, so limiting
     * this to the mod's current goal call sites would miss external callers.
     */
    @Inject(
            method = "spawnAtLocation(Lnet/minecraft/world/item/ItemStack;F)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("RETURN"),
            require = 1)
    @SuppressWarnings("unused")
    private void recordEmeraldDrop(ItemStack stack, float yOffset, CallbackInfoReturnable<ItemEntity> cir) {
        Entity entity = (Entity) (Object) this;
        if (!(entity instanceof Villager villager) || villager.level().isClientSide()) {
            return;
        }

        ItemEntity droppedItem = cir.getReturnValue();
        if (droppedItem == null) {
            return;
        }

        int emeraldValue = EmeraldConsolidationUtils.countEmeraldValue(droppedItem.getItem());
        if (emeraldValue <= 0) {
            return;
        }

        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        stats.subtractEmeralds(emeraldValue);
    }

}
