package com.orangevillager61.emeraldcapitalism.item;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.tags.StructureTags;
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
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.Optional;

/** A single-use locator that becomes a filled map centered on the nearest village. */
public final class VillageMapItem extends Item {

    private static final int SEARCH_RADIUS_CHUNKS = 100;

    public VillageMapItem(Properties properties) {
        super(properties);
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
        LocatedVillage locatedVillage = findNearestVillage(serverLevel, origin).orElse(null);
        if (locatedVillage == null) {
            player.displayClientMessage(Component.translatable("item.emeraldcapitalism.village_map.not_found"), true);
//? if >=1.21.4 {
            return InteractionResult.FAIL;
//?} else {
/*            return InteractionResultHolder.fail(ticket);
 *///?}
        }

        BlockPos village = locatedVillage.position();
        ItemStack map = MapItem.create(serverLevel, village.getX(), village.getZ(),
                (byte) 2, true, true);
        MapItemSavedData mapData = MapItem.getSavedData(map, serverLevel);
        if (mapData != null) {
            MapItemSavedData.addTargetDecoration(map, village, "village",
                    villageDecoration(locatedVillage.structure()));
        }
        map.set(DataComponents.CUSTOM_NAME, getName(ticket));

        replaceTicketWithMap(player, hand, ticket, map);
        player.awardStat(Stats.ITEM_USED.get(this));
//? if >=1.21.4 {
        return InteractionResult.CONSUME;
//?} else {
/*        return InteractionResultHolder.consume(map);
 *///?}
    }

    static void replaceTicketWithMap(Player player, InteractionHand hand,
                                     ItemStack ticket, ItemStack map) {
        if (player.getAbilities().instabuild) {
            // Keep the locator available in creative while putting the result in the hand
            // that was used. The normal use pipeline applies the returned stack to that hand.
            ItemStack ticketCopy = ticket.copy();
            if (!player.getInventory().add(ticketCopy)) {
                player.drop(ticketCopy, false);
            }
        }
        player.setItemInHand(hand, map);
    }

    private static Optional<LocatedVillage> findNearestVillage(ServerLevel level, BlockPos origin) {
        Registry<Structure> structureRegistry =
                com.orangevillager61.emeraldcapitalism.util.RegistryAccessCompat.get(
                        level.registryAccess(), Registries.STRUCTURE);
        Optional<HolderSet.Named<Structure>> villageStructures =
                com.orangevillager61.emeraldcapitalism.util.RegistryAccessCompat.getTag(
                        structureRegistry, StructureTags.VILLAGE);
        if (villageStructures.isEmpty()) {
            return Optional.empty();
        }

        Pair<BlockPos, Holder<Structure>> result = level.getChunkSource()
                .getGenerator()
                .findNearestMapStructure(
                        level,
                        villageStructures.get(),
                        origin,
                        SEARCH_RADIUS_CHUNKS,
                        false
                );
        return result == null
                ? Optional.empty()
                : Optional.of(new LocatedVillage(result.getFirst(), result.getSecond()));
    }

    private static Holder<MapDecorationType> villageDecoration(
            Holder<Structure> structure) {
        String path = structure.unwrapKey()
                .map(key -> key.location().getPath())
                .orElse("");
        return switch (path) {
            case "village_desert" -> MapDecorationTypes.DESERT_VILLAGE;
            case "village_savanna" -> MapDecorationTypes.SAVANNA_VILLAGE;
            case "village_snowy" -> MapDecorationTypes.SNOWY_VILLAGE;
            case "village_taiga" -> MapDecorationTypes.TAIGA_VILLAGE;
            case "village_plains" -> MapDecorationTypes.PLAINS_VILLAGE;
            default -> MapDecorationTypes.RED_X;
        };
    }

    private record LocatedVillage(BlockPos position, Holder<Structure> structure) {
    }
}
