package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldChestBlockEntity;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import com.orangevillager61.emeraldcapitalism.util.BankEmployeeLookup;
import com.orangevillager61.emeraldcapitalism.world.bank.BankReputationData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageGovernance;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-side bank security and bank-reputation hooks. */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class BankReputationEvents {

    private static final Map<UUID, OpenChestSnapshot> OPEN_CHESTS = new HashMap<>();

    private BankReputationEvents() {
    }

    /** Applies the bank's separate damage penalty to every kind of bank employee. */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.isCanceled()
                || event.getEntity().level().isClientSide()
                || !(event.getEntity().level() instanceof ServerLevel level)
                || !(event.getSource().getEntity() instanceof Player player)) {
            return;
        }

        if (event.getEntity() instanceof EmeraldGolem golem) {
            VillageGovernance.endGovernorCandidateAttackGrace(level, golem, player.getUUID());
        }

        if (!isBankEmployee(level, event.getEntity())) {
            return;
        }

        BankReputationData.get(level).adjustReputation(
                player.getUUID(), calculateDamagePenalty(event.getOriginalAmount()));
    }

    /** Uses the same 5-points-per-HP rule as villager reputation when enabled. */
    static int calculateDamagePenalty(float originalDamage) {
        if (!Config.proportionalVillagerReputation) {
            return -10;
        }
        return -Math.max(1, Math.round(originalDamage * 5.0F));
    }

    private static boolean isBankEmployee(ServerLevel level, LivingEntity entity) {
        if (entity instanceof EmeraldGolem emeraldGolem) {
            return emeraldGolem.getBankEmployeePos() != null;
        }
        return entity instanceof Villager villager && BankEmployeeLookup.isEmployee(level, villager);
    }

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getContainer() instanceof ChestMenu chestMenu)
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }

        List<EmeraldChestBlockEntity> bankChests = findBankChests(level, chestMenu.getContainer());
        if (bankChests.isEmpty()) {
            return;
        }
        OPEN_CHESTS.put(player.getUUID(), OpenChestSnapshot.capture(bankChests));
    }

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }

        OpenChestSnapshot snapshot = OPEN_CHESTS.remove(player.getUUID());
        if (snapshot == null || !snapshot.contentsWereRemoved()) {
            return;
        }

        BankReputationData.get(level).adjustReputation(
                player.getUUID(), BankReputationData.EMERALD_CHEST_WITHDRAWAL_PENALTY);
    }

    /** Clears stale per-player state when a player disconnects or changes dimension. */
    public static void clearPlayer(UUID playerId) {
        OPEN_CHESTS.remove(playerId);
    }

    /**
     * Finds the emerald chest containers represented by a chest menu and
     * verifies that each is currently linked to a registered bank.
     */
    private static List<EmeraldChestBlockEntity> findBankChests(ServerLevel level, Container opened) {
        List<EmeraldChestBlockEntity> result = new ArrayList<>();
        VillageRegistryData registry = VillageRegistryData.get(level);

        for (VillageRecord village : registry.getVillages().values()) {
            if (registry.getBankPos(village.getVillageId()) == null) {
                continue;
            }
            if (!(level.getBlockEntity(registry.getBankPos(village.getVillageId()))
                    instanceof BankBlockEntity bank)) {
                continue;
            }

            for (var chestPos : bank.getCachedChestPositions()) {
                if (!(level.getBlockEntity(chestPos) instanceof EmeraldChestBlockEntity chest)
                        || !containsContainer(opened, chest)) {
                    continue;
                }
                result.add(chest);
            }
        }
        return result;
    }

    private static boolean containsContainer(Container opened, EmeraldChestBlockEntity chest) {
        return opened == chest
                || opened instanceof CompoundContainer compound && compound.contains(chest);
    }

    private record ChestState(EmeraldChestBlockEntity chest, List<ItemStack> contents) {
        private static ChestState capture(EmeraldChestBlockEntity chest) {
            List<ItemStack> contents = new ArrayList<>(chest.getContainerSize());
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                contents.add(chest.getItem(slot).copy());
            }
            return new ChestState(chest, contents);
        }

        private boolean contentsWereRemoved() {
            if (chest.isRemoved()) {
                return false;
            }
            for (int slot = 0; slot < contents.size(); slot++) {
                ItemStack before = contents.get(slot);
                ItemStack after = chest.getItem(slot);
                if (before.isEmpty()) {
                    continue;
                }
                if (after.isEmpty()
                        || !ItemStack.isSameItemSameComponents(before, after)
                        || after.getCount() < before.getCount()) {
                    return true;
                }
            }
            return false;
        }
    }

    private record OpenChestSnapshot(List<ChestState> chests) {
        private static OpenChestSnapshot capture(List<EmeraldChestBlockEntity> chests) {
            return new OpenChestSnapshot(chests.stream().map(ChestState::capture).toList());
        }

        private boolean contentsWereRemoved() {
            return chests.stream().anyMatch(ChestState::contentsWereRemoved);
        }
    }
}
