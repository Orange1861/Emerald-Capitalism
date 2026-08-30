package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.block.entity.VillageManagerBlockEntity;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.UUID;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class VillageManagerPersistenceGameTests {

    private VillageManagerPersistenceGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void linkedManagerSurvivesRealBlockEntitySaveReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos managerPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockState state = ECAPBlocks.VILLAGE_MANAGER.get().defaultBlockState();
        level.setBlock(managerPos, state, 3);

        VillageManagerBlockEntity original = managerAt(helper, managerPos);
        if (original == null) {
            return;
        }

        UUID villageId = UUID.fromString("12345678-1234-5678-1234-567812345678");
        BlockPos bellPos = helper.absolutePos(new BlockPos(0, 1, 0));
        BlockPos bankPos = helper.absolutePos(new BlockPos(2, 1, 1));
        VillageRegistryData registry = VillageRegistryData.get(level);
        registry.getOrCreateVillage(villageId, bellPos, new AABB(
                managerPos.getX() - 8, managerPos.getY() - 4, managerPos.getZ() - 8,
                managerPos.getX() + 8, managerPos.getY() + 4, managerPos.getZ() + 8));

        original.setVillageId(villageId);
        original.registerBank(bankPos);
        byte[] originalMenuPayload = menuPayload(original);
        CompoundTag saved = original.saveWithId(level.registryAccess());

        BlockEntity loadedEntity = BlockEntity.loadStatic(managerPos, state, saved, level.registryAccess());
        if (!(loadedEntity instanceof VillageManagerBlockEntity restored)) {
            helper.fail("real block-entity save/reload did not recreate the village manager");
            return;
        }
        restored.setLevel(level);
        restored.onLoad();

        helper.assertTrue(villageId.equals(restored.getVillageId()),
                "village link did not survive manager save/reload");
        helper.assertTrue(bankPos.equals(restored.getBankPos()),
                "bank link did not survive manager save/reload");
        helper.assertTrue(managerPos.equals(registry.getVMPos(villageId)),
                "reloaded manager did not re-register the same village manager position");
        helper.assertTrue(bankPos.equals(registry.getBankPos(villageId)),
                "linked bank registry did not resolve the same bank after reload");
        helper.assertTrue(java.util.Arrays.equals(originalMenuPayload, menuPayload(restored)),
                "menu-opening payload changed across manager save/reload");
        helper.assertTrue(!saved.contains("village_name") && !saved.contains("member_count"),
                "menu/client summary data must not be persisted with manager links");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void malformedOrMissingLinksDefaultWithoutCrashing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos managerPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockState state = ECAPBlocks.VILLAGE_MANAGER.get().defaultBlockState();
        level.setBlock(managerPos, state, 3);

        VillageManagerBlockEntity source = managerAt(helper, managerPos);
        if (source == null) {
            return;
        }
        CompoundTag saved = source.saveWithId(level.registryAccess());

        VillageManagerBlockEntity missing = reload(helper, managerPos, state, saved);
        if (missing == null) {
            return;
        }
        helper.assertTrue(missing.getVillageId() == null && missing.getBankPos() == null,
                "missing links must default to an unlinked manager");

        CompoundTag malformed = saved.copy();
        malformed.putString("village_id", "not-a-uuid");
        try {
            VillageManagerBlockEntity malformedReload = reload(helper, managerPos, state, malformed);
            if (malformedReload == null) {
                return;
            }
            helper.assertTrue(malformedReload.getVillageId() == null && malformedReload.getBankPos() == null,
                    "malformed links must default to an unlinked manager");
        } catch (RuntimeException ex) {
            helper.fail("malformed manager links crashed block-entity reload: " + ex.getMessage());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void durableLinkAndUnlinkMutationsMarkChunkChanged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos managerPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockState state = ECAPBlocks.VILLAGE_MANAGER.get().defaultBlockState();
        level.setBlock(managerPos, state, 3);

        VillageManagerBlockEntity manager = managerAt(helper, managerPos);
        if (manager == null) {
            return;
        }
        UUID villageId = UUID.fromString("87654321-4321-8765-4321-876543218765");
        BlockPos bankPos = helper.absolutePos(new BlockPos(2, 1, 1));

        level.getChunkAt(managerPos).setUnsaved(false);
        manager.setVillageId(villageId);
        helper.assertTrue(level.getChunkAt(managerPos).isUnsaved(),
                "village link did not call setChanged");

        level.getChunkAt(managerPos).setUnsaved(false);
        manager.registerBank(bankPos);
        helper.assertTrue(level.getChunkAt(managerPos).isUnsaved(),
                "bank link did not call setChanged");

        level.getChunkAt(managerPos).setUnsaved(false);
        manager.deregisterBank();
        helper.assertTrue(level.getChunkAt(managerPos).isUnsaved(),
                "bank unlink did not call setChanged");

        level.getChunkAt(managerPos).setUnsaved(false);
        manager.setVillageId(null);
        helper.assertTrue(level.getChunkAt(managerPos).isUnsaved(),
                "village unlink did not call setChanged");
        helper.succeed();
    }

    private static VillageManagerBlockEntity managerAt(GameTestHelper helper, BlockPos pos) {
        BlockEntity blockEntity = helper.getLevel().getBlockEntity(pos);
        if (blockEntity instanceof VillageManagerBlockEntity manager) {
            return manager;
        }
        helper.fail("village manager block entity was not created");
        return null;
    }

    private static VillageManagerBlockEntity reload(GameTestHelper helper,
                                                    BlockPos pos,
                                                    BlockState state,
                                                    CompoundTag saved) {
        BlockEntity loaded = BlockEntity.loadStatic(pos, state, saved, helper.getLevel().registryAccess());
        if (!(loaded instanceof VillageManagerBlockEntity manager)) {
            helper.fail("village manager block entity could not be reloaded");
            return null;
        }
        return manager;
    }

    private static byte[] menuPayload(VillageManagerBlockEntity manager) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        manager.writeMenuOpenData(buffer);
        byte[] payload = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), payload);
        buffer.release();
        return payload;
    }
}
