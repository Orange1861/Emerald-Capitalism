package com.orangevillager61.emeraldcapitalism.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.behavior.HarvestFarmland;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Keeps vanilla farmer crop work reachable and makes the harvest/replant step
 * atomic from the player's point of view.
 *
 * <p>Vanilla only considers the three-by-three cube around the farmer when it
 * selects a work target. That is normally sufficient while the farmer wanders
 * freely, but it is not sufficient when another movement owner has just
 * returned the farmer to its work area. A bounded fallback search lets the
 * existing vanilla behavior publish a real {@code WALK_TARGET}; it does not
 * invoke the behavior or install a path itself.</p>
 *
 * <p>After a harvest, a small frontier search around the last crop is used
 * before the normal random candidate. This keeps work contiguous without
 * scanning the farm on every AI tick.</p>
 */
@Mixin(HarvestFarmland.class)
public abstract class HarvestFarmlandMixin {

    private static final int FALLBACK_HORIZONTAL_RADIUS = 8;
    private static final int NEIGHBOR_SEARCH_RADIUS = 1;

    @Shadow
    @Final
    private List<BlockPos> validFarmlandAroundVillager;

    @Shadow
    @Nullable
    private BlockPos aboveFarmlandPos;

    @Shadow
    @Nullable
    private BlockPos getValidFarmland(ServerLevel level) {
        throw new AssertionError();
    }

    @Unique
    @Nullable
    private BlockPos emeraldCapitalism$matureHarvest;

    @Unique
    @Nullable
    private BlockPos emeraldCapitalism$lastHarvestedPos;

    @ModifyConstant(method = "tick", constant = @Constant(doubleValue = 1.0D), require = 1)
    private double emeraldCapitalism$allowAdjacentCropInteraction(double vanillaRange) {
        return Math.max(vanillaRange, 2.5D);
    }

    @Inject(method = "checkExtraStartConditions", at = @At("RETURN"), cancellable = true, require = 1)
    private void emeraldCapitalism$findReachableCropFooting(ServerLevel level, Villager villager,
                                                             CallbackInfoReturnable<Boolean> callback) {
        if (villager.getVillagerData().getProfession() != VillagerProfession.FARMER
                || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            return;
        }

        if (!callback.getReturnValue()) {
            BlockPos origin = villager.blockPosition();
            BlockPos.MutableBlockPos candidate = origin.mutable();
            for (int x = -FALLBACK_HORIZONTAL_RADIUS; x <= FALLBACK_HORIZONTAL_RADIUS; x++) {
                for (int z = -FALLBACK_HORIZONTAL_RADIUS; z <= FALLBACK_HORIZONTAL_RADIUS; z++) {
                    for (int y = -1; y <= 1; y++) {
                        candidate.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                        if (emeraldCapitalism$isValidFarmland(candidate, level)) {
                            validFarmlandAroundVillager.add(candidate.immutable());
                        }
                    }
                }
            }

            aboveFarmlandPos = getValidFarmland(level);
            callback.setReturnValue(aboveFarmlandPos != null);
        }

        if (callback.getReturnValue()) {
            emeraldCapitalism$preferNeighboringWork(level, villager);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), require = 1)
    private void emeraldCapitalism$captureMatureHarvest(ServerLevel level, Villager villager,
                                                        long gameTime, CallbackInfo callback) {
        emeraldCapitalism$matureHarvest = null;
        if (villager.getVillagerData().getProfession() != VillagerProfession.FARMER) {
            return;
        }
        if (aboveFarmlandPos != null) {
            BlockState state = level.getBlockState(aboveFarmlandPos);
            if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                emeraldCapitalism$matureHarvest = aboveFarmlandPos.immutable();
            }
        }
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 1)
    private void emeraldCapitalism$replantAfterHarvest(ServerLevel level, Villager villager,
                                                        long gameTime, CallbackInfo callback) {
        BlockPos harvested = emeraldCapitalism$matureHarvest;
        emeraldCapitalism$matureHarvest = null;
        if (villager.getVillagerData().getProfession() != VillagerProfession.FARMER
                || harvested == null || !level.getBlockState(harvested).isAir()) {
            return;
        }
        emeraldCapitalism$lastHarvestedPos = harvested;
        if (!(level.getBlockState(harvested.below()).getBlock() instanceof FarmBlock)
                || !villager.hasFarmSeeds()) {
            return;
        }

        SimpleContainer inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (stack.isEmpty() || !stack.is(ItemTags.VILLAGER_PLANTABLE_SEEDS)
                    || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem)) {
                continue;
            }
            BlockState planted = blockItem.getBlock().defaultBlockState();
            level.setBlockAndUpdate(harvested, planted);
            level.gameEvent(GameEvent.BLOCK_PLACE, harvested, GameEvent.Context.of(villager, planted));
            level.playSound(null, harvested.getX(), harvested.getY(), harvested.getZ(),
                    SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0F, 1.0F);
            stack.shrink(1);
            if (stack.isEmpty()) {
                inventory.setItem(slot, net.minecraft.world.item.ItemStack.EMPTY);
            }
            break;
        }
    }

    @Unique
    private void emeraldCapitalism$preferNeighboringWork(ServerLevel level, Villager villager) {
        BlockPos lastHarvested = emeraldCapitalism$lastHarvestedPos;
        if (lastHarvested == null) {
            return;
        }

        BlockPos preferred = emeraldCapitalism$findNeighbor(level, lastHarvested, true);
        if (preferred == null && villager.hasFarmSeeds()) {
            preferred = emeraldCapitalism$findNeighbor(level, lastHarvested, false);
        }
        if (preferred == null) {
            return;
        }

        if (!validFarmlandAroundVillager.contains(preferred)) {
            validFarmlandAroundVillager.add(preferred);
        }
        aboveFarmlandPos = preferred;
    }

    @Unique
    @Nullable
    private BlockPos emeraldCapitalism$findNeighbor(ServerLevel level, BlockPos origin,
                                                     boolean matureOnly) {
        BlockPos nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (int x = -NEIGHBOR_SEARCH_RADIUS; x <= NEIGHBOR_SEARCH_RADIUS; x++) {
            for (int z = -NEIGHBOR_SEARCH_RADIUS; z <= NEIGHBOR_SEARCH_RADIUS; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }
                for (int y = -1; y <= 1; y++) {
                    BlockPos candidate = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(candidate);
                    boolean matureCrop = state.getBlock() instanceof CropBlock crop
                            && crop.isMaxAge(state);
                    boolean plantableFarmland = state.isAir()
                            && level.getBlockState(candidate.below()).getBlock() instanceof FarmBlock;
                    if ((matureOnly && !matureCrop) || (!matureOnly && !plantableFarmland)) {
                        continue;
                    }

                    int distance = x * x + y * y + z * z;
                    if (distance < nearestDistance) {
                        nearest = candidate;
                        nearestDistance = distance;
                    }
                }
            }
        }
        return nearest;
    }

    private static boolean emeraldCapitalism$isValidFarmland(BlockPos pos, ServerLevel level) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        Block below = level.getBlockState(pos.below()).getBlock();
        return block instanceof CropBlock crop && crop.isMaxAge(state)
                || state.isAir() && below instanceof FarmBlock;
    }
}
