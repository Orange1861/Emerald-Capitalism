package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.resources.ResourceLocation;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** Applies the configured global movement-speed multiplier to villagers. */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class VillagerMovementEvents {

    private static final ResourceLocation SPEED_MODIFIER_ID = ModIds.id("villager_movement_speed");

    private VillagerMovementEvents() {
    }

    @SubscribeEvent
    public static void onVillagerJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Mob mob)
                || (!(mob instanceof Villager) && !(mob instanceof WanderingTrader))
                || !(event.getLevel() instanceof ServerLevel)) {
            return;
        }

        var villager = mob;

        AttributeInstance movementSpeed = villager.getAttribute(Attributes.MOVEMENT_SPEED);
        if (movementSpeed == null) {
            return;
        }

        // Remove a saved/previous modifier before applying the current config so
        // reloads and chunk unloads never compound the multiplier.
        movementSpeed.removeModifier(SPEED_MODIFIER_ID);
        double multiplier = Config.villagerMovementSpeedMultiplier;
        if (Double.compare(multiplier, 1.0D) == 0) {
            return;
        }

        movementSpeed.addPermanentModifier(new AttributeModifier(
                SPEED_MODIFIER_ID,
                multiplier - 1.0D,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
    }
}
