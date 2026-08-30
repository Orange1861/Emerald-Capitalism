package com.orangevillager61.emeraldcapitalism.registry;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.item.AbandonedVaultMapItem;
import com.orangevillager61.emeraldcapitalism.item.EmeraldLeadItem;
import com.orangevillager61.emeraldcapitalism.item.RottenFleshCoverItem;
import com.orangevillager61.emeraldcapitalism.item.VillageMapItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.bus.api.IEventBus;

public final class ECAPItems {

    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(EmeraldCapitalism.MODID);

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }

    // Block Items
    public static final DeferredItem<BlockItem> EMERALD_CHEST = ITEMS.registerSimpleBlockItem(
            "emerald_chest",
            ECAPBlocks.EMERALD_CHEST
    );

    // Spawn Eggs
    public static final DeferredItem<SpawnEggItem> EMERALD_GOLEM_SPAWN_EGG = ITEMS.register(
            "emerald_golem_spawn_egg",
            () -> new DeferredSpawnEggItem(
                    ECAPEntityTypes.EMERALD_GOLEM,
                    0x17DD62,   // Primary color: emerald green
                    0x0D8C3E,   // Secondary color: darker emerald
                    new Item.Properties()
            )
    );

    public static final DeferredItem<SpawnEggItem> EMERALD_SKRIMISHER_SPAWN_EGG = ITEMS.register(
            "emerald_skrimisher_spawn_egg",
            () -> new DeferredSpawnEggItem(
                    ECAPEntityTypes.EMERALD_SKRIMISHER,
                    0xB87333,
                    0x6E3F25,
                    new Item.Properties()
            )
    );

    public static final DeferredItem<BlockItem> VILLAGE_MANAGER = ITEMS.registerSimpleBlockItem(
            "village_manager",
            ECAPBlocks.VILLAGE_MANAGER
    );

    public static final DeferredItem<BlockItem> EMERALD_ORE_PROCESSOR = ITEMS.registerSimpleBlockItem(
            "emerald_ore_processor",
            ECAPBlocks.EMERALD_ORE_PROCESSOR
    );

    public static final DeferredItem<Item> EMERALD_GREEN_DYE = ITEMS.registerItem(
            "emerald_green_dye",
            Item::new,
            new Item.Properties()
    );

    public static final DeferredItem<BlockItem> EMERALD_GREEN_STAINED_GLASS_PANE = ITEMS.registerSimpleBlockItem(
            "emerald_green_stained_glass_pane",
            ECAPBlocks.EMERALD_GREEN_STAINED_GLASS_PANE
    );

    public static final DeferredItem<BlockItem> EMERALD_GREEN_STAINED_GLASS = ITEMS.registerSimpleBlockItem(
            "emerald_green_stained_glass",
            ECAPBlocks.EMERALD_GREEN_STAINED_GLASS
    );

    public static final DeferredItem<BlockItem> EMERALD_GREEN_BED = ITEMS.registerSimpleBlockItem(
            "emerald_green_bed",
            ECAPBlocks.EMERALD_GREEN_BED
    );

    public static final DeferredItem<BlockItem> BANK = ITEMS.registerSimpleBlockItem(
            "bank",
            ECAPBlocks.BANK
    );

    public static final DeferredItem<BlockItem> SAWMILL = ITEMS.registerSimpleBlockItem(
            "sawmill",
            ECAPBlocks.SAWMILL
    );

    public static final DeferredItem<BlockItem> GOLEM_CONSTRUCTION_LOCATION = ITEMS.registerSimpleBlockItem(
            "golem_construction_location",
            ECAPBlocks.GOLEM_CONSTRUCTION_LOCATION
    );

    public static final DeferredItem<DoubleHighBlockItem> EMERALD_DOOR = ITEMS.register(
            "emerald_door",
            () -> new DoubleHighBlockItem(ECAPBlocks.EMERALD_DOOR.get(), new Item.Properties())
    );

    public static final DeferredItem<DoubleHighBlockItem> REGULAR_EMERALD_DOOR = ITEMS.register(
            "regular_emerald_door",
            () -> new DoubleHighBlockItem(ECAPBlocks.REGULAR_EMERALD_DOOR.get(), new Item.Properties())
    );

    // Standalone Items
    public static final DeferredItem<EmeraldLeadItem> EMERALD_LEAD = ITEMS.register(
            "emerald_lead",
            () -> new EmeraldLeadItem(new Item.Properties())
    );

    public static final DeferredItem<AbandonedVaultMapItem> ABANDONED_VAULT_MAP = ITEMS.register(
            "abandoned_vault_map",
            () -> new AbandonedVaultMapItem(AbandonedVaultMapItem.Target.NEAREST,
                    new Item.Properties().stacksTo(1))
    );

    public static final DeferredItem<AbandonedVaultMapItem> SECOND_ABANDONED_VAULT_MAP = ITEMS.register(
            "second_abandoned_vault_map",
            () -> new AbandonedVaultMapItem(AbandonedVaultMapItem.Target.SECOND_NEAREST,
                    new Item.Properties().stacksTo(1))
    );

    public static final DeferredItem<VillageMapItem> VILLAGE_MAP = ITEMS.register(
            "village_map",
            () -> new VillageMapItem(new Item.Properties().stacksTo(1))
    );

    /** Icon item for the first-infection Zombie Plague advancement. */
    public static final DeferredItem<Item> ZOMBIE_FACE = ITEMS.registerItem(
            "zombie_face",
            Item::new,
            new Item.Properties().stacksTo(1)
    );

    public static final DeferredItem<Item> COMPACTED_ROTTEN_FLESH = ITEMS.registerItem(
            "compacted_rotten_flesh",
            Item::new,
            new Item.Properties()
    );

    public static final DeferredItem<RottenFleshCoverItem> ROTTEN_FLESH_COVER = ITEMS.register(
            "rotten_flesh_cover",
            () -> new RottenFleshCoverItem(new Item.Properties())
    );
}
