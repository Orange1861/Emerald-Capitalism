package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldSkrimisher;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldChestBlockEntity;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.registry.ECAPEntityTypes;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.Optional;

public class EmeraldGolemEvents {

    private static final String INITIAL_AMBUSH_SPAWNED_KEY =
            "emeraldcapitalism_initial_emerald_golem_ambush_spawned";

    private EmeraldGolemEvents() {
    }

    /**
     * Register on the MOD/GAME event buses: registers entity attributes and golem spawning.
     */
    public static void register(IEventBus modBus, IEventBus gameBus) {
        modBus.addListener(EmeraldGolemEvents::onEntityAttributeCreation);
        gameBus.addListener(EmeraldGolemEvents::onBlockPlace);
        gameBus.addListener(EmeraldGolemEvents::onPlayerLoggedIn);
        gameBus.addListener(EmeraldGolemEvents::onPlayerClone);
        gameBus.addListener(EmeraldGolemEvents::onPlayerRespawn);
        gameBus.addListener(EmeraldGolemEvents::onPlayerChangedDimension);
    }

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(ECAPEntityTypes.EMERALD_GOLEM.get(), EmeraldGolem.createAttributes().build());
        event.put(ECAPEntityTypes.EMERALD_SKRIMISHER.get(), EmeraldSkrimisher.createAttributes().build());
    }

    /** Starts the one-time ambush when a server player first enters the world. */
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && !player.getPersistentData().getBoolean(INITIAL_AMBUSH_SPAWNED_KEY)) {
            if (trySpawnAmbush(player.serverLevel(), player)) {
                player.getPersistentData().putBoolean(INITIAL_AMBUSH_SPAWNED_KEY, true);
            }
        }
    }

    /** Preserves the one-time ambush marker when the player entity is cloned on death. */
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (event.getOriginal().getPersistentData().getBoolean(INITIAL_AMBUSH_SPAWNED_KEY)) {
            event.getEntity().getPersistentData().putBoolean(INITIAL_AMBUSH_SPAWNED_KEY, true);
        }
    }

    /** Retargets existing ambushes and starts configured or previously failed ambushes. */
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        retargetExistingAmbush(player.serverLevel().getServer(), player);
        boolean initialAmbushPending = !player.getPersistentData()
                .getBoolean(INITIAL_AMBUSH_SPAWNED_KEY);
        if (Config.emeraldGolemAmbushOnRespawn || initialAmbushPending) {
            if (trySpawnAmbush(player.serverLevel(), player)) {
                // A successful retry also completes the one-time initial
                // ambush, preventing a later login from starting another one.
                player.getPersistentData().putBoolean(INITIAL_AMBUSH_SPAWNED_KEY, true);
            }
        }
    }

    /** Rebinds any loaded ambush in any dimension to the current player entity. */
    private static void retargetExistingAmbush(MinecraftServer server, ServerPlayer player) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof EmeraldGolem golem && golem.isAmbushFor(player.getUUID())) {
                    if (level == player.serverLevel()) {
                        golem.retargetAmbush(player);
                    } else {
                        golem.pauseAmbushFor(player.getUUID());
                    }
                }
            }
        }
    }

    /** Rebinds delayed ambushes after a player changes dimension. */
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            retargetExistingAmbush(player.serverLevel().getServer(), player);
        }
    }

    /**
     * Spawns one delayed ambush golem for a server player entering or respawning
     * into the game.
     * This method owns the server-side spawn details and does not require a
     * village or reputation lookup.
     */
    public static boolean trySpawnAmbush(ServerLevel level, ServerPlayer player) {
        if (!Config.emeraldGolemAmbushEnabled || !player.isAlive() || player.isSpectator()
                || !level.getWorldBorder().isWithinBounds(player.blockPosition())) {
            return false;
        }

        if (hasExistingAmbush(level.getServer(), player)) {
            return false;
        }

        EmeraldGolem golem = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        if (golem == null) {
            return false;
        }

        Optional<BlockPos> spawnPosition = findAmbushSpawnPosition(level, player, golem);
        if (spawnPosition.isEmpty()) {
            return false;
        }

        int minDelayTicks = Math.min(Config.emeraldGolemAmbushMinDelaySeconds,
                Config.emeraldGolemAmbushMaxDelaySeconds) * 20;
        int maxDelayTicks = Math.max(Config.emeraldGolemAmbushMinDelaySeconds,
                Config.emeraldGolemAmbushMaxDelaySeconds) * 20;
        int delayRange = maxDelayTicks - minDelayTicks;
        int attackDelayTicks = minDelayTicks
                + (delayRange == 0 ? 0 : level.getRandom().nextInt(delayRange + 1));

        golem.setHealth((float) Config.emeraldGolemAmbushHealth);
        golem.armAmbush(player, attackDelayTicks);
        return level.addFreshEntity(golem);
    }

    /**
     * Checks every loaded server level so a respawn or dimension change cannot
     * create a second ambush for the same player merely because the old golem
     * is no longer nearby.
     */
    private static boolean hasExistingAmbush(MinecraftServer server, ServerPlayer player) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof EmeraldGolem golem
                        && golem.isAmbushFor(player.getUUID())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Optional<BlockPos> findAmbushSpawnPosition(ServerLevel level,
                                                                ServerPlayer player,
                                                                EmeraldGolem golem) {
        int distance = Config.emeraldGolemAmbushSpawnDistance;
        double startAngle = level.getRandom().nextDouble() * Math.PI * 2.0D;
        int playerY = player.blockPosition().getY();

        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = startAngle + (Math.PI * 2.0D * attempt / 24.0D);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);

            for (int verticalOffset = 0; verticalOffset <= 8; verticalOffset++) {
                for (int direction : verticalOffset == 0 ? new int[]{1} : new int[]{1, -1}) {
                    int y = playerY + verticalOffset * direction;
                    if (y < level.getMinBuildHeight() + 1 || y >= level.getMaxBuildHeight() - 2) {
                        continue;
                    }

                    BlockPos candidate = new BlockPos(x, y, z);
                    if (!isValidAmbushPosition(level, candidate)) {
                        continue;
                    }

                    golem.moveTo(candidate.getX() + 0.5D, candidate.getY(),
                            candidate.getZ() + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
                    if (level.noCollision(golem)) {
                        return Optional.of(candidate);
                    }
                }
            }

            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (surfaceY >= level.getMinBuildHeight() + 1
                    && surfaceY < level.getMaxBuildHeight() - 2) {
                BlockPos surface = new BlockPos(x, surfaceY, z);
                if (isValidAmbushPosition(level, surface)) {
                    golem.moveTo(surface.getX() + 0.5D, surface.getY(),
                            surface.getZ() + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
                    if (level.noCollision(golem)) {
                        return Optional.of(surface);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isValidAmbushPosition(ServerLevel level, BlockPos position) {
        BlockState floor = level.getBlockState(position.below());
        return floor.isFaceSturdy(level, position.below(), Direction.UP)
                && level.getBlockState(position).getFluidState().isEmpty()
                && level.getBlockState(position.above()).getFluidState().isEmpty();
    }

    /**
     * When a carved pumpkin is placed, check if it completes an Emerald Golem pattern.
     *
     * Pattern (T-shape, same as iron golem but with emerald blocks):
     * <pre>
     *      P        ← carved pumpkin (just placed)
     *     EEE       ← 3 emerald blocks (arms + torso)
     *      E        ← 1 emerald block (legs)
     * </pre>
     * Arms can extend along either the X or Z axis.
     */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        BlockState placed = event.getPlacedBlock();
        if (!placed.is(Blocks.CARVED_PUMPKIN)) {
            return;
        }

        BlockPos pumpkinPos = event.getPos();

        // Skrimisher pattern: pumpkin on an emerald chest on an emerald block.
        if (trySpawnEmeraldSkrimisher(level, pumpkinPos, event.getEntity())) {
            return;
        }

        // Try both horizontal axis orientations for the T-shape arms
        if (trySpawnEmeraldGolem(level, pumpkinPos, Direction.Axis.X, event.getEntity())) {
            return;
        }
        trySpawnEmeraldGolem(level, pumpkinPos, Direction.Axis.Z, event.getEntity());
    }

    /**
     * Attempts to spawn an Emerald Skrimisher from a three-block vertical
     * stack: emerald block, emerald chest, and pumpkin.
     */
    public static boolean trySpawnEmeraldSkrimisher(ServerLevel level, BlockPos pumpkinPos, Entity placer) {
        BlockPos chestPos = pumpkinPos.below();
        BlockPos basePos = chestPos.below();
        if (!level.getBlockState(chestPos).is(ECAPBlocks.EMERALD_CHEST.get())
                || !isEmeraldBlock(level, basePos)) {
            return false;
        }

        EmeraldSkrimisher skrimisher = ECAPEntityTypes.EMERALD_SKRIMISHER.get().create(level);
        if (skrimisher == null) {
            return false;
        }

        // Never delete items stored in the consumed emerald chest.
        if (level.getBlockEntity(chestPos) instanceof EmeraldChestBlockEntity chest) {
            Containers.dropContents(level, chestPos, chest);
        }

        level.setBlock(pumpkinPos, Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(chestPos, Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(basePos, Blocks.AIR.defaultBlockState(), 2);

        skrimisher.setPlayerCreated(placer instanceof Player);
        skrimisher.moveTo(
                (double) basePos.getX() + 0.5D,
                (double) basePos.getY() + 0.05D,
                (double) basePos.getZ() + 0.5D,
                0.0F, 0.0F
        );
        level.addFreshEntity(skrimisher);

        level.blockUpdated(pumpkinPos, Blocks.AIR);
        level.blockUpdated(chestPos, Blocks.AIR);
        level.blockUpdated(basePos, Blocks.AIR);

        if (placer instanceof ServerPlayer serverPlayer) {
            CriteriaTriggers.SUMMONED_ENTITY.trigger(serverPlayer, skrimisher);
        }
        return true;
    }

    /**
     * Attempts to spawn an Emerald Golem at the given pumpkin position with
     * the T-shape arms along the specified axis.
     *
     * @return true if the golem was successfully spawned
     */
    private static boolean trySpawnEmeraldGolem(ServerLevel level, BlockPos pumpkinPos, Direction.Axis armAxis, Entity placer) {
        BlockPos centerBody = pumpkinPos.below();
        BlockPos legs = centerBody.below();

        // Check center body and legs are emerald blocks
        if (!isEmeraldBlock(level, centerBody) || !isEmeraldBlock(level, legs)) {
            return false;
        }

        // Check arm blocks along the specified axis
        BlockPos arm1, arm2;
        if (armAxis == Direction.Axis.X) {
            arm1 = centerBody.east();
            arm2 = centerBody.west();
        } else {
            arm1 = centerBody.north();
            arm2 = centerBody.south();
        }

        if (!isEmeraldBlock(level, arm1) || !isEmeraldBlock(level, arm2)) {
            return false;
        }

        // All blocks match: spawn the golem!

        // Remove the building blocks
        level.setBlock(pumpkinPos, Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(centerBody, Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(arm1, Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(arm2, Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(legs, Blocks.AIR.defaultBlockState(), 2);

        // Spawn at the legs position (bottom of the T), centered
        EmeraldGolem golem = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        if (golem == null) return false;

        golem.setPlayerCreated(placer instanceof Player);
        golem.moveTo(
                (double) legs.getX() + 0.5D,
                (double) legs.getY() + 0.05D,
                (double) legs.getZ() + 0.5D,
                0.0F, 0.0F
        );

        level.addFreshEntity(golem);

        // Block update notifications for removed blocks
        level.blockUpdated(pumpkinPos, Blocks.AIR);
        level.blockUpdated(centerBody, Blocks.AIR);
        level.blockUpdated(arm1, Blocks.AIR);
        level.blockUpdated(arm2, Blocks.AIR);
        level.blockUpdated(legs, Blocks.AIR);

        // Award advancement trigger (same as vanilla golems)
        if (placer instanceof ServerPlayer serverPlayer) {
            CriteriaTriggers.SUMMONED_ENTITY.trigger(serverPlayer, golem);
        }

        return true;
    }

    private static boolean isEmeraldBlock(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.EMERALD_BLOCK);
    }
}
