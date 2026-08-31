package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.LumberjackProductionAttachment;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.util.VillagerNameManager;
import com.orangevillager61.emeraldcapitalism.util.VillagerNameRefreshScheduler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;

@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public class VillagerConversionEvents {

    @SubscribeEvent
    public static void onLivingConversion(LivingConversionEvent.Post event) {
        LivingEntity outcome = event.getOutcome();
        LivingEntity original = event.getEntity();

        if (original instanceof Villager villager && outcome instanceof ZombieVillager zombieVillager) {
            copyAttachmentData(
                villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS),
                zombieVillager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS)
            );
            copyLumberjackProductionData(villager, zombieVillager);
            EmeraldCapitalism.LOGGER.debug("Copied villager data to zombie villager: {}", zombieVillager.getUUID());
        }

        if (original instanceof ZombieVillager zombieVillager && outcome instanceof Villager villager) {
            copyAttachmentData(
                zombieVillager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS),
                villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS)
            );
            copyLumberjackProductionData(zombieVillager, villager);
            VillagerNameManager.applyStoredName(villager);
            VillagerNameRefreshScheduler.requestRefresh(villager);
            EmeraldCapitalism.LOGGER.debug("Copied zombie villager data back to villager: {}", villager.getUUID());
        }
    }

    /** Copies durable villager attachment state across conversion. */
    private static void copyAttachmentData(VillagerStatsAttachment from, VillagerStatsAttachment to) {
        // The assembled display name is derived after conversion from durable naming data.
        to.setVillagerName(null);
        to.setPersonalFirstElement(from.getPersonalFirstElement());
        to.setPersonalSecondElement(from.getPersonalSecondElement());
        to.setNamingVillageId(from.getNamingVillageId());
        to.setSpecialFirstName(from.getSpecialFirstName());

        to.setHungerLevel(from.getHungerLevel());
        to.setLastAteTime(from.getLastAteTime());
        to.setTicksSinceLastHungerDecrease(from.getTicksSinceLastHungerDecrease());
        to.setTicksSinceLastHeal(from.getTicksSinceLastHeal());
        to.setTicksSinceLastStarvationDamage(from.getTicksSinceLastStarvationDamage());
        to.setLastBegTime(from.getLastBegTime());
        to.resetBeggingState();

        to.setEmeraldBalance(from.getEmeraldBalance());

        to.setParent1UUID(from.getParent1UUID());
        to.setParent2UUID(from.getParent2UUID());
        to.setParent1Name(from.getParent1Name());
        to.setParent2Name(from.getParent2Name());

        for (java.util.UUID childUUID : from.getChildrenUUIDs()) {
            to.addChild(childUUID);
        }

        for (java.util.UUID grandparentUUID : from.getGrandparentUUIDs()) {
            to.addGrandparent(grandparentUUID);
        }
    }

    private static void copyLumberjackProductionData(LivingEntity from, LivingEntity to) {
        LumberjackProductionAttachment source = from.getData(
                EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION);
        LumberjackProductionAttachment target = to.getData(
                EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION);
        target.setCharcoalQuota(source.getCharcoalQuota());
    }
}
