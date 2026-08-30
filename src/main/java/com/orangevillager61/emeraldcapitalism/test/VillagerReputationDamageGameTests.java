package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.Config;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.ReputationEventType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = "emeraldcapitalism")
public final class VillagerReputationDamageGameTests {

    private static final float[] DAMAGE_AMOUNTS = {0.01F, 2.0F, 4.0F, 5.0F};
    private static volatile java.util.UUID cancelNextDamageFor;

    private VillagerReputationDamageGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void playerDamageUsesOriginalAmountBeforeReputation(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);

        for (int i = 0; i < DAMAGE_AMOUNTS.length; i++) {
            Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1 + i, 1, 1);
            float damage = DAMAGE_AMOUNTS[i];
            boolean damaged = villager.hurt(
                    helper.getLevel().damageSources().playerAttack(player), damage);

            helper.assertTrue(damaged, "player damage was not applied for amount " + damage);
            helper.assertValueEqual(villager.getPlayerReputation(player), expectedReputation(damage),
                    "player damage did not produce proportional reputation for amount " + damage);

            villager.onReputationEventFrom(ReputationEventType.TRADE, player);
            helper.assertValueEqual(villager.getPlayerReputation(player), expectedReputation(damage) + 2,
                    "non-damage gossip was modified after player damage amount " + damage);
        }

        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void nonPlayerAndEnvironmentalDamageDoNotArmPlayerPath(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);

        Villager environmentalTarget = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        environmentalTarget.hurt(helper.getLevel().damageSources().generic(), 2.0F);
        environmentalTarget.onReputationEventFrom(ReputationEventType.VILLAGER_HURT, player);
        helper.assertValueEqual(environmentalTarget.getPlayerReputation(player), -25,
                "environmental damage armed the player-damage reputation path");

        Villager mobTarget = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 3, 1, 1);
        Zombie attacker = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 4, 1, 1);
        mobTarget.hurt(helper.getLevel().damageSources().mobAttack(attacker), 2.0F);
        mobTarget.onReputationEventFrom(ReputationEventType.VILLAGER_HURT, player);
        helper.assertValueEqual(mobTarget.getPlayerReputation(player), -25,
                "non-player damage armed the player-damage reputation path");

        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void canceledDamageDoesNotLeakIntoLaterGossip(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);

        cancelNextDamageFor = villager.getUUID();
        boolean damaged;
        try {
            damaged = villager.hurt(
                    helper.getLevel().damageSources().playerAttack(player), 2.0F);
        } finally {
            cancelNextDamageFor = null;
        }

        helper.assertFalse(damaged, "the focused cancellation did not cancel incoming damage");
        villager.onReputationEventFrom(ReputationEventType.VILLAGER_HURT, player);
        helper.assertValueEqual(villager.getPlayerReputation(player), -25,
                "canceled damage leaked into later non-damage gossip");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void ineffectiveDamagePreservesExistingReputationPolicy(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        villager.setInvulnerable(true);

        boolean damaged = villager.hurt(
                helper.getLevel().damageSources().playerAttack(player), 2.0F);

        helper.assertFalse(damaged, "invulnerable player damage was not rejected by the damage path");
        helper.assertValueEqual(villager.getHealth(), villager.getMaxHealth(),
                "ineffective player damage changed villager health");
        villager.onReputationEventFrom(ReputationEventType.VILLAGER_HURT, player);
        helper.assertValueEqual(villager.getPlayerReputation(player), -25,
                "ineffective player damage changed its existing reputation policy");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void disabledConfigPreservesVanillaReputation(GameTestHelper helper) {
        helper.runAfterDelay(40, () -> {
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
            boolean previous = Config.proportionalVillagerReputation;
            Config.proportionalVillagerReputation = false;
            try {
                villager.hurt(
                        helper.getLevel().damageSources().playerAttack(player), 2.0F);
            } finally {
                Config.proportionalVillagerReputation = previous;
            }

            helper.assertValueEqual(villager.getPlayerReputation(player), -25,
                    "disabled proportional reputation did not preserve vanilla loss");
            helper.succeed();
        });
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void cancelArmedDamage(LivingIncomingDamageEvent event) {
        java.util.UUID target = cancelNextDamageFor;
        if (target != null && target.equals(event.getEntity().getUUID())) {
            cancelNextDamageFor = null;
            event.setCanceled(true);
        }
    }

    private static int expectedReputation(float damage) {
        int loss = Math.round(damage * 5.0F);
        return -Math.max(1, loss);
    }
}
