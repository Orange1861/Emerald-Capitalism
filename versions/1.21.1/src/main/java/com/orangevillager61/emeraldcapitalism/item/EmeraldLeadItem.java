package com.orangevillager61.emeraldcapitalism.item;

import com.orangevillager61.emeraldcapitalism.registry.ECAPEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.LeadItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * A lead that can be attached to zombie villagers and villagers carrying Zombkolaps.
 */
public class EmeraldLeadItem extends LeadItem {

    public EmeraldLeadItem(Properties properties) {
        super(properties);
    }

    public static boolean isValidTarget(LivingEntity target) {
        return target instanceof Leashable
                && (target instanceof ZombieVillager
                || target instanceof Villager
                && target.getEffect(ECAPEffects.ZOMBIE_VIRUS) != null);
    }

    @Override
    public InteractionResult interactLivingEntity(
            ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!isValidTarget(target)) {
            return InteractionResult.PASS;
        }

        if (!player.level().isClientSide) {
            ((Leashable) target).setLeashedTo(player, true);
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos clickedPos = context.getClickedPos();
        Player player = context.getPlayer();

        if (!level.getBlockState(clickedPos).is(BlockTags.FENCES)) {
            return InteractionResult.PASS;
        }

        if (!level.isClientSide && player != null) {
            bindEligibleVillagersToFence(player, level, clickedPos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void bindEligibleVillagersToFence(Player player, Level level, BlockPos fencePos) {
        List<Leashable> leashables = LeadItem.leashableInArea(
                level,
                fencePos,
                leashable -> leashable instanceof LivingEntity livingEntity
                        && isValidTarget(livingEntity)
                        && leashable.getLeashHolder() == player
        );
        if (leashables.isEmpty()) {
            return;
        }

        LeashFenceKnotEntity knot = LeashFenceKnotEntity.getOrCreateKnot(level, fencePos);
        knot.playPlacementSound();
        for (Leashable leashable : leashables) {
            leashable.setLeashedTo(knot, true);
        }
    }
}
