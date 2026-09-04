package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.event.VillagerBreedingEvents;
import com.orangevillager61.emeraldcapitalism.event.ZombieVirusEvents;
import com.orangevillager61.emeraldcapitalism.registry.ECAPEffects;
import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import com.orangevillager61.emeraldcapitalism.registry.ECAPPotions;
import com.orangevillager61.emeraldcapitalism.util.EntityDamageUtils;
import com.orangevillager61.emeraldcapitalism.util.ItemDescriptionCompat;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class ZombieVirusGameTests {

    private ZombieVirusGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void phaseOneTransitionsToWitherPhase(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        villager.addEffect(new MobEffectInstance(
                ECAPEffects.ZOMBIE_VIRUS, 1, 0, false, true, true));

        helper.runAfterDelay(2, () -> {
            helper.assertValueEqual(ZombieVirusEvents.getPhase(villager), 2,
                    "Zombkolaps did not enter phase two");
            MobEffectInstance wither = villager.getEffect(MobEffects.WITHER);
            helper.assertTrue(wither != null && wither.getAmplifier() == 2,
                    "phase two did not apply Wither III");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_3x3x3")
    public static void phaseTwoVillagerBecomesNamedZombieVillager(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        villager.setCustomName(net.minecraft.network.chat.Component.literal("Infected Villager"));
        villager.setCustomNameVisible(true);
        villager.addEffect(new MobEffectInstance(
                ECAPEffects.ZOMBIE_VIRUS, MobEffectInstance.INFINITE_DURATION, 0, false, false, true));
        villager.setHealth(1.0F);

        EntityDamageUtils.hurt(villager, helper.getLevel().damageSources().generic(), 100.0F);

        helper.runAfterDelay(2, () -> {
            ZombieVillager replacement = helper.getLevel().getEntitiesOfClass(
                    ZombieVillager.class, villager.getBoundingBox().inflate(2.0D)).stream()
                    .findFirst().orElse(null);
            helper.assertTrue(replacement != null,
                    "a phase-two villager death did not create a zombie villager");
            helper.assertTrue(replacement.getCustomName() != null
                            && replacement.getCustomName().getString().equals("Infected Villager"),
                    "the replacement zombie villager did not retain the villager name");
            MobEffectInstance resistance = replacement.getEffect(MobEffects.DAMAGE_RESISTANCE);
            helper.assertTrue(resistance != null
                            && resistance.getAmplifier() == 3
                            && resistance.getDuration() >= 55,
                    "illness conversion did not grant 3 seconds of Resistance IV");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_3x3x3")
    public static void weaknessAndGoldenAppleCureBothPlaguePhases(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Villager phaseOneVillager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        phaseOneVillager.addEffect(new MobEffectInstance(
                ECAPEffects.ZOMBIE_VIRUS, 200, 0, false, true, true));
        phaseOneVillager.addEffect(new MobEffectInstance(
                MobEffects.WEAKNESS, 200, 0, false, true, true));
        player.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(Items.GOLDEN_APPLE, 2));

        PlayerInteractEvent.EntityInteract phaseOneEvent =
                new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, phaseOneVillager);
        ZombieVirusEvents.onEntityInteract(phaseOneEvent);
        helper.assertTrue(phaseOneEvent.isCanceled()
                        && phaseOneVillager.getEffect(ECAPEffects.ZOMBIE_VIRUS) == null
                        && player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 1,
                "golden apple did not cure phase-one Zombkolaps or consume one apple");

        Villager phaseTwoVillager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
        phaseTwoVillager.addEffect(new MobEffectInstance(
                ECAPEffects.ZOMBIE_VIRUS, MobEffectInstance.INFINITE_DURATION, 0, false, false, true));
        phaseTwoVillager.addEffect(new MobEffectInstance(
                MobEffects.WITHER, MobEffectInstance.INFINITE_DURATION, 2, false, true, false));
        phaseTwoVillager.addEffect(new MobEffectInstance(
                MobEffects.WEAKNESS, 200, 0, false, true, true));

        PlayerInteractEvent.EntityInteract phaseTwoEvent =
                new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, phaseTwoVillager);
        ZombieVirusEvents.onEntityInteract(phaseTwoEvent);
        helper.assertTrue(phaseTwoEvent.isCanceled()
                        && phaseTwoVillager.getEffect(ECAPEffects.ZOMBIE_VIRUS) == null
                        && phaseTwoVillager.getEffect(MobEffects.WITHER) == null
                        && player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(),
                "golden apple did not cure phase-two Zombkolaps or consume the second apple");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void playerGoldenAppleCuresZombiePlague(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.addEffect(new MobEffectInstance(
                ECAPEffects.ZOMBIE_VIRUS, MobEffectInstance.INFINITE_DURATION, 0, false, false, true));
        player.addEffect(new MobEffectInstance(
                MobEffects.WITHER, MobEffectInstance.INFINITE_DURATION, 2, false, true, false));
        player.addEffect(new MobEffectInstance(
                MobEffects.WEAKNESS, 200, 0, false, true, true));

        ItemStack apple = new ItemStack(Items.GOLDEN_APPLE);
        LivingEntityUseItemEvent.Finish event = new LivingEntityUseItemEvent.Finish(
                player, apple, 0, ItemStack.EMPTY);
        ZombieVirusEvents.onItemUseFinished(event);

        helper.assertTrue(player.getEffect(ECAPEffects.ZOMBIE_VIRUS) == null
                        && player.getEffect(MobEffects.WITHER) == null,
                "eating a golden apple with Weakness did not cure the player's Zombkolaps");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void rottenFleshInfectionUsesConfiguredChance(GameTestHelper helper) {
        int previousChance = Config.zombieVirusRottenFleshInfectionChancePercent;
        try {
            Config.zombieVirusRottenFleshInfectionChancePercent = 0;
            Player immunePlayer = helper.makeMockPlayer(GameType.SURVIVAL);
            ZombieVirusEvents.onItemUseFinished(new LivingEntityUseItemEvent.Finish(
                    immunePlayer, new ItemStack(Items.ROTTEN_FLESH), 0, ItemStack.EMPTY));
            helper.assertTrue(immunePlayer.getEffect(ECAPEffects.ZOMBIE_VIRUS) == null,
                    "rotten flesh infected a player when the configured chance was 0%");

            Config.zombieVirusRottenFleshInfectionChancePercent = 100;
            Player infectedPlayer = helper.makeMockPlayer(GameType.SURVIVAL);
            ZombieVirusEvents.onItemUseFinished(new LivingEntityUseItemEvent.Finish(
                    infectedPlayer, new ItemStack(Items.ROTTEN_FLESH), 0, ItemStack.EMPTY));
            helper.assertValueEqual(ZombieVirusEvents.getPhase(infectedPlayer), 1,
                    "rotten flesh did not infect a player when the configured chance was 100%");
        } finally {
            Config.zombieVirusRottenFleshInfectionChancePercent = previousChance;
        }
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void zombieHitReducesPlayerPhaseOneTime(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Zombie zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 2, 1, 1);
        player.addEffect(new MobEffectInstance(
                ECAPEffects.ZOMBIE_VIRUS, 600, 0, false, true, true));

        MobEffectInstance beforeEffect = player.getEffect(ECAPEffects.ZOMBIE_VIRUS);
        int before = beforeEffect == null ? 0 : beforeEffect.getDuration();
        boolean damaged = EntityDamageUtils.hurt(player,
                helper.getLevel().damageSources().mobAttack(zombie), 1.0F);
        MobEffectInstance afterEffect = player.getEffect(ECAPEffects.ZOMBIE_VIRUS);
        int expected = Math.max(1, before - Config.zombieVirusHitTimeReductionSeconds * 20);

        helper.assertTrue(damaged, "the test player did not receive zombie damage");
        helper.assertTrue(afterEffect != null, "the test player's Zombkolaps effect was removed");
        helper.assertValueEqual(ZombieVirusEvents.getPhase(player), 1,
                "the test player's Zombkolaps left phase one");
        helper.assertValueEqual(afterEffect.getDuration(), expected,
                "a zombie hit did not reduce a phase-one player's Zombkolaps time");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void milkRemovesZombiePlagueWithoutCrashing(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.addEffect(new MobEffectInstance(
                ECAPEffects.ZOMBIE_VIRUS, MobEffectInstance.INFINITE_DURATION, 0, false, false, true));
        player.addEffect(new MobEffectInstance(
                MobEffects.WITHER, MobEffectInstance.INFINITE_DURATION, 2, false, true, false));

        player.removeAllEffects();

        helper.assertTrue(player.getEffect(ECAPEffects.ZOMBIE_VIRUS) == null
                        && player.getEffect(MobEffects.WITHER) == null,
                "milk did not remove the Zombkolaps and Wither effects");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void virusZombieAlwaysDropsTransferredEquipment(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        player.addEffect(new MobEffectInstance(
                ECAPEffects.ZOMBIE_VIRUS, MobEffectInstance.INFINITE_DURATION, 0, false, false, true));
        player.setHealth(1.0F);

        EntityDamageUtils.hurt(player, helper.getLevel().damageSources().generic(), 100.0F);

        helper.runAfterDelay(2, () -> {
            Zombie zombie = helper.getLevel().getEntitiesOfClass(
                    Zombie.class, player.getBoundingBox().inflate(2.0D)).stream()
                    .findFirst().orElse(null);
            helper.assertTrue(zombie != null
                            && zombie.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.DIAMOND_SWORD)
                            && zombie.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET),
                    "Zombkolaps conversion did not transfer the player's equipment");

            helper.getLevel().getEntitiesOfClass(ItemEntity.class, zombie.getBoundingBox().inflate(2.0D))
                    .forEach(ItemEntity::discard);
            zombie.setHealth(1.0F);
            EntityDamageUtils.hurt(zombie, helper.getLevel().damageSources().generic(), 100.0F);

            helper.runAfterDelay(2, () -> {
                boolean droppedSword = helper.getLevel().getEntitiesOfClass(
                        ItemEntity.class, zombie.getBoundingBox().inflate(2.0D)).stream()
                        .anyMatch(item -> item.getItem().is(Items.DIAMOND_SWORD));
                boolean droppedHelmet = helper.getLevel().getEntitiesOfClass(
                        ItemEntity.class, zombie.getBoundingBox().inflate(2.0D)).stream()
                        .anyMatch(item -> item.getItem().is(Items.DIAMOND_HELMET));
                helper.assertTrue(droppedSword && droppedHelmet,
                        "Zombkolaps conversion did not guarantee held-item and armor drops");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty_3x3x3")
    public static void zombiePlagueCarriersIgnorePoisonDamage(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        villager.addEffect(new MobEffectInstance(
                ECAPEffects.ZOMBIE_VIRUS, 200, 0, false, true, true));
        villager.addEffect(new MobEffectInstance(
                MobEffects.POISON, 200, 0, false, true, true));
        villager.setHealth(villager.getMaxHealth());
        float villagerHealth = villager.getHealth();
        boolean villagerHurt = EntityDamageUtils.hurt(villager,
                helper.getLevel().damageSources().magic(), 1.0F);

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.addEffect(new MobEffectInstance(
                ECAPEffects.ZOMBIE_VIRUS, 200, 0, false, true, true));
        player.addEffect(new MobEffectInstance(
                MobEffects.POISON, 200, 0, false, true, true));
        player.setHealth(player.getMaxHealth());
        float playerHealth = player.getHealth();
        boolean playerHurt = EntityDamageUtils.hurt(player,
                helper.getLevel().damageSources().magic(), 1.0F);

        helper.assertTrue(!villagerHurt && villager.getHealth() == villagerHealth
                        && !playerHurt && player.getHealth() == playerHealth,
                "Zombkolaps carriers took poison damage");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void infectedVillagerParentsPassPhaseOneToChild(GameTestHelper helper) {
        Villager parentOne = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        Villager parentTwo = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
        Villager child = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 3, 1, 1);
        parentOne.addEffect(new MobEffectInstance(
                ECAPEffects.ZOMBIE_VIRUS, MobEffectInstance.INFINITE_DURATION, 0, false, false, true));
        parentTwo.addEffect(new MobEffectInstance(
                ECAPEffects.ZOMBIE_VIRUS, 200, 0, false, true, true));

        VillagerBreedingEvents.applySuccessfulVillagerBirth(parentOne, parentTwo, child);

        MobEffectInstance inherited = child.getEffect(ECAPEffects.ZOMBIE_VIRUS);
        helper.assertTrue(inherited != null
                        && ZombieVirusEvents.getPhase(child) == 1
                        && !inherited.isInfiniteDuration()
                        && inherited.getDuration() > 0,
                "infected villager parents did not pass phase-one Zombkolaps to their child");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void zombiePlaguePotionDefinitionsRepresentBothPhases(GameTestHelper helper) {
        MobEffectInstance phaseOne = ECAPPotions.ZOMBIE_VIRUS_PHASE_ONE.get().getEffects().getFirst();
        MobEffectInstance phaseTwo = ECAPPotions.ZOMBIE_VIRUS_PHASE_TWO.get().getEffects().getFirst();
        ItemStack phaseOneSplash = PotionContents.createItemStack(
                Items.SPLASH_POTION, ECAPPotions.ZOMBIE_VIRUS_PHASE_ONE);
        ItemStack phaseTwoSplash = PotionContents.createItemStack(
                Items.SPLASH_POTION, ECAPPotions.ZOMBIE_VIRUS_PHASE_TWO);
        ItemStack phaseOnePotion = PotionContents.createItemStack(
                Items.POTION, ECAPPotions.ZOMBIE_VIRUS_PHASE_ONE);
        ItemStack phaseTwoPotion = PotionContents.createItemStack(
                Items.POTION, ECAPPotions.ZOMBIE_VIRUS_PHASE_TWO);

        helper.assertTrue(phaseOne.getEffect().equals(ECAPEffects.ZOMBIE_VIRUS)
                        && !phaseOne.isInfiniteDuration()
                        && phaseOne.getDuration() > 0,
                "phase-one potion does not contain a finite Zombkolaps effect");
        helper.assertTrue(phaseTwo.getEffect().equals(ECAPEffects.ZOMBIE_VIRUS)
                        && phaseTwo.isInfiniteDuration(),
                "phase-two potion does not contain an infinite Zombkolaps effect");
        helper.assertTrue(phaseOneSplash.is(Items.SPLASH_POTION)
                        && phaseTwoSplash.is(Items.SPLASH_POTION),
                "Zombkolaps potions did not produce splash potion variants");
        helper.assertTrue(ItemDescriptionCompat.get(phaseOnePotion).equals(
                                "item.minecraft.potion.effect.zombie_virus_phase_one")
                        && ItemDescriptionCompat.get(phaseTwoPotion).equals(
                                "item.minecraft.potion.effect.zombie_virus_phase_two")
                        && ItemDescriptionCompat.get(phaseOneSplash).equals(
                                "item.minecraft.splash_potion.effect.zombie_virus_phase_one")
                        && ItemDescriptionCompat.get(phaseTwoSplash).equals(
                                "item.minecraft.splash_potion.effect.zombie_virus_phase_two"),
                "Zombkolaps potion names did not resolve to localized translation keys");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void zombiePlagueBrewingSupportsNormalAndSplashBottles(GameTestHelper helper) {
        PotionBrewing brewing = PotionBrewing.bootstrap(
            FeatureFlags.VANILLA_SET, helper.getLevel().registryAccess());
        ItemStack rottenFlesh = new ItemStack(Items.ROTTEN_FLESH);
        ItemStack thickPotion = PotionContents.createItemStack(Items.POTION, Potions.THICK);
        ItemStack thickSplashPotion = PotionContents.createItemStack(
                Items.SPLASH_POTION, Potions.THICK);

        ItemStack phaseOnePotion = brewing.mix(rottenFlesh, thickPotion);
        ItemStack compactedRottenFlesh = new ItemStack(ECAPItems.COMPACTED_ROTTEN_FLESH.get());
        ItemStack phaseTwoPotion = brewing.mix(compactedRottenFlesh, phaseOnePotion);
        ItemStack phaseOneSplashPotion = brewing.mix(rottenFlesh, thickSplashPotion);
        ItemStack phaseTwoSplashPotion = brewing.mix(compactedRottenFlesh, phaseOneSplashPotion);

        helper.assertTrue(hasPotion(phaseOnePotion, ECAPPotions.ZOMBIE_VIRUS_PHASE_ONE),
                "Zombkolaps phase-one brewing failed: " + phaseOnePotion);
        helper.assertTrue(hasPotion(phaseTwoPotion, ECAPPotions.ZOMBIE_VIRUS_PHASE_TWO),
                "Zombkolaps phase-two brewing failed: " + phaseTwoPotion);
        helper.assertTrue(phaseOneSplashPotion.is(Items.SPLASH_POTION)
                        && phaseTwoSplashPotion.is(Items.SPLASH_POTION)
                        && hasPotion(phaseOneSplashPotion, ECAPPotions.ZOMBIE_VIRUS_PHASE_ONE)
                        && hasPotion(phaseTwoSplashPotion, ECAPPotions.ZOMBIE_VIRUS_PHASE_TWO),
                "Zombkolaps splash potion brewing progression is missing");
        helper.succeed();
    }

    private static boolean hasPotion(ItemStack stack,
                                     net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> potion) {
        return stack.getOrDefault(
                net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                PotionContents.EMPTY).is(potion);
    }
}
