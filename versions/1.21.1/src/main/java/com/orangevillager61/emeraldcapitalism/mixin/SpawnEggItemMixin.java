package com.orangevillager61.emeraldcapitalism.mixin;

import com.orangevillager61.emeraldcapitalism.event.VillagerSpawnEvents;
import com.orangevillager61.emeraldcapitalism.util.VillagerNameManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Preserves the exact parent for the direct offspring spawn-egg path.
 *
 * <p>The 1.21.1 public spawn events expose the child but not the parent passed
 * to {@code SpawnEggItem.spawnOffspringFromSpawnEgg}. Entity-join and nearby
 * parent lookup are therefore only fallbacks: they cannot prove which adult
 * was interacted with. This return hook runs after vanilla has successfully
 * added and consumed the child, so it does not change vanilla random usage or
 * the success/failure result.</p>
 */
@Mixin(SpawnEggItem.class)
public class SpawnEggItemMixin {

    @SuppressWarnings("unused") // Mixin injection signature requires CallbackInfoReturnable even if unused.
    @Inject(method = "spawnOffspringFromSpawnEgg", at = @At("RETURN"))
    private void onSpawnOffspringFromSpawnEgg(Player player, Mob parent, EntityType<? extends Mob> entityType,
                                              ServerLevel level, Vec3 pos, ItemStack stack,
                                              CallbackInfoReturnable<Optional<Mob>> cir) {
        Optional<Mob> spawned = cir.getReturnValue();
        if (spawned == null || spawned.isEmpty()) {
            return;
        }

        Mob child = spawned.get();
        if (!(child instanceof Villager babyVillager) || !(parent instanceof Villager parentVillager)) {
            return;
        }

        VillagerNameManager.assignNameIfNeeded(parentVillager);
        VillagerNameManager.assignNameIfNeeded(babyVillager);
        VillagerSpawnEvents.assignParentsFromSpawnEgg(babyVillager, parentVillager);
    }
}
