package com.orangevillager61.emeraldcapitalism.item;

import com.orangevillager61.emeraldcapitalism.world.structure.AbandonedVaultLocator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
//? if >=1.21.4 {
import net.minecraft.world.InteractionResult;
//?} else {
/*import net.minecraft.world.InteractionResultHolder;
 *///?}
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;

import java.util.Optional;

/** A one-use locator ticket that becomes a normal filled map on the server. */
public final class AbandonedVaultMapItem extends Item {
    public enum Target {
        NEAREST,
        SECOND_NEAREST
    }

    private final Target target;

    public AbandonedVaultMapItem(Target target, Properties properties) {
        super(properties);
        this.target = target;
    }

    public Target target() {
        return target;
    }

    @Override
//? if >=1.21.4 {
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
//?} else {
/*    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
 *///?}
        ItemStack ticket = player.getItemInHand(hand);
        if (level.isClientSide()) {
//? if >=1.21.4 {
            return InteractionResult.SUCCESS;
//?} else {
/*            return InteractionResultHolder.success(ticket);
 *///?}
        }

        ServerLevel serverLevel = (ServerLevel) level;
        BlockPos origin = BlockPos.containing(player.getPosition(1.0F));
        Optional<BlockPos> targetPosition = target == Target.SECOND_NEAREST
                ? AbandonedVaultLocator.findSecondNearest(serverLevel, origin)
                : AbandonedVaultLocator.findNearest(serverLevel, origin);
        if (targetPosition.isEmpty()) {
            player.displayClientMessage(Component.literal(target == Target.SECOND_NEAREST
                    ? "No second abandoned vault could be found nearby."
                    : "No abandoned vault could be found nearby."), true);
//? if >=1.21.4 {
            return InteractionResult.FAIL;
//?} else {
/*            return InteractionResultHolder.fail(ticket);
 *///?}
        }

        BlockPos vault = targetPosition.get();
        ItemStack map = MapItem.create(serverLevel, vault.getX(), vault.getZ(),
                (byte) 2, true, true);
        MapItemSavedData mapData = MapItem.getSavedData(map, serverLevel);
        if (mapData != null) {
            mapData.addTargetDecoration(map, vault, "abandoned_vault",
                    MapDecorationTypes.RED_X);
        }
        map.set(DataComponents.CUSTOM_NAME, getName(ticket));

        if (!player.getAbilities().instabuild) {
            ticket.shrink(1);
        }
        if (!player.getInventory().add(map)) {
            player.drop(map, false);
        }
        player.awardStat(Stats.ITEM_USED.get(this));
//? if >=1.21.4 {
        return InteractionResult.CONSUME;
//?} else {
/*        return InteractionResultHolder.consume(ticket);
 *///?}
    }
}
