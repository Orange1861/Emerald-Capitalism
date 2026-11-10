package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.command.StateReloadScenarioCommand;
import com.orangevillager61.emeraldcapitalism.util.EntityDamageUtils;
import com.orangevillager61.emeraldcapitalism.world.village.VillagerPOIRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageWorkCell;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
//? if >=1.21.4 {
/*import net.minecraft.world.level.portal.TeleportTransition;
*///?} else {
import net.minecraft.world.level.portal.DimensionTransition;
//?}
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Exercises the real persistence codecs and lifecycle entry points of transient villager state. */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class VillagerSessionCleanupGameTests {

    private static final int SETTLE_TICKS = 5;

    private VillagerSessionCleanupGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void savedStatsKeepBegCooldownButNotTheLiveBeggingSession(GameTestHelper helper) {
        VillagerStatsAttachment stats = new VillagerStatsAttachment();
        stats.setLastBegTime(1234L);
        stats.setBegTime(17);
        stats.setBegDonorUUID(UUID.randomUUID());

        CompoundTag encoded = (CompoundTag) VillagerStatsAttachment.CODEC
                .encodeStart(NbtOps.INSTANCE, stats).result().orElseThrow();
        helper.assertFalse(encoded.contains("beg_time") || encoded.contains("beg_donor_uuid"),
                "the live begging session was serialized: " + encoded);
        helper.assertTrue(encoded.getLong("last_beg_time") == 1234L,
                "the durable begging cooldown was not serialized: " + encoded);

        // A save written before this schema still carries the old session fields.
        encoded.putInt("beg_time", 17);
        encoded.putUUID("beg_donor_uuid", UUID.randomUUID());
        VillagerStatsAttachment decoded = VillagerStatsAttachment.CODEC
                .parse(NbtOps.INSTANCE, encoded).result().orElseThrow();
        helper.assertTrue(decoded.getBegTime() == 0 && decoded.getBegDonorUUID() == null,
                "a reloaded villager resumed a pre-reload begging session");
        helper.assertTrue(decoded.getLastBegTime() == 1234L,
                "the begging cooldown did not survive reload");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void savedVillageKeepsWorkInventoryButNoLeaseOrClaim(GameTestHelper helper) {
        installFloor(helper);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            Held held = hold(helper, 2, new BlockPos(0, 0, 0), new BlockPos(9, 3, 19));
            assertHeld(helper, held, "fixture");

            CompoundTag encoded = (CompoundTag) VillageRecord.CODEC
                    .encodeStart(NbtOps.INSTANCE, held.village()).result().orElseThrow();
            VillageRecord decoded = VillageRecord.CODEC
                    .parse(NbtOps.INSTANCE, encoded).result().orElseThrow();

            helper.assertTrue(decoded.getRepairQueue().contains(held.work()),
                    "durable repair work did not survive reload");
            helper.assertTrue(decoded.getMissingDoorRegistry().contains(held.door()),
                    "durable missing-door work did not survive reload");
            VillageWorkCell cell = decoded.getWorkCell(held.work());
            helper.assertTrue(cell != null && cell.getPendingRepairCount() > 0,
                    "durable work did not return to its work cell");
            helper.assertTrue(cell.getActiveLease() == null,
                    "a work-cell lease survived reload");
            helper.assertTrue(decoded.getWorkCellLeasedTo(held.villager().getUUID()) == null,
                    "a farmer lease survived reload");
            helper.assertTrue(decoded.getClaimedPositions().isEmpty(),
                    "a repair claim survived reload");
            helper.assertTrue(decoded.getClaimedDoorPositions().isEmpty(),
                    "a door claim survived reload");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_20x3x20")
    public static void removalReleasesAllSessionStateAndOnlyThatVillagers(GameTestHelper helper) {
        installFloor(helper);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            Held target = hold(helper, 2, new BlockPos(0, 0, 0), new BlockPos(9, 3, 19));
            Held bystander = hold(helper, 12, new BlockPos(10, 0, 0), new BlockPos(19, 3, 19));

            target.villager().discard();

            assertWorkReleased(helper, target, "removal");
            helper.assertFalse(VillagerLadderClaims.isOwnedBy(target.villager(), target.ladder()),
                    "removal left the ladder claim");
            helper.assertTrue(MovementRequestManager.activeHandle(target.villager()) == null,
                    "removal left the active movement request");
            assertHeld(helper, bystander, "bystander after removal");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_20x3x20")
    public static void deathReleasesWorkImmediatelyAndOnlyThatVillagers(GameTestHelper helper) {
        installFloor(helper);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            Held target = hold(helper, 2, new BlockPos(0, 0, 0), new BlockPos(9, 3, 19));
            Held bystander = hold(helper, 12, new BlockPos(10, 0, 0), new BlockPos(19, 3, 19));
            VillagerStatsAttachment stats = target.villager()
                    .getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
            stats.setBegDonorUUID(bystander.villager().getUUID());
            target.villager().setHealth(1.0F);

            EntityDamageUtils.hurt(target.villager(),
                    helper.getLevel().damageSources().generic(), 1000.0F);

            helper.assertTrue(target.villager().isDeadOrDying(), "fixture villager did not die");
            assertWorkReleased(helper, target, "death");
            helper.assertTrue(stats.getBegDonorUUID() == null,
                    "death left the begging session");
            assertHeld(helper, bystander, "bystander after death");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_20x3x20")
    public static void professionChangeEndsOldWorkButNotTheLadderTraversal(GameTestHelper helper) {
        installFloor(helper);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            Held target = hold(helper, 2, new BlockPos(0, 0, 0), new BlockPos(9, 3, 19));
            Held bystander = hold(helper, 12, new BlockPos(10, 0, 0), new BlockPos(19, 3, 19));

            target.villager().setVillagerData(target.villager().getVillagerData()
                    .setProfession(VillagerProfession.MASON));

            assertWorkReleased(helper, target, "profession change");
            helper.assertTrue(MovementRequestManager.activeHandle(target.villager()) == null,
                    "profession change left the old job's movement request");
            helper.assertTrue(VillagerLadderClaims.isOwnedBy(target.villager(), target.ladder()),
                    "profession change dropped a route-level ladder claim mid-traversal");
            assertHeld(helper, bystander, "bystander after profession change");

            // Reassigning the same profession is not a change and must not release anything.
            bystander.villager().setVillagerData(bystander.villager().getVillagerData()
                    .setProfession(VillagerProfession.FARMER));
            assertHeld(helper, bystander, "bystander after an unchanged profession");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_20x3x20")
    public static void dimensionChangeReleasesTheOldLevelsSessionState(GameTestHelper helper) {
        installFloor(helper);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
            helper.assertTrue(nether != null, "fixture server has no Nether level");
            Held target = hold(helper, 2, new BlockPos(0, 0, 0), new BlockPos(9, 3, 19));
            Held bystander = hold(helper, 12, new BlockPos(10, 0, 0), new BlockPos(19, 3, 19));

            //? if >=1.21.4 {
            /*Entity moved = target.villager().teleport(new TeleportTransition(
                    nether, new Vec3(0.5D, 64.0D, 0.5D), Vec3.ZERO, 0.0F, 0.0F,
                    TeleportTransition.DO_NOTHING));
            *///?} else {
            Entity moved = target.villager().changeDimension(new DimensionTransition(
                    nether, new Vec3(0.5D, 64.0D, 0.5D), Vec3.ZERO, 0.0F, 0.0F,
                    DimensionTransition.DO_NOTHING));
            //?}
            helper.assertTrue(moved != null && moved != target.villager()
                            && moved.level() == nether,
                    "fixture villager did not change dimension");
            moved.discard();

            helper.assertTrue(target.villager().getRemovalReason()
                            == Entity.RemovalReason.CHANGED_DIMENSION,
                    "fixture did not end the old entity with CHANGED_DIMENSION");
            assertWorkReleased(helper, target, "dimension change");
            helper.assertFalse(VillagerLadderClaims.isOwnedBy(target.villager(), target.ladder()),
                    "dimension change left the old level's ladder claim");
            assertHeld(helper, bystander, "bystander after dimension change");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_20x3x20")
    public static void levelUnloadReleasesOnlyThatLevelsSessionState(GameTestHelper helper) {
        installFloor(helper);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
            helper.assertTrue(nether != null, "fixture server has no Nether level");
            Held overworld = hold(helper, 2, new BlockPos(0, 0, 0), new BlockPos(9, 3, 19));
            UUID netherOwner = UUID.randomUUID();
            BlockPos netherLog = new BlockPos(1, 70, 1);
            LumberjackTreeReservations.tryReserve(nether, netherOwner, List.of(netherLog));
            VillagerLadderClaims.LadderColumn netherColumn = new VillagerLadderClaims.LadderColumn(
                    Level.NETHER, 1, 1, 70, 72);
            helper.assertTrue(VillagerLadderClaims.tryClaim(overworld.villager(), netherColumn),
                    "fixture could not claim the Nether column");

            VillagerSessionCleanup.onLevelUnload(new LevelEvent.Unload(nether));

            helper.assertFalse(LumberjackTreeReservations.isLogReservedByOther(
                            nether, UUID.randomUUID(), netherLog),
                    "Nether unload left its tree reservation");
            helper.assertFalse(VillagerLadderClaims.isOwnedBy(overworld.villager(), netherColumn),
                    "Nether unload left its ladder claim");
            assertHeld(helper, overworld, "overworld state after Nether unload");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_20x3x20")
    public static void oneVillageCannotReleaseAnotherVillagesWork(GameTestHelper helper) {
        installFloor(helper);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            Held first = hold(helper, 2, new BlockPos(0, 0, 0), new BlockPos(9, 3, 19));
            Held second = hold(helper, 12, new BlockPos(10, 0, 0), new BlockPos(19, 3, 19));
            UUID secondId = second.villager().getUUID();

            helper.assertFalse(first.village().removeFarmerFromRoster(secondId),
                    "a village reported releasing a farmer it does not hold");
            helper.assertFalse(first.village().releaseWorkLease(secondId, second.work()),
                    "a village released another village's work lease");
            helper.assertFalse(first.village().releaseDoorClaims(secondId),
                    "a village released another village's door claim");
            assertHeld(helper, second, "second village after foreign release attempts");

            // Each record releases its own work without reaching across villages.
            helper.assertTrue(first.village().removeFarmerFromRoster(first.villager().getUUID()),
                    "a village could not release its own farmer");
            helper.assertTrue(first.village().releaseDoorClaims(first.villager().getUUID()),
                    "a village could not release its own door claim");
            assertHeld(helper, second, "second village after the first released its own work");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_20x3x20", timeoutTicks = 400)
    public static void stateReloadScenarioReachesALeaseAndInspectionDoesNotChangeIt(
            GameTestHelper helper) {
        BlockPos origin = helper.absolutePos(new BlockPos(9, 1, 9));
        Villager farmer = StateReloadScenarioCommand.start(helper.getLevel(), origin, 8);
        VillageRecord village = VillageRegistryData.get(helper.getLevel()).getVillageFor(origin);
        helper.assertTrue(village != null && village.hasMember(farmer.getUUID()),
                "scenario did not register its farmer in a village");
        awaitScenarioLease(helper, village, farmer, 300);
    }

    private static void awaitScenarioLease(GameTestHelper helper, VillageRecord village,
                                           Villager farmer, int remainingTicks) {
        if (village.getWorkCellLeasedTo(farmer.getUUID()) != null) {
            int repairs = village.getRepairQueue().size();
            String report = StateReloadScenarioCommand.describe(village);
            helper.assertTrue(report.contains(farmer.getUUID().toString()),
                    "inspection did not report the farmer's lease: " + report);
            helper.assertTrue(village.getWorkCellLeasedTo(farmer.getUUID()) != null
                            && village.getRepairQueue().size() == repairs,
                    "inspection changed lease or work state");

            CompoundTag encoded = (CompoundTag) VillageRecord.CODEC
                    .encodeStart(NbtOps.INSTANCE, village).result().orElseThrow();
            VillageRecord reloaded = VillageRecord.CODEC
                    .parse(NbtOps.INSTANCE, encoded).result().orElseThrow();
            helper.assertFalse(StateReloadScenarioCommand.describe(reloaded)
                            .contains(farmer.getUUID().toString()),
                    "the scenario's lease survived reload");
            helper.assertTrue(reloaded.getRepairQueue().size() == repairs,
                    "the scenario's durable work did not survive reload");
            helper.succeed();
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("scenario farmer never received a lease: "
                    + StateReloadScenarioCommand.describe(village));
            return;
        }
        helper.runAfterDelay(1, () -> awaitScenarioLease(helper, village, farmer, remainingTicks - 1));
    }

    private record Held(VillageRecord village, Villager villager, BlockPos work, BlockPos door,
                        BlockPos log, VillagerLadderClaims.LadderColumn ladder) {
    }

    /** Gives one farmer a real lease, repair claim, door claim, reservation, ladder, and request. */
    private static Held hold(GameTestHelper helper, int localX, BlockPos min, BlockPos max) {
        ServerLevel level = helper.getLevel();
        VillageRecord village = VillageRegistryData.get(level).getOrCreateVillage(
                UUID.randomUUID(), helper.absolutePos(new BlockPos(localX, 1, 2)),
                new AABB(helper.absolutePos(min).getX(), helper.absolutePos(min).getY(),
                        helper.absolutePos(min).getZ(),
                        helper.absolutePos(max).getX() + 1.0D, helper.absolutePos(max).getY() + 1.0D,
                        helper.absolutePos(max).getZ() + 1.0D));
        BlockPos work = helper.absolutePos(new BlockPos(localX + 1, 1, 2));
        village.addFarmland(work);
        village.addToRepairQueue(work);
        BlockPos door = helper.absolutePos(new BlockPos(localX + 1, 1, 8));
        village.addDoor(door, Blocks.OAK_DOOR.defaultBlockState());
        helper.assertTrue(village.markDoorMissing(door), "fixture door was not marked missing");

        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER,
                new BlockPos(localX, 1, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        villager.setVillagerXp(1);
        village.addMember(new VillagerPOIRecord(villager.getUUID(), villager.getName().getString(), "FARMER",
                null, null, null, villager.getHealth(), VillagerPOIRecord.Status.ACTIVE,
                0, level.getGameTime()));
        helper.assertTrue(village.refreshFarmerRoster(villager), "fixture farmer was not rostered");

        UUID id = villager.getUUID();
        village.dispatchFarmerWork(level, level.getGameTime());
        helper.assertTrue(village.getWorkCellLeasedTo(id) != null, "fixture farmer received no lease");
        helper.assertTrue(village.claimRepairPosition(id, work), "fixture farmer could not claim work");
        helper.assertTrue(village.claimDoorPosition(door, id), "fixture farmer could not claim a door");

        BlockPos log = helper.absolutePos(new BlockPos(localX, 1, 12));
        helper.assertTrue(LumberjackTreeReservations.tryReserve(level, id, List.of(log)),
                "fixture farmer could not reserve a log");

        BlockPos ladderPos = villager.blockPosition();
        level.setBlock(ladderPos, Blocks.LADDER.defaultBlockState(), 2);
        VillagerLadderClaims.LadderColumn ladder = VillagerLadderClaims.column(villager, ladderPos);
        helper.assertTrue(VillagerLadderClaims.tryClaim(villager, ladder),
                "fixture farmer could not claim a ladder");

        MovementRequestSubmission request = MovementRequestManager.submit(villager,
                new MovementRequestSpec("regression:session-cleanup", "session-cleanup",
                        helper.absolutePos(new BlockPos(localX, 1, 6)), 0.6F, 1,
                        "social-interaction-v1", "ground-ladder-v1",
                        MovementRequestManager.PRIORITY_WORK, null));
        helper.assertTrue(request.accepted(), "fixture movement request was rejected");
        return new Held(village, villager, work, door, log, ladder);
    }

    private static void assertHeld(GameTestHelper helper, Held held, String stage) {
        UUID id = held.villager().getUUID();
        helper.assertTrue(held.village().getWorkCellLeasedTo(id) != null, stage + ": lease lost");
        helper.assertTrue(held.village().getClaimedPositions().contains(held.work()),
                stage + ": repair claim lost");
        helper.assertTrue(held.village().getClaimedDoorPositions().contains(held.door()),
                stage + ": door claim lost");
        helper.assertTrue(LumberjackTreeReservations.isLogReservedByOther(
                helper.getLevel(), UUID.randomUUID(), held.log()), stage + ": reservation lost");
        helper.assertTrue(VillagerLadderClaims.isOwnedBy(held.villager(), held.ladder()),
                stage + ": ladder claim lost");
        helper.assertTrue(MovementRequestManager.activeHandle(held.villager()) != null,
                stage + ": movement request lost");
    }

    /** Durable work must remain available to the next worker after every release. */
    private static void assertWorkReleased(GameTestHelper helper, Held held, String stage) {
        UUID id = held.villager().getUUID();
        helper.assertTrue(held.village().getWorkCellLeasedTo(id) == null, stage + " left the lease");
        helper.assertFalse(held.village().getClaimedPositions().contains(held.work()),
                stage + " left the repair claim");
        helper.assertFalse(held.village().getClaimedDoorPositions().contains(held.door()),
                stage + " left the door claim");
        helper.assertFalse(LumberjackTreeReservations.isLogReservedByOther(
                helper.getLevel(), UUID.randomUUID(), held.log()), stage + " left the reservation");
        helper.assertTrue(held.village().getRepairQueue().contains(held.work()),
                stage + " discarded durable repair work");
        helper.assertTrue(held.village().getMissingDoorRegistry().contains(held.door()),
                stage + " discarded durable door work");
    }

    private static void installFloor(GameTestHelper helper) {
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
    }
}
