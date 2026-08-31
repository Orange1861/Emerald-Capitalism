package com.orangevillager61.emeraldcapitalism.attachments;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerStatsAttachmentCodecTest {

    @Test
    void defaultAttachmentRoundTripsWithDurableDefaults() {
        VillagerStatsAttachment decoded = roundTrip(new VillagerStatsAttachment());

        assertEquals(20, decoded.getHungerLevel());
        assertEquals(0L, decoded.getLastAteTime());
        assertEquals(0, decoded.getTicksSinceLastHungerDecrease());
        assertEquals(0, decoded.getTicksSinceLastHeal());
        assertEquals(0, decoded.getTicksSinceLastStarvationDamage());
        assertEquals(0L, decoded.getLastBegTime());
        assertEquals(0, decoded.getBegTime());
        assertEquals(null, decoded.getBegDonorUUID());
        assertEquals(null, decoded.getVillagerName());
        assertEquals(null, decoded.getParent1UUID());
        assertEquals(null, decoded.getParent2UUID());
        assertEquals(null, decoded.getParent1Name());
        assertEquals(null, decoded.getParent2Name());
        assertTrue(decoded.getChildrenUUIDs().isEmpty());
        assertTrue(decoded.getGrandparentUUIDs().isEmpty());
        assertEquals(0, decoded.getEmeraldBalance());
    }

    @Test
    void fullyPopulatedDurableStateRoundTripsExactly() {
        VillagerStatsAttachment source = new VillagerStatsAttachment();
        UUID donor = UUID.randomUUID();
        UUID parent1 = UUID.randomUUID();
        UUID parent2 = UUID.randomUUID();
        UUID child1 = UUID.randomUUID();
        UUID child2 = UUID.randomUUID();
        UUID grandparent1 = UUID.randomUUID();
        UUID grandparent2 = UUID.randomUUID();
        UUID grandparent3 = UUID.randomUUID();
        UUID namingVillage = UUID.randomUUID();

        source.setHungerLevel(7);
        source.setLastAteTime(1234L);
        source.setTicksSinceLastHungerDecrease(12);
        source.setTicksSinceLastHeal(13);
        source.setTicksSinceLastStarvationDamage(14);
        source.setLastBegTime(5678L);
        source.setBegTime(9);
        source.setBegDonorUUID(donor);
        source.setVillagerName("Mara");
        source.setPersonalFirstElement("bem");
        source.setPersonalSecondElement("mun");
        source.setNamingVillageId(namingVillage);
        source.setParent1UUID(parent1);
        source.setParent2UUID(parent2);
        source.setParent1Name("Parent One");
        source.setParent2Name("Parent Two");
        source.addChild(child1);
        source.addChild(child2);
        source.addGrandparent(grandparent1);
        source.addGrandparent(grandparent2);
        source.addGrandparent(grandparent3);
        source.setEmeraldBalance(-17);

        VillagerStatsAttachment decoded = roundTrip(source);

        assertEquals(7, decoded.getHungerLevel());
        assertEquals(1234L, decoded.getLastAteTime());
        assertEquals(12, decoded.getTicksSinceLastHungerDecrease());
        assertEquals(13, decoded.getTicksSinceLastHeal());
        assertEquals(14, decoded.getTicksSinceLastStarvationDamage());
        assertEquals(5678L, decoded.getLastBegTime());
        assertEquals(9, decoded.getBegTime());
        assertEquals(donor, decoded.getBegDonorUUID());
        // The assembled display string is derived and is intentionally not
        // persisted. Only the substrate pair and village identity survive.
        assertEquals(null, decoded.getVillagerName());
        assertEquals("bem", decoded.getPersonalFirstElement());
        assertEquals("mun", decoded.getPersonalSecondElement());
        assertEquals(namingVillage, decoded.getNamingVillageId());
        assertEquals(parent1, decoded.getParent1UUID());
        assertEquals(parent2, decoded.getParent2UUID());
        assertEquals("Parent One", decoded.getParent1Name());
        assertEquals("Parent Two", decoded.getParent2Name());
        assertEquals(List.of(child1, child2), decoded.getChildrenUUIDs());
        assertEquals(List.of(grandparent1, grandparent2, grandparent3), decoded.getGrandparentUUIDs());
        assertEquals(-17, decoded.getEmeraldBalance());

        CompoundTag encoded = (CompoundTag) VillagerStatsAttachment.CODEC
                .encodeStart(NbtOps.INSTANCE, source).result().orElseThrow();
        assertFalse(encoded.contains("villager_name"));
    }

    @Test
    void specialFirstNamePersistsWithoutASubstratePair() {
        VillagerStatsAttachment source = new VillagerStatsAttachment();
        UUID namingVillage = UUID.randomUUID();
        source.setSpecialFirstName("Kinniken");
        source.setNamingVillageId(namingVillage);

        VillagerStatsAttachment decoded = roundTrip(source);

        assertEquals("Kinniken", decoded.getSpecialFirstName());
        assertEquals(namingVillage, decoded.getNamingVillageId());
        assertTrue(decoded.hasAssignedFirstName());
        assertFalse(decoded.hasPersonalNameSlot());

        CompoundTag encoded = (CompoundTag) VillagerStatsAttachment.CODEC
                .encodeStart(NbtOps.INSTANCE, source).result().orElseThrow();
        assertEquals("Kinniken", encoded.getCompound("villager_naming")
                .getString("special_first_name"));
    }

    @Test
    void wanderingTraderNamingStateRoundTripsWithBoundedShiftRules() {
        VillagerStatsAttachment source = new VillagerStatsAttachment();
        source.setPersonalFirstElement("bem");
        source.setPersonalSecondElement("mun");
        source.setWanderingTraderDriftRules(List.of("drop_final_nasal", "lower_i_to_e"));
        source.setGeneratedWanderingTraderName("Bemun Wantreidi");

        VillagerStatsAttachment decoded = roundTrip(source);

        assertEquals(List.of("drop_final_nasal", "lower_i_to_e"), decoded.getWanderingTraderDriftRules());
        assertEquals("Bemun Wantreidi", decoded.getGeneratedWanderingTraderName());
    }

    @Test
    void invalidWanderingTraderRulesAndGrandparentsFailLoudly() {
        VillagerStatsAttachment attachment = new VillagerStatsAttachment();

        assertThrows(NullPointerException.class,
                () -> attachment.setWanderingTraderDriftRules(null));
        assertThrows(NullPointerException.class,
                () -> attachment.setWanderingTraderDriftRules(java.util.Arrays.asList("valid", null)));
        assertThrows(IllegalArgumentException.class,
                () -> attachment.setWanderingTraderDriftRules(java.util.Collections.nCopies(13, "valid")));
        assertThrows(NullPointerException.class,
                () -> attachment.addGrandparent(null));
    }

    @Test
    void transientEatingAndInventoryStateResetsAfterDecode() throws ReflectiveOperationException {
        VillagerStatsAttachment source = new VillagerStatsAttachment();
        setPrivate(source, "isEating", true);
        setPrivate(source, "eatingTicksRemaining", 32);
        setPrivate(source, "eatingSlot", 3);
        setPrivate(source, "eatingNutrition", 6);
        source.setCachedFoodSlot(3);
        setPrivate(source, "cachedEmeraldCount", 4);
        setPrivate(source, "cachedWheatCount", 9);
        setPrivate(source, "cachedBreadCount", 2);
        setPrivate(source, "cachedPumpkinCount", 1);

        VillagerStatsAttachment decoded = roundTrip(source);

        assertFalse(decoded.isEating());
        assertEquals(null, getPrivate(decoded, "eatingItem"));
        assertEquals(0, decoded.getEatingTicksRemaining());
        assertEquals(-1, decoded.getEatingSlot());
        assertEquals(0, decoded.finishEating());
        assertEquals(-1, decoded.getCachedFoodSlot());
        assertEquals(-1, decoded.getCachedEmeraldCount());
        assertEquals(-1, decoded.getCachedWheatCount());
        assertEquals(-1, decoded.getCachedBreadCount());
        assertEquals(-1, decoded.getCachedPumpkinCount());
    }

    @Test
    void malformedInputProducesCodecErrorsAndOutOfRangeHungerIsClamped() {
        CompoundTag belowMinimum = new CompoundTag();
        belowMinimum.putInt("hunger_level", -4);
        assertEquals(0, parse(belowMinimum).getHungerLevel());

        CompoundTag aboveMaximum = new CompoundTag();
        aboveMaximum.putInt("hunger_level", 40);
        assertEquals(20, parse(aboveMaximum).getHungerLevel());

        assertEquals(20, parse(new CompoundTag()).getHungerLevel());

        CompoundTag malformedUuid = new CompoundTag();
        malformedUuid.putString("parent_1_uuid", "not-a-uuid");
        assertTrue(VillagerStatsAttachment.CODEC.parse(NbtOps.INSTANCE, malformedUuid).error().isPresent());

        CompoundTag malformedList = new CompoundTag();
        ListTag children = new ListTag();
        children.add(StringTag.valueOf("not-a-uuid"));
        malformedList.put("children_uuids", children);
        assertTrue(VillagerStatsAttachment.CODEC.parse(NbtOps.INSTANCE, malformedList).error().isPresent());

        CompoundTag invalidNumericForm = new CompoundTag();
        invalidNumericForm.putString("beg_time", "not-an-int");
        assertTrue(VillagerStatsAttachment.CODEC.parse(NbtOps.INSTANCE, invalidNumericForm).error().isPresent());
    }

    @Test
    void familyCollectionsAreCopiedAndDuplicateRulesRemainUnchanged() {
        VillagerStatsAttachment source = new VillagerStatsAttachment();
        UUID child = UUID.randomUUID();
        UUID grandparent = UUID.randomUUID();
        source.addChild(child);
        source.addChild(child);
        source.addGrandparent(grandparent);
        source.addGrandparent(grandparent);

        VillagerStatsAttachment decoded = roundTrip(source);
        List<UUID> decodedChildren = decoded.getChildrenUUIDs();
        List<UUID> decodedGrandparents = decoded.getGrandparentUUIDs();
        decodedChildren.clear();
        decodedGrandparents.clear();

        assertEquals(List.of(child), source.getChildrenUUIDs());
        assertEquals(List.of(grandparent), source.getGrandparentUUIDs());
        assertEquals(List.of(child), decoded.getChildrenUUIDs());
        assertEquals(List.of(grandparent), decoded.getGrandparentUUIDs());
        assertNotSame(decodedChildren, decoded.getChildrenUUIDs());
        assertNotSame(decodedGrandparents, decoded.getGrandparentUUIDs());
    }

    @Test
    void familyDecodeDeduplicatesUuidsAndRejectsMoreThanFourGrandparents() {
        UUID child = UUID.randomUUID();
        UUID grandparent = UUID.randomUUID();
        CompoundTag duplicated = new CompoundTag();
        ListTag children = new ListTag();
        children.add(net.minecraft.nbt.NbtUtils.createUUID(child));
        children.add(net.minecraft.nbt.NbtUtils.createUUID(child));
        duplicated.put("children_uuids", children);
        ListTag grandparents = new ListTag();
        grandparents.add(net.minecraft.nbt.NbtUtils.createUUID(grandparent));
        grandparents.add(net.minecraft.nbt.NbtUtils.createUUID(grandparent));
        duplicated.put("grandparent_uuids", grandparents);

        VillagerStatsAttachment decoded = parse(duplicated);
        assertEquals(List.of(child), decoded.getChildrenUUIDs());
        assertEquals(List.of(grandparent), decoded.getGrandparentUUIDs());

        CompoundTag oversized = new CompoundTag();
        ListTag tooManyGrandparents = new ListTag();
        for (int i = 0; i < 5; i++) {
            tooManyGrandparents.add(net.minecraft.nbt.NbtUtils.createUUID(UUID.randomUUID()));
        }
        oversized.put("grandparent_uuids", tooManyGrandparents);
        assertTrue(VillagerStatsAttachment.CODEC.parse(NbtOps.INSTANCE, oversized).error().isPresent());
    }

    @Test
    void persistedFamilyAndNamingBoundariesRejectOversizedInput() {
        CompoundTag oversizedChildren = new CompoundTag();
        ListTag children = new ListTag();
        for (int i = 0; i <= VillagerStatsAttachment.MAX_PERSISTED_CHILDREN; i++) {
            children.add(NbtUtils.createUUID(new UUID(0L, i)));
        }
        oversizedChildren.put("children_uuids", children);
        assertTrue(VillagerStatsAttachment.CODEC.parse(NbtOps.INSTANCE, oversizedChildren).error().isPresent());

        CompoundTag oversizedParentName = new CompoundTag();
        oversizedParentName.putString("parent_1_name", "p".repeat(65));
        assertTrue(VillagerStatsAttachment.CODEC.parse(NbtOps.INSTANCE, oversizedParentName).error().isPresent());

        CompoundTag oversizedNamingElement = new CompoundTag();
        CompoundTag naming = new CompoundTag();
        naming.putString("special_first_name", "n".repeat(65));
        oversizedNamingElement.put("villager_naming", naming);
        assertTrue(VillagerStatsAttachment.CODEC.parse(NbtOps.INSTANCE, oversizedNamingElement).error().isPresent());
    }

    @Test
    void persistedStringSettersClampToAcceptedBoundaries() {
        VillagerStatsAttachment source = new VillagerStatsAttachment();
        source.setParent1Name("p".repeat(65));
        source.setSpecialFirstName("n".repeat(65));

        VillagerStatsAttachment decoded = roundTrip(source);
        assertEquals("p".repeat(64), decoded.getParent1Name());
        assertEquals("n".repeat(64), decoded.getSpecialFirstName());
    }

    private static VillagerStatsAttachment roundTrip(VillagerStatsAttachment source) {
        return parse((CompoundTag) VillagerStatsAttachment.CODEC
                .encodeStart(NbtOps.INSTANCE, source)
                .result()
                .orElseThrow());
    }

    private static VillagerStatsAttachment parse(CompoundTag tag) {
        return VillagerStatsAttachment.CODEC.parse(NbtOps.INSTANCE, tag).result().orElseThrow();
    }

    private static void setPrivate(VillagerStatsAttachment attachment, String fieldName, Object value)
            throws ReflectiveOperationException {
        var field = VillagerStatsAttachment.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(attachment, value);
    }

    private static Object getPrivate(VillagerStatsAttachment attachment, String fieldName)
            throws ReflectiveOperationException {
        var field = VillagerStatsAttachment.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(attachment);
    }
}
