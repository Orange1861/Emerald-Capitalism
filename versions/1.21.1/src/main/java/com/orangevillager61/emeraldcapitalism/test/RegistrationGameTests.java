package com.orangevillager61.emeraldcapitalism.test;

import com.mojang.serialization.JsonOps;
import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.block.EmeraldDoorTopBlock;
import com.orangevillager61.emeraldcapitalism.block.EmeraldOreProcessorBlock;
import com.orangevillager61.emeraldcapitalism.block.VillageManagerBlock;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldChestBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldGreenBedBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldOreProcessorBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.VillageManagerBlockEntity;
import com.orangevillager61.emeraldcapitalism.network.PacketHandlerUtil;
import com.orangevillager61.emeraldcapitalism.network.VillagePOIAccessPolicy;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlockEntityTypes;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.registry.ECAPEntityTypes;
import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import com.orangevillager61.emeraldcapitalism.item.VillageMapItem;
import com.orangevillager61.emeraldcapitalism.registry.ECAPRecipeTypes;
import com.orangevillager61.emeraldcapitalism.registry.ECAPPoiTypes;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.menu.ECAPMenuTypes;
import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.resources.RegistryOps;

import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import com.mojang.authlib.GameProfile;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class RegistrationGameTests {

    private RegistrationGameTests() {}

    @GameTest(template = "empty_3x3x3")
    public static void registryIdentityAndDedicatedStartup(GameTestHelper helper) {
        assertId(helper, BuiltInRegistries.BLOCK, ECAPBlocks.EMERALD_CHEST.get(), "emerald_chest");
        assertId(helper, BuiltInRegistries.BLOCK, ECAPBlocks.VILLAGE_MANAGER.get(), "village_manager");
        assertId(helper, BuiltInRegistries.BLOCK, ECAPBlocks.EMERALD_ORE_PROCESSOR.get(), "emerald_ore_processor");
        assertId(helper, BuiltInRegistries.BLOCK, ECAPBlocks.BANK.get(), "bank");
        assertId(helper, BuiltInRegistries.BLOCK, ECAPBlocks.EMERALD_DOOR_TOP.get(), "emerald_door_top");
        assertId(helper, BuiltInRegistries.BLOCK, ECAPBlocks.EMERALD_DOOR.get(), "emerald_door");
        assertId(helper, BuiltInRegistries.BLOCK, ECAPBlocks.REGULAR_EMERALD_DOOR.get(), "regular_emerald_door");
        assertId(helper, BuiltInRegistries.BLOCK, ECAPBlocks.EMERALD_GREEN_STAINED_GLASS_PANE.get(), "emerald_green_stained_glass_pane");
        assertId(helper, BuiltInRegistries.BLOCK, ECAPBlocks.EMERALD_GREEN_STAINED_GLASS.get(), "emerald_green_stained_glass");
        assertId(helper, BuiltInRegistries.BLOCK, ECAPBlocks.EMERALD_GREEN_WOOL.get(), "emerald_green_wool");
        assertId(helper, BuiltInRegistries.BLOCK, ECAPBlocks.EMERALD_GREEN_BED.get(), "emerald_green_bed");
        assertId(helper, BuiltInRegistries.BLOCK, ECAPBlocks.GOLEM_CONSTRUCTION_LOCATION.get(), "golem_construction_location");
        assertId(helper, BuiltInRegistries.BLOCK, ECAPBlocks.SAWMILL.get(), "sawmill");

        assertId(helper, BuiltInRegistries.ITEM, ECAPItems.EMERALD_CHEST.get(), "emerald_chest");
        assertId(helper, BuiltInRegistries.ITEM, ECAPItems.EMERALD_GOLEM_SPAWN_EGG.get(), "emerald_golem_spawn_egg");
        assertId(helper, BuiltInRegistries.ITEM, ECAPItems.VILLAGE_MANAGER.get(), "village_manager");
        assertId(helper, BuiltInRegistries.ITEM, ECAPItems.EMERALD_ORE_PROCESSOR.get(), "emerald_ore_processor");
        assertId(helper, BuiltInRegistries.ITEM, ECAPItems.EMERALD_GREEN_DYE.get(), "emerald_green_dye");
        assertId(helper, BuiltInRegistries.ITEM, ECAPItems.EMERALD_GREEN_STAINED_GLASS_PANE.get(), "emerald_green_stained_glass_pane");
        assertId(helper, BuiltInRegistries.ITEM, ECAPItems.EMERALD_GREEN_STAINED_GLASS.get(), "emerald_green_stained_glass");
        assertId(helper, BuiltInRegistries.ITEM, ECAPItems.EMERALD_GREEN_WOOL.get(), "emerald_green_wool");
        assertId(helper, BuiltInRegistries.ITEM, ECAPItems.EMERALD_GREEN_BED.get(), "emerald_green_bed");
        assertId(helper, BuiltInRegistries.ITEM, ECAPItems.BANK.get(), "bank");
        assertId(helper, BuiltInRegistries.ITEM, ECAPItems.GOLEM_CONSTRUCTION_LOCATION.get(), "golem_construction_location");
        assertId(helper, BuiltInRegistries.ITEM, ECAPItems.EMERALD_DOOR.get(), "emerald_door");
        assertId(helper, BuiltInRegistries.ITEM, ECAPItems.REGULAR_EMERALD_DOOR.get(), "regular_emerald_door");
        assertId(helper, BuiltInRegistries.ITEM, ECAPItems.EMERALD_LEAD.get(), "emerald_lead");
        assertId(helper, BuiltInRegistries.ITEM, ECAPItems.SAWMILL.get(), "sawmill");
        assertId(helper, BuiltInRegistries.ITEM, ECAPItems.VILLAGE_MAP.get(), "village_map");
        if (!(ECAPItems.VILLAGE_MAP.get() instanceof VillageMapItem)
                || ECAPItems.VILLAGE_MAP.get().getDefaultMaxStackSize() != 1) {
            helper.fail("Village Map was not registered as a single-use locator item");
            return;
        }

        assertId(helper, BuiltInRegistries.ENTITY_TYPE, ECAPEntityTypes.EMERALD_GOLEM.get(), "emerald_golem");
        assertId(helper, BuiltInRegistries.BLOCK_ENTITY_TYPE, ECAPBlockEntityTypes.EMERALD_CHEST.get(), "emerald_chest");
        assertId(helper, BuiltInRegistries.BLOCK_ENTITY_TYPE, ECAPBlockEntityTypes.VILLAGE_MANAGER.get(), "village_manager");
        assertId(helper, BuiltInRegistries.BLOCK_ENTITY_TYPE, ECAPBlockEntityTypes.EMERALD_ORE_PROCESSOR.get(), "emerald_ore_processor");
        assertId(helper, BuiltInRegistries.BLOCK_ENTITY_TYPE, ECAPBlockEntityTypes.BANK.get(), "bank");
        assertId(helper, BuiltInRegistries.BLOCK_ENTITY_TYPE, ECAPBlockEntityTypes.EMERALD_GREEN_BED.get(), "emerald_green_bed");
        assertId(helper, BuiltInRegistries.MENU, ECAPMenuTypes.VILLAGER_STATS_MENU.get(), "villager_stats");
        assertId(helper, BuiltInRegistries.MENU, ECAPMenuTypes.VILLAGE_MANAGER_MENU.get(), "village_manager");
        assertId(helper, BuiltInRegistries.MENU, ECAPMenuTypes.EMERALD_ORE_PROCESSOR_MENU.get(), "emerald_ore_processor");
        assertId(helper, BuiltInRegistries.MENU, ECAPMenuTypes.BANK_MENU.get(), "bank");
        assertId(helper, BuiltInRegistries.MENU, ECAPMenuTypes.SAWMILL_MENU.get(), "sawmill");
        assertId(helper, BuiltInRegistries.POINT_OF_INTEREST_TYPE, ECAPPoiTypes.SAWMILL.get(), "sawmill");
        assertId(helper, BuiltInRegistries.VILLAGER_PROFESSION, ECAPVillagerProfessions.LUMBERJACK.get(), "lumberjack");
        var sawmillPoiHolder = BuiltInRegistries.POINT_OF_INTEREST_TYPE
                .getHolder(ECAPPoiTypes.SAWMILL.getKey()).orElseThrow();
        if (!ECAPVillagerProfessions.LUMBERJACK.get().heldJobSite().test(sawmillPoiHolder)) {
            helper.fail("Lumberjack profession is not assigned to the sawmill POI");
            return;
        }
        if (Objects.equals(ECAPRecipeTypes.SAWMILL.get(), RecipeType.STONECUTTING)) {
            helper.fail("Sawmill recipes reused the vanilla stonecutting recipe type");
            return;
        }
        assertId(helper, NeoForgeRegistries.ATTACHMENT_TYPES, EmeraldCapitalismAttachments.VILLAGER_STATS.get(), "villager_stats");
        assertId(helper, NeoForgeRegistries.ATTACHMENT_TYPES,
                EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION.get(), "lumberjack_production");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void blockItemsRemainBoundToTheirBlocks(GameTestHelper helper) {
        assertBlockItem(helper, ECAPItems.EMERALD_CHEST.get(), ECAPBlocks.EMERALD_CHEST.get());
        assertBlockItem(helper, ECAPItems.VILLAGE_MANAGER.get(), ECAPBlocks.VILLAGE_MANAGER.get());
        assertBlockItem(helper, ECAPItems.EMERALD_ORE_PROCESSOR.get(), ECAPBlocks.EMERALD_ORE_PROCESSOR.get());
        assertBlockItem(helper, ECAPItems.EMERALD_GREEN_STAINED_GLASS_PANE.get(), ECAPBlocks.EMERALD_GREEN_STAINED_GLASS_PANE.get());
        assertBlockItem(helper, ECAPItems.EMERALD_GREEN_STAINED_GLASS.get(), ECAPBlocks.EMERALD_GREEN_STAINED_GLASS.get());
        assertBlockItem(helper, ECAPItems.EMERALD_GREEN_WOOL.get(), ECAPBlocks.EMERALD_GREEN_WOOL.get());
        assertBlockItem(helper, ECAPItems.EMERALD_GREEN_BED.get(), ECAPBlocks.EMERALD_GREEN_BED.get());
        assertBlockItem(helper, ECAPItems.BANK.get(), ECAPBlocks.BANK.get());
        assertBlockItem(helper, ECAPItems.SAWMILL.get(), ECAPBlocks.SAWMILL.get());
        assertBlockItem(helper, ECAPItems.GOLEM_CONSTRUCTION_LOCATION.get(), ECAPBlocks.GOLEM_CONSTRUCTION_LOCATION.get());
        assertDoorItem(helper, ECAPItems.EMERALD_DOOR.get(), ECAPBlocks.EMERALD_DOOR.get());
        assertDoorItem(helper, ECAPItems.REGULAR_EMERALD_DOOR.get(), ECAPBlocks.REGULAR_EMERALD_DOOR.get());

        Item egg = ECAPItems.EMERALD_GOLEM_SPAWN_EGG.get();
        if (!(egg instanceof DeferredSpawnEggItem deferredEgg)
                || deferredEgg.getType(egg.getDefaultInstance()) != ECAPEntityTypes.EMERALD_GOLEM.get()) {
            helper.fail("Emerald golem egg is not the intended deferred spawn egg");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void blockEntityTypesAcceptOnlyTheirRegisteredBlocks(GameTestHelper helper) {
        assertBlockEntityType(helper, ECAPBlockEntityTypes.EMERALD_CHEST.get(), ECAPBlocks.EMERALD_CHEST.get(), EmeraldChestBlockEntity.class);
        assertBlockEntityType(helper, ECAPBlockEntityTypes.VILLAGE_MANAGER.get(), ECAPBlocks.VILLAGE_MANAGER.get(), VillageManagerBlockEntity.class);
        assertBlockEntityType(helper, ECAPBlockEntityTypes.EMERALD_ORE_PROCESSOR.get(), ECAPBlocks.EMERALD_ORE_PROCESSOR.get(), EmeraldOreProcessorBlockEntity.class);
        assertBlockEntityType(helper, ECAPBlockEntityTypes.BANK.get(), ECAPBlocks.BANK.get(), BankBlockEntity.class);
        assertBlockEntityType(helper, ECAPBlockEntityTypes.EMERALD_GREEN_BED.get(), ECAPBlocks.EMERALD_GREEN_BED.get(), EmeraldGreenBedBlockEntity.class);
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void facingPropertiesRemainHorizontalAndSerializable(GameTestHelper helper) {
        assertFacing(helper, ECAPBlocks.BANK.get(), BankBlock.FACING);
        assertFacing(helper, ECAPBlocks.EMERALD_DOOR_TOP.get(), EmeraldDoorTopBlock.FACING);
        assertFacing(helper, ECAPBlocks.EMERALD_ORE_PROCESSOR.get(), EmeraldOreProcessorBlock.FACING);
        assertFacing(helper, ECAPBlocks.VILLAGE_MANAGER.get(), VillageManagerBlock.FACING);
        assertFacing(helper, ECAPBlocks.SAWMILL.get(), net.minecraft.world.level.block.StonecutterBlock.FACING);
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void sawmillRecipesUseWoodTypesAndCounts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var recipes = level.getRecipeManager().getAllRecipesFor(ECAPRecipeTypes.SAWMILL.get());
        if (recipes.size() != 120) {
            helper.fail("Expected 120 sawmill recipes, found " + recipes.size());
            return;
        }

        var oakDoor = recipes.stream()
                .map(RecipeHolder::value)
                .filter(recipe -> recipe.getResultItem(level.registryAccess()).is(Items.OAK_DOOR))
                .findFirst()
                .orElseThrow();
        if (oakDoor.getInputCount() != 3
                || oakDoor.getResultItem(level.registryAccess()).getCount() != 2
                || !oakDoor.matches(new SingleRecipeInput(new ItemStack(Items.OAK_PLANKS, 3)), level)) {
            helper.fail("Oak door sawmill recipe did not preserve its input/output counts");
            return;
        }

        var birchStairs = recipes.stream()
                .map(RecipeHolder::value)
                .filter(recipe -> recipe.getResultItem(level.registryAccess()).is(Items.BIRCH_STAIRS))
                .findFirst();
        if (birchStairs.isEmpty()
                || !birchStairs.get().matches(
                        new SingleRecipeInput(new ItemStack(Items.BIRCH_PLANKS, 1)), level)) {
            helper.fail("Birch stairs sawmill recipe did not accept birch planks");
            return;
        }

        var stonecutterRecipes = level.getRecipeManager().getAllRecipesFor(RecipeType.STONECUTTING);
        if (stonecutterRecipes.stream().anyMatch(holder -> holder.value().getType() == ECAPRecipeTypes.SAWMILL.get())) {
            helper.fail("Sawmill recipes were exposed through the vanilla stonecutter type");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void serverLevelBoundaryFollowsTheServerPlayer(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel();
        ServerPlayer player = new ServerPlayer(
                overworld.getServer(), overworld,
                new GameProfile(UUID.randomUUID(), "ecap-registration-test"),
                ClientInformation.createDefault()
        );
        if (PacketHandlerUtil.serverLevel(player) != overworld) {
            helper.fail("Server-level helper did not return the sender's actual level");
            return;
        }

        ServerLevel nether = overworld.getServer().getLevel(Level.NETHER);
        if (nether == null) {
            helper.fail("Nether level was unavailable for the server-level boundary test");
            return;
        }
        player.setServerLevel(nether);
        if (PacketHandlerUtil.serverLevel(player) != nether) {
            helper.fail("Server-level helper did not follow a dimension change");
            return;
        }
        VillageRecord village = new VillageRecord(
                UUID.randomUUID(),
                new net.minecraft.core.BlockPos(0, 0, 0),
                new net.minecraft.world.phys.AABB(-1, -1, -1, 1, 1, 1)
        );
        if (VillagePOIAccessPolicy.isLocalContextValid(player, overworld, village)) {
            helper.fail("A client-supplied position was treated as proof of level ownership");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void identifierHelperPreservesExactPathsAndRejectsInvalidOnes(GameTestHelper helper) {
        if (!"emeraldcapitalism:request_full_scan".equals(ModIds.id("request_full_scan").toString())
                || !"emeraldcapitalism:textures/gui/villager_stats.png"
                .equals(ModIds.id("textures/gui/villager_stats.png").toString())
                || !"emeraldcapitalism:outskirt_farm".equals(ModIds.id("outskirt_farm").toString())) {
            helper.fail("Mod identifier helper changed an existing path");
            return;
        }
        if (!throwsInvalidPath(null) || !throwsInvalidPath("BadPath")
                || !throwsInvalidPath("minecraft:stone") || !throwsInvalidPath("")) {
            helper.fail("Mod identifier helper normalized an invalid path");
            return;
        }
        helper.succeed();
    }

    private static boolean throwsInvalidPath(String path) {
        try {
            ModIds.id(path);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static <T> void assertId(GameTestHelper helper, Registry<T> registry, T value, String path) {
        if (!ModIds.id(path).equals(registry.getKey(value))) {
            helper.fail("Registry identity mismatch for " + path + ": " + registry.getKey(value));
        }
    }

    private static void assertBlockItem(GameTestHelper helper, Item item, Block expectedBlock) {
        if (!(item instanceof BlockItem blockItem) || blockItem.getBlock() != expectedBlock) {
            helper.fail("Block item is bound to the wrong block: " + item);
        }
    }

    private static void assertDoorItem(GameTestHelper helper, Item item, Block expectedBlock) {
        if (!(item instanceof DoubleHighBlockItem)) {
            helper.fail("Door item is not a DoubleHighBlockItem: " + item);
        }
        assertBlockItem(helper, item, expectedBlock);
    }

    private static <T extends BlockEntity> void assertBlockEntityType(
            GameTestHelper helper, BlockEntityType<T> type, Block block, Class<T> expectedClass) {
        BlockState state = block.defaultBlockState();
        if (!type.isValid(state)) {
            helper.fail("Block entity type rejected its registered block: " + block);
            return;
        }
        BlockEntity entity = type.create(BlockPosHelper.TEST_POS, state);
        if (!expectedClass.isInstance(entity)) {
            helper.fail("Block entity type created " + entity + " instead of " + expectedClass.getSimpleName());
        }
        if (type.isValid(Blocks.STONE.defaultBlockState())) {
            helper.fail("Block entity type accepted an unrelated block: " + block);
        }
    }

    private static void assertFacing(GameTestHelper helper, Block block, EnumProperty<Direction> facing) {
        if (!"facing".equals(facing.getName())
                || !Set.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)
                .equals(Set.copyOf(facing.getPossibleValues()))) {
            helper.fail("Facing property is not exactly horizontal on " + block);
            return;
        }

        BlockState state = block.defaultBlockState();
        if (state.getValue(facing) != Direction.NORTH) {
            helper.fail("Default facing changed on " + block);
            return;
        }
        if (Rotation.CLOCKWISE_90.rotate(state.getValue(facing)) != Direction.EAST
                || Mirror.LEFT_RIGHT.mirror(state.getValue(facing)) != Direction.SOUTH) {
            helper.fail("Facing rotation or mirroring changed on " + block);
            return;
        }

        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        for (Direction direction : facing.getPossibleValues()) {
            BlockState directed = state.setValue(facing, direction);
            var encoded = BlockState.CODEC.encodeStart(ops, directed).result().orElseThrow();
            BlockState decoded = BlockState.CODEC.parse(ops, encoded).result().orElseThrow();
            if (!decoded.equals(directed)) {
                helper.fail("Facing serialization changed " + direction + " on " + block);
                return;
            }
        }
    }

    private static final class BlockPosHelper {
        private static final net.minecraft.core.BlockPos TEST_POS = new net.minecraft.core.BlockPos(1, 1, 1);
    }
}
