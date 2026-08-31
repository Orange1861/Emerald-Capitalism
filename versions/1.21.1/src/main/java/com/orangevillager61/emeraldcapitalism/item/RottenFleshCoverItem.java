package com.orangevillager61.emeraldcapitalism.item;

import com.orangevillager61.emeraldcapitalism.registry.ECAPEffects;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** A consumable cover that masks a living entity from zombies for 30 seconds. */
public final class RottenFleshCoverItem extends Item {

    public static final int ZOMBIE_SMELL_DURATION_TICKS = 30 * 20;

    public RottenFleshCoverItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResultHolder.success(stack);
        }

        applyZombieSmell(player);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        player.awardStat(Stats.ITEM_USED.get(this));
        return InteractionResultHolder.consume(stack);
    }

    public static void applyZombieSmell(LivingEntity entity) {
        entity.addEffect(new MobEffectInstance(
                ECAPEffects.ZOMBIE_SMELL,
                ZOMBIE_SMELL_DURATION_TICKS,
                0,
                false,
                true,
                true));
    }
}
