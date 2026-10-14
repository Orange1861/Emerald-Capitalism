package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.behavior.VillagerBehaviorRegistry;
import com.orangevillager61.emeraldcapitalism.entity.ai.VillagerLadderClaims;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestManager;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.minecraft.server.level.ServerLevel;

@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class VillagerLadderClaimEvents {
    private VillagerLadderClaimEvents() {
    }

    @SubscribeEvent
    public static void onLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof Villager villager) {
            VillagerLadderClaims.releaseAll(villager.getUUID());
            VillagerBehaviorRegistry.release(villager);
            MovementRequestManager.release(villager);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        VillagerLadderClaims.clear();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            VillagerLadderClaims.releaseDimension(serverLevel.dimension());
        }
    }
}
