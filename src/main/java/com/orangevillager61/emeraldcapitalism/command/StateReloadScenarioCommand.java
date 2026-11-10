package com.orangevillager61.emeraldcapitalism.command;

import com.mojang.brigadier.CommandDispatcher;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.util.EntityCreation;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageWorkCell;
import com.orangevillager61.emeraldcapitalism.world.village.VillagerPOIRecord;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Temporary Phase 3 manual scenario; Phase 6 migrates it to the common scenario runner.
 * It builds a disposable farmer job and reports lease state without changing it.
 */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class StateReloadScenarioCommand {

    /** Wide enough that walking between work items keeps the farmer mid-job for about a minute. */
    private static final int MANUAL_HALF_WIDTH = 22;
    private static final int WORK_ITEMS = 16;
    private static final int WATCH_INTERVAL_TICKS = 20;

    /** Session-local marker state; a reload intentionally forgets it. */
    @Nullable
    private static Watch watch;

    private StateReloadScenarioCommand() {
    }

    private record Watch(ServerLevel level, UUID villageId, UUID farmerId) {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ecapscenario")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("state_reload")
                        .then(Commands.literal("start").executes(context -> {
                            CommandSourceStack source = context.getSource();
                            BlockPos origin = BlockPos.containing(source.getPosition());
                            if (VillageRegistryData.get(source.getLevel()).getVillageFor(origin) != null) {
                                source.sendFailure(Component.literal(
                                        "state_reload needs a location outside any village"));
                                return 0;
                            }
                            start(source.getLevel(), origin, MANUAL_HALF_WIDTH);
                            source.sendSuccess(() -> Component.literal(
                                    "state_reload started; wait for [ECAP][StateReload] MID_JOB"), true);
                            return 1;
                        }))
                        .then(Commands.literal("inspect").executes(context -> {
                            CommandSourceStack source = context.getSource();
                            VillageRecord village = VillageRegistryData.get(source.getLevel())
                                    .getVillageFor(BlockPos.containing(source.getPosition()));
                            if (village == null) {
                                source.sendFailure(Component.literal("no village at this position"));
                                return 0;
                            }
                            String report = describe(village);
                            EmeraldCapitalism.LOGGER.info("[ECAP][StateReload] INSPECT {}", report);
                            source.sendSuccess(() -> Component.literal(report), false);
                            return 1;
                        }))));
    }

    /**
     * Builds the disposable scene around {@code origin} and registers one real farmer.
     * Work items sit on a ring just inside {@code halfWidth}, so a wider scene is a longer job.
     */
    public static Villager start(ServerLevel level, BlockPos origin, int halfWidth) {
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-halfWidth, -1, -halfWidth), origin.offset(halfWidth, 1, halfWidth))) {
            level.setBlock(pos, pos.getY() < origin.getY()
                    ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        }
        VillageRecord village = VillageRegistryData.get(level).getOrCreateVillage(
                UUID.randomUUID(), origin, new AABB(origin).inflate(halfWidth));
        level.setBlock(origin.offset(-2, 0, -2), Blocks.COMPOSTER.defaultBlockState(), 2);
        int ring = halfWidth - 2;
        for (int item = 0; item < WORK_ITEMS; item++) {
            double angle = 2.0D * Math.PI * item / WORK_ITEMS;
            BlockPos work = origin.offset((int) Math.round(ring * Math.cos(angle)), 0,
                    (int) Math.round(ring * Math.sin(angle)));
            level.setBlock(work.below(), Blocks.DIRT.defaultBlockState(), 2);
            village.addFarmland(work);
            village.addToRepairQueue(work);
        }

        Villager farmer = EntityCreation.create(EntityType.VILLAGER, level);
        farmer.moveTo(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D, 0.0F, 0.0F);
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        // Vanilla resets a level-one, zero-XP farmer that has no job-site memory yet.
        farmer.setVillagerXp(1);
        level.addFreshEntity(farmer);
        if (!village.hasMember(farmer.getUUID())) {
            village.addMember(new VillagerPOIRecord(
                    farmer.getUUID(), farmer.getName().getString(), "FARMER",
                    null, null, null, farmer.getHealth(), VillagerPOIRecord.Status.ACTIVE,
                    0, level.getGameTime()));
        }
        village.refreshFarmerRoster(farmer);
        watch = new Watch(level, village.getVillageId(), farmer.getUUID());
        return farmer;
    }

    /** A read-only summary of durable work beside the leases and claims held right now. */
    public static String describe(VillageRecord village) {
        List<String> leases = new ArrayList<>();
        for (UUID member : village.getMembersSnapshot().keySet()) {
            VillageWorkCell cell = village.getWorkCellLeasedTo(member);
            if (cell != null) {
                leases.add(member + "@" + cell.getKey());
            }
        }
        return "village=" + village.getVillageId()
                + " durableRepairs=" + village.getRepairQueue().size()
                + " durableMissingDoors=" + village.getMissingDoorRegistry().size()
                + " leases=" + leases
                + " repairClaims=" + village.getClaimedPositions().size()
                + " doorClaims=" + village.getClaimedDoorPositions().size();
    }

    /** Called by the session cleanup owner. */
    public static void clearWatch() {
        watch = null;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Watch active = watch;
        if (active == null || active.level().getGameTime() % WATCH_INTERVAL_TICKS != 0L) {
            return;
        }
        VillageRecord village = VillageRegistryData.get(active.level()).getVillages().get(active.villageId());
        if (village == null || village.getWorkCellLeasedTo(active.farmerId()) == null) {
            return;
        }
        EmeraldCapitalism.LOGGER.info("[ECAP][StateReload] MID_JOB farmer={} {}",
                active.farmerId(), describe(village));
        watch = null;
    }
}
