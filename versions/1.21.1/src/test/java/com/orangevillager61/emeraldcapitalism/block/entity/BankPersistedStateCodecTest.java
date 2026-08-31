package com.orangevillager61.emeraldcapitalism.block.entity;

import com.orangevillager61.emeraldcapitalism.network.ProtocolStringLimits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankPersistedStateCodecTest {

    private static final UUID VILLAGE_ID = UUID.fromString("12345678-1234-5678-1234-567812345678");
    private static final UUID EMPLOYEE_ONE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID EMPLOYEE_TWO = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID EMPLOYEE_THREE = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID OUTSIDE_EMPLOYEE = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final UUID TAKEOVER_KILLER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    void codecRoundTripsEmptyAndFullyPopulatedDurableState() {
        assertEquals(BankBlockEntity.PersistedState.empty(),
                roundTrip(BankBlockEntity.PersistedState.empty()));

        List<UUID> golemIds = IntStream.range(0, BankBlockEntity.MAX_PERSISTED_GOLEM_EMPLOYEES)
                .mapToObj(index -> new UUID(0x1000L, index))
                .toList();
        BankBlockEntity.PersistedState full = new BankBlockEntity.PersistedState(
                Optional.of(VILLAGE_ID),
                "  " + "B".repeat(ProtocolStringLimits.MAX_BANK_NAME_LENGTH) + "  ",
                List.of(EMPLOYEE_ONE, EMPLOYEE_TWO, EMPLOYEE_THREE),
                List.of(EMPLOYEE_ONE, EMPLOYEE_TWO, EMPLOYEE_THREE),
                golemIds,
                Optional.of(new BlockPos(-30, -64, 45)),
                Optional.of(new BlockPos(-1_234_567, 319, 765_432)));

        BankBlockEntity.PersistedState decoded = roundTrip(full);
        assertEquals(VILLAGE_ID, decoded.villageId().orElseThrow());
        assertEquals("B".repeat(ProtocolStringLimits.MAX_BANK_NAME_LENGTH), decoded.bankName());
        assertEquals(List.of(EMPLOYEE_ONE, EMPLOYEE_TWO, EMPLOYEE_THREE), decoded.employeeIds());
        assertEquals(List.of(EMPLOYEE_ONE, EMPLOYEE_TWO, EMPLOYEE_THREE), decoded.jobEmployeeIds());
        assertEquals(golemIds, decoded.emeraldGolemEmployeeIds());
        assertEquals(new BlockPos(-30, -64, 45), decoded.composterPos().orElseThrow());
        assertEquals(new BlockPos(-1_234_567, 319, 765_432), decoded.golemConstructionPos().orElseThrow());
    }

    @Test
    void trackedChestLocationsAndPreparedRepairsRoundTrip() {
        List<BlockPos> locations = List.of(
                new BlockPos(1, 2, 3), new BlockPos(-4, 5, -6));
        BankBlockEntity.PersistedState source = new BankBlockEntity.PersistedState(
                Optional.of(VILLAGE_ID), true, Optional.empty(), "Bank",
                List.of(), List.of(), List.of(), locations, 2,
                Optional.empty(), Optional.empty(), Optional.empty(), 0L);

        BankBlockEntity.PersistedState decoded = roundTrip(source);
        assertEquals(locations, decoded.trackedChestPositions());
        assertEquals(2, decoded.preparedEmeraldChestCount());
    }

    @Test
    void decodeCopiesCollectionsDeduplicatesIdsAndFiltersJobsToEmployees() {
        ArrayList<UUID> mutableEmployees = new ArrayList<>(List.of(EMPLOYEE_ONE, EMPLOYEE_ONE, EMPLOYEE_TWO));
        ArrayList<UUID> mutableJobs = new ArrayList<>(List.of(OUTSIDE_EMPLOYEE, EMPLOYEE_ONE, EMPLOYEE_ONE));
        BankBlockEntity.PersistedState source = new BankBlockEntity.PersistedState(
                Optional.empty(), "Bank", mutableEmployees, mutableJobs, List.of(),
                Optional.empty(), Optional.empty());
        mutableEmployees.add(EMPLOYEE_THREE);
        mutableJobs.add(EMPLOYEE_TWO);

        BankBlockEntity.PersistedState decoded = roundTrip(source);
        assertEquals(List.of(EMPLOYEE_ONE, EMPLOYEE_TWO), decoded.employeeIds());
        assertEquals(List.of(EMPLOYEE_ONE), decoded.jobEmployeeIds());
        assertNotSame(mutableEmployees, decoded.employeeIds());
        assertNotSame(mutableJobs, decoded.jobEmployeeIds());
        assertTrue(decoded.employeeIds().contains(EMPLOYEE_ONE));
        assertFalse(decoded.jobEmployeeIds().contains(OUTSIDE_EMPLOYEE));
    }

    @Test
    void codecContainsOnlyDurableBankState() {
        CompoundTag encoded = (CompoundTag) BankBlockEntity.PersistedState.CODEC
                .encodeStart(NbtOps.INSTANCE, new BankBlockEntity.PersistedState(
                        Optional.of(VILLAGE_ID), "Bank",
                        List.of(EMPLOYEE_ONE), List.of(EMPLOYEE_ONE), List.of(EMPLOYEE_TWO),
                        Optional.of(new BlockPos(-3, 2, -4)),
                        Optional.of(new BlockPos(5, 6, 7))))
                .result()
                .orElseThrow();

        assertEquals(Set.of(
                        "village_id", "bank_name", "employee_ids", "job_employee_ids",
                        "emerald_golem_employee_ids", "composter_pos", "golem_construction_pos"),
                encoded.getAllKeys());
        assertFalse(encoded.contains("cached_chests"));
        assertFalse(encoded.contains("total_emerald_count"));
        assertFalse(encoded.contains("deposit_queue"));
        assertFalse(encoded.contains("active_golem_construction_villager"));
    }

    @Test
    void bankControllerAndIndependentFlagRoundTrip() {
        UUID controller = UUID.randomUUID();
        BankBlockEntity.PersistedState source = new BankBlockEntity.PersistedState(
                Optional.of(VILLAGE_ID), false, Optional.of(controller), "Bank",
                List.of(), List.of(), List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), 0L);

        BankBlockEntity.PersistedState decoded = roundTrip(source);
        assertFalse(decoded.bankIndependent());
        assertEquals(controller, decoded.controllerId().orElseThrow());
    }

    @Test
    void controlSettingsRoundTrip() {
        BankBlockEntity.PersistedState source = new BankBlockEntity.PersistedState(
                Optional.empty(), false, Optional.of(TAKEOVER_KILLER), "Bank",
                List.of(), List.of(), List.of(), List.of(), 0,
                Optional.empty(), Optional.empty(), Optional.empty(), 0L,
                true, 12, 8, 17, false, false, false, true, true);

        BankBlockEntity.PersistedState decoded = roundTrip(source);
        assertTrue(decoded.manualTargets());
        assertEquals(12, decoded.emeraldGolemTarget());
        assertEquals(8, decoded.emeraldSkrimisherTarget());
        assertEquals(17, decoded.foodDays());
        assertFalse(decoded.villagerDeliveriesEnabled());
        assertFalse(decoded.randomDeliveriesEnabled());
        assertFalse(decoded.breadDeliveriesEnabled());
        assertTrue(decoded.lumberjackDeliveriesEnabled());
        assertTrue(decoded.attackAllPlayers());
    }

    @Test
    void takeoverKillerLockRoundTrips() {
        BankBlockEntity.PersistedState source = new BankBlockEntity.PersistedState(
                Optional.of(VILLAGE_ID), true, Optional.empty(), "Bank",
                List.of(), List.of(), List.of(), Optional.empty(), Optional.empty(),
                Optional.of(TAKEOVER_KILLER), 12_345L);

        BankBlockEntity.PersistedState decoded = roundTrip(source);
        assertEquals(TAKEOVER_KILLER, decoded.takeoverLockPlayer().orElseThrow());
        assertEquals(12_345L, decoded.takeoverLockUntil());
    }

    @Test
    void missingAndMalformedBoundedFieldsFailSafely() {
        assertEquals(BankBlockEntity.PersistedState.empty(),
                BankBlockEntity.PersistedState.CODEC.parse(NbtOps.INSTANCE, new CompoundTag())
                        .result()
                        .orElseThrow());

        CompoundTag oversizedName = new CompoundTag();
        oversizedName.putString("bank_name", "x".repeat(ProtocolStringLimits.MAX_BANK_NAME_LENGTH + 1));
        assertCodecError(oversizedName);

        CompoundTag oversizedEmployees = new CompoundTag();
        ListTag employeeList = new ListTag();
        for (int i = 0; i < BankBlockEntity.MAX_EMPLOYEES + 1; i++) {
            employeeList.add(new IntArrayTag(UUIDUtil.uuidToIntArray(new UUID(0x2000L, i))));
        }
        oversizedEmployees.put("employee_ids", employeeList);
        assertCodecError(oversizedEmployees);

        CompoundTag malformedVillage = new CompoundTag();
        malformedVillage.putString("village_id", "not-a-uuid");
        assertCodecError(malformedVillage);

        CompoundTag oversizedChestLocations = new CompoundTag();
        ListTag chestLocations = new ListTag();
        for (int i = 0; i < BankBlockEntity.MAX_TRACKED_CHEST_LOCATIONS + 1; i++) {
            chestLocations.add(new IntArrayTag(new int[]{i, 0, 0}));
        }
        oversizedChestLocations.put("tracked_chest_positions", chestLocations);
        assertCodecError(oversizedChestLocations);
    }

    private static BankBlockEntity.PersistedState roundTrip(BankBlockEntity.PersistedState source) {
        CompoundTag encoded = (CompoundTag) BankBlockEntity.PersistedState.CODEC
                .encodeStart(NbtOps.INSTANCE, source)
                .result()
                .orElseThrow();
        return BankBlockEntity.PersistedState.CODEC.parse(NbtOps.INSTANCE, encoded)
                .result()
                .orElseThrow();
    }

    private static void assertCodecError(CompoundTag tag) {
        assertTrue(BankBlockEntity.PersistedState.CODEC.parse(NbtOps.INSTANCE, tag).error().isPresent(),
                "malformed bank durable state should be rejected without throwing");
    }
}
