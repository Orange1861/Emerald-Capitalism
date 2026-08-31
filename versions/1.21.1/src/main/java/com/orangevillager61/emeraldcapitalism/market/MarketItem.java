package com.orangevillager61.emeraldcapitalism.market;

import com.orangevillager61.emeraldcapitalism.market.MarketItemConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/** A validated resource-backed market item and its resolved Minecraft item. */
public record MarketItem(MarketItemConfig config, ResourceLocation itemId, Item item) {
}
