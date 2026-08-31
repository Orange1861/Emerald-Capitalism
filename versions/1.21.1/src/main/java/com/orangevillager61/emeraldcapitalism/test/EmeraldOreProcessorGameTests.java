package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.block.EmeraldOreProcessorBlock;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldOreProcessorBlockEntity;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class EmeraldOreProcessorGameTests {

    private EmeraldOreProcessorGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void processorInventoryAndTimersSurviveRealBlockEntityReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockState state = ECAPBlocks.EMERALD_ORE_PROCESSOR.get().defaultBlockState();
        level.setBlock(pos, state, 3);

        EmeraldOreProcessorBlockEntity original = processorAt(helper, pos);
        if (original == null) {
            return;
        }
        original.setItem(EmeraldOreProcessorBlockEntity.SLOT_INPUT,
                new ItemStack(Items.EMERALD_ORE, 2));
        original.setItem(EmeraldOreProcessorBlockEntity.SLOT_FUEL,
                new ItemStack(Items.COAL, 2));

        CompoundTag notStartedSaved = original.saveWithId(level.registryAccess());
        BlockEntity notStartedLoaded = BlockEntity.loadStatic(
                pos, state, notStartedSaved, level.registryAccess());
        if (!(notStartedLoaded instanceof EmeraldOreProcessorBlockEntity notStarted)) {
            helper.fail("not-started processor state could not be reloaded");
            return;
        }
        assertInventoryAndTimers(helper, notStarted, 2, 2, 0, 0, 0, 400,
                "not-started processor state");

        setTimers(original, 2, 400, 399, 400);

        CompoundTag saved = original.saveWithId(level.registryAccess());
        BlockEntity loaded = BlockEntity.loadStatic(pos, state, saved, level.registryAccess());
        if (!(loaded instanceof EmeraldOreProcessorBlockEntity restored)) {
            helper.fail("real block-entity save/reload did not recreate the emerald ore processor");
            return;
        }

        assertInventoryAndTimers(helper, restored, 2, 2, 0, 2, 399, 400,
                "active processor state");

        EmeraldOreProcessorBlockEntity.serverTick(level, pos, state, restored);
        helper.assertValueEqual(restored.getItem(EmeraldOreProcessorBlockEntity.SLOT_INPUT).getCount(), 1,
                "one input must be consumed when the saved cook completes");
        helper.assertValueEqual(restored.getItem(EmeraldOreProcessorBlockEntity.SLOT_FUEL).getCount(), 2,
                "already-burning fuel must not be consumed a second time after reload");
        helper.assertValueEqual(restored.getItem(EmeraldOreProcessorBlockEntity.SLOT_OUTPUT).getCount(), 1,
                "completed cook must produce exactly one output");
        helper.assertTrue(restored.getItem(EmeraldOreProcessorBlockEntity.SLOT_OUTPUT).is(Items.EMERALD_BLOCK),
                "completed emerald ore cook produced the wrong output");
        helper.assertValueEqual(restored.getDataAccess().get(0), 1,
                "burn time did not resume from the saved active state");
        helper.assertValueEqual(restored.getDataAccess().get(2), 0,
                "completed cook progress was not reset");

        CompoundTag completedSaved = restored.saveWithId(level.registryAccess());
        BlockEntity completedLoaded = BlockEntity.loadStatic(
                pos, level.getBlockState(pos), completedSaved, level.registryAccess());
        if (!(completedLoaded instanceof EmeraldOreProcessorBlockEntity completed)) {
            helper.fail("completed processor state could not be reloaded");
            return;
        }
        assertInventoryAndTimers(helper, completed, 1, 2, 1, 1, 0, 400,
                "completed processor state");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void malformedProcessorTimersDefaultWithoutDiscardingInventory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockState state = ECAPBlocks.EMERALD_ORE_PROCESSOR.get().defaultBlockState();
        level.setBlock(pos, state, 3);

        EmeraldOreProcessorBlockEntity original = processorAt(helper, pos);
        if (original == null) {
            return;
        }
        original.setItem(EmeraldOreProcessorBlockEntity.SLOT_INPUT,
                new ItemStack(Items.EMERALD, 3));
        CompoundTag malformed = original.saveWithId(level.registryAccess());
        malformed.putInt("burn_time", -1);
        malformed.putInt("burn_duration", 400);
        malformed.putInt("cook_progress", 400);
        malformed.putInt("cook_total_time", 400);

        BlockEntity loaded;
        try {
            loaded = BlockEntity.loadStatic(pos, state, malformed, level.registryAccess());
        } catch (RuntimeException ex) {
            helper.fail("malformed processor timers crashed block-entity reload: " + ex.getMessage());
            return;
        }
        if (!(loaded instanceof EmeraldOreProcessorBlockEntity restored)) {
            helper.fail("malformed processor timers discarded the block entity");
            return;
        }

        assertInventoryAndTimers(helper, restored, 3, 0, 0, 0, 0, 400,
                "malformed processor state");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void processorDurableMutationsMarkTheChunkChanged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockState state = ECAPBlocks.EMERALD_ORE_PROCESSOR.get().defaultBlockState();
        level.setBlock(pos, state, 3);
        EmeraldOreProcessorBlockEntity processor = processorAt(helper, pos);
        if (processor == null) {
            return;
        }

        level.getChunkAt(pos).setUnsaved(false);
        processor.setItem(EmeraldOreProcessorBlockEntity.SLOT_INPUT,
                new ItemStack(Items.EMERALD_ORE));
        helper.assertTrue(level.getChunkAt(pos).isUnsaved(),
                "inventory insertion did not call setChanged");

        level.getChunkAt(pos).setUnsaved(false);
        setTimers(processor, 2, 400, 5, 400);
        EmeraldOreProcessorBlockEntity.serverTick(level, pos, state, processor);
        helper.assertTrue(level.getChunkAt(pos).isUnsaved(),
                "burn/cook timer mutation did not call setChanged");

        level.getChunkAt(pos).setUnsaved(false);
        processor.clearContent();
        helper.assertTrue(level.getChunkAt(pos).isUnsaved(),
                "clearContent did not call setChanged for durable inventory removal");
        helper.succeed();
    }

    private static EmeraldOreProcessorBlockEntity processorAt(GameTestHelper helper, BlockPos pos) {
        BlockEntity blockEntity = helper.getLevel().getBlockEntity(pos);
        if (blockEntity instanceof EmeraldOreProcessorBlockEntity processor) {
            return processor;
        }
        helper.fail("emerald ore processor block entity was not created");
        return null;
    }

    private static void setTimers(EmeraldOreProcessorBlockEntity processor,
                                  int burnTime, int burnDuration,
                                  int cookProgress, int cookTotalTime) {
        processor.getDataAccess().set(0, burnTime);
        processor.getDataAccess().set(1, burnDuration);
        processor.getDataAccess().set(2, cookProgress);
        processor.getDataAccess().set(3, cookTotalTime);
    }

    private static void assertInventoryAndTimers(GameTestHelper helper,
                                                 EmeraldOreProcessorBlockEntity processor,
                                                 int inputCount, int fuelCount, int outputCount,
                                                 int burnTime, int cookProgress, int cookTotalTime,
                                                 String stateName) {
        helper.assertValueEqual(processor.getItem(EmeraldOreProcessorBlockEntity.SLOT_INPUT).getCount(),
                inputCount, stateName + " input count changed");
        helper.assertValueEqual(processor.getItem(EmeraldOreProcessorBlockEntity.SLOT_FUEL).getCount(),
                fuelCount, stateName + " fuel count changed");
        helper.assertValueEqual(processor.getItem(EmeraldOreProcessorBlockEntity.SLOT_OUTPUT).getCount(),
                outputCount, stateName + " output count changed");
        helper.assertValueEqual(processor.getDataAccess().get(0), burnTime,
                stateName + " burn time changed");
        helper.assertValueEqual(processor.getDataAccess().get(2), cookProgress,
                stateName + " cook progress changed");
        helper.assertValueEqual(processor.getDataAccess().get(3), cookTotalTime,
                stateName + " cook total time changed");
    }
}
