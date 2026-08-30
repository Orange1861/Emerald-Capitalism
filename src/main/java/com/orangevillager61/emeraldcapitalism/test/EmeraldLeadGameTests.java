package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.event.EmeraldLeadEvents;
import com.orangevillager61.emeraldcapitalism.item.EmeraldLeadItem;
import com.orangevillager61.emeraldcapitalism.registry.ECAPEffects;
import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class EmeraldLeadGameTests {

    private EmeraldLeadGameTests() {}

    @GameTest(template = "empty_3x3x3")
    public static void emeraldLeadLeashesZombieVillagersAndInfectedVillagers(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        EmeraldLeadItem item = ECAPItems.EMERALD_LEAD.get();
        ItemStack stack = new ItemStack(item);
        ZombieVillager zombieVillager = helper.spawnWithNoFreeWill(EntityType.ZOMBIE_VILLAGER, 1, 1, 1);
        Villager infectedVillager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
        infectedVillager.addEffect(new MobEffectInstance(
                ECAPEffects.ZOMBIE_VIRUS, 200, 0, false, true, true));
        Villager healthyVillager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 2);
        Skeleton skeleton = helper.spawnWithNoFreeWill(EntityType.SKELETON, 2, 1, 2);

        InteractionResult zombieResult = item.interactLivingEntity(
                stack, player, zombieVillager, InteractionHand.MAIN_HAND);
        if (!zombieResult.consumesAction()
                || zombieVillager.getLeashHolder() != player) {
            helper.fail("Emerald Lead did not leash a zombie villager: result=" + zombieResult
                    + ", leashed=" + zombieVillager.isLeashed()
                    + ", holder=" + zombieVillager.getLeashHolder()
                    + ", player=" + player);
            return;
        }

        InteractionResult infectedResult = item.interactLivingEntity(
                stack, player, infectedVillager, InteractionHand.MAIN_HAND);
        if (!infectedResult.consumesAction()
                || infectedVillager.getLeashHolder() != player) {
            helper.fail("Emerald Lead did not leash a villager with Zombie Plague");
            return;
        }

        if (item.interactLivingEntity(stack, player, healthyVillager, InteractionHand.MAIN_HAND)
                != InteractionResult.PASS
                || healthyVillager.isLeashed()) {
            helper.fail("Emerald Lead affected a healthy villager");
            return;
        }

        if (item.interactLivingEntity(stack, player, skeleton, InteractionHand.MAIN_HAND)
                != InteractionResult.PASS
                || skeleton.isLeashed()) {
            helper.fail("Emerald Lead affected a non-zombie-villager entity");
            return;
        }

        Leashable leashable = skeleton;
        leashable.setLeashedTo(player, true);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        PlayerInteractEvent.EntityInteract event =
                new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, skeleton);
        EmeraldLeadEvents.onEntityInteract(event);
        if (!event.isCanceled() || skeleton.getLeashHolder() != player) {
            helper.fail("Emerald Lead did not block vanilla interaction with another leashed entity");
            return;
        }

        PlayerInteractEvent.EntityInteract infectedEvent =
                new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, infectedVillager);
        EmeraldLeadEvents.onEntityInteract(infectedEvent);
        if (infectedEvent.isCanceled()) {
            helper.fail("Emerald Lead event guard blocked an infected villager");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void emeraldLeadAttachesOnlyZombieVillagersToFences(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        EmeraldLeadItem item = ECAPItems.EMERALD_LEAD.get();
        ItemStack stack = new ItemStack(item);
        ZombieVillager zombieVillager = helper.spawnWithNoFreeWill(EntityType.ZOMBIE_VILLAGER, 1, 1, 1);
        Villager infectedVillager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
        infectedVillager.addEffect(new MobEffectInstance(
                ECAPEffects.ZOMBIE_VIRUS, 200, 0, false, true, true));
        Skeleton skeleton = helper.spawnWithNoFreeWill(EntityType.SKELETON, 2, 1, 2);
        item.interactLivingEntity(stack, player, zombieVillager, InteractionHand.MAIN_HAND);
        item.interactLivingEntity(stack, player, infectedVillager, InteractionHand.MAIN_HAND);
        ((Leashable) skeleton).setLeashedTo(player, true);

        BlockPos fencePos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlock(fencePos, Blocks.OAK_FENCE.defaultBlockState(), 3);
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(fencePos), Direction.UP, fencePos, false);
        InteractionResult result = item.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));

        if (!result.consumesAction()
                || !(zombieVillager.getLeashHolder() instanceof net.minecraft.world.entity.decoration.LeashFenceKnotEntity)
                || !(infectedVillager.getLeashHolder() instanceof net.minecraft.world.entity.decoration.LeashFenceKnotEntity)
                || skeleton.getLeashHolder() != player) {
            helper.fail("Emerald Lead did not bind only valid villager targets: result=" + result
                    + ", zombieLeashed=" + zombieVillager.isLeashed()
                    + ", zombieHolder=" + zombieVillager.getLeashHolder()
                    + ", infectedLeashed=" + infectedVillager.isLeashed()
                    + ", infectedHolder=" + infectedVillager.getLeashHolder()
                    + ", skeletonLeashed=" + skeleton.isLeashed()
                    + ", skeletonHolder=" + skeleton.getLeashHolder());
            return;
        }

        helper.succeed();
    }
}
