package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.api.VillagerDamageTracker;
import com.orangevillager61.emeraldcapitalism.util.BankEmployeeLookup;
import com.orangevillager61.emeraldcapitalism.world.village.VillageGovernance;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Captures player damage for the villager reputation calculation.
 *
 * <p>NeoForge 21.1.219 fires {@link LivingIncomingDamageEvent} after
 * invulnerability checks and before damage reductions.  The original damage is
 * used deliberately: it is the same amount that the old damage-entry hook
 * recorded, rather than the post-armor health loss.</p>
 *
 * <p>This handler receives canceled events so it can explicitly ignore a
 * cancellation performed by an earlier listener.  The incoming event still
 * fires for an accepted hit whose final damage is zero (for example, a fully
 * absorbed hit); recording that hit preserves the existing reputation behavior.</p>
 */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class VillagerDamageEvents {

    private VillagerDamageEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.isCanceled()) {
            return;
        }

        if (event.getEntity().level().isClientSide()
                || !(event.getEntity().level() instanceof ServerLevel level)
                || !(event.getEntity() instanceof Villager villager)
                || !(event.getSource().getEntity() instanceof Player player)) {
            return;
        }

        // During a contested takeover, the candidate may remove the Bank's
        // employee villagers without damaging the village ledger's opinion.
        // BankReputationEvents listens independently and still penalizes the
        // Bank for the same damage.
        var employeeVillage = BankEmployeeLookup.findEmployeeVillage(level, villager);
        if (employeeVillage != null
                && VillageGovernance.isContestedGovernor(level, employeeVillage, player.getUUID())) {
            ((VillagerDamageTracker) villager)
                    .emeraldcapitalism$suppressNextPlayerDamageReputation();
            return;
        }

        ((VillagerDamageTracker) villager).emeraldcapitalism$recordDamage(
                event.getSource(), event.getOriginalAmount());
    }
}
