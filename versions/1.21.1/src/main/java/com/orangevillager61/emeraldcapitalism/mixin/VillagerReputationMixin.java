package com.orangevillager61.emeraldcapitalism.mixin;

import com.orangevillager61.emeraldcapitalism.api.VillagerDamageTracker;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.ai.gossip.GossipContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import it.unimi.dsi.fastutil.objects.Object2IntMap;

import java.util.Map;
import java.util.UUID;

/** Scales player-damage reputation changes with the accepted damage amount. */
@Mixin(Villager.class)
public class VillagerReputationMixin implements VillagerDamageTracker {

    /** Damage captured before the reputation mutation. */
    @Unique
    private float emeraldcapitalism$lastDamage = 0.0f;

    /** Whether the next reputation mutation belongs to player damage. */
    @Unique
    private boolean emeraldcapitalism$wasPlayerDamage = false;

    /**
     * Marks a minimum-one update that needs to survive GossipContainer's
     * minimum raw value check when the target had no existing entry.
     */
    @Unique
    private boolean emeraldcapitalism$minimumOnePending = false;

    @Unique
    private boolean emeraldcapitalism$suppressNextPlayerDamageReputation = false;

    @Unique
    private UUID emeraldcapitalism$damagePlayer;

    @Override
    public void emeraldcapitalism$recordDamage(DamageSource source, float amount) {
        emeraldcapitalism$suppressNextPlayerDamageReputation = false;
        emeraldcapitalism$wasPlayerDamage = source.getEntity() instanceof Player;
        emeraldcapitalism$minimumOnePending = false;
        emeraldcapitalism$damagePlayer = null;
        if (emeraldcapitalism$wasPlayerDamage) {
            emeraldcapitalism$lastDamage = amount;
            emeraldcapitalism$damagePlayer = source.getEntity().getUUID();
        }
    }

    @Override
    public void emeraldcapitalism$suppressNextPlayerDamageReputation() {
        emeraldcapitalism$suppressNextPlayerDamageReputation = true;
        emeraldcapitalism$wasPlayerDamage = false;
        emeraldcapitalism$minimumOnePending = false;
        emeraldcapitalism$damagePlayer = null;
    }

    /** Restores a minimum gossip entry when the container drops a new value of one. */
    @Inject(method = "setLastHurtByMob", at = @At("RETURN"))
    private void emeraldcapitalism$preserveMinimumOne(
            net.minecraft.world.entity.LivingEntity attacker, CallbackInfo ci) {
        if (!emeraldcapitalism$minimumOnePending
                || emeraldcapitalism$damagePlayer == null) {
            emeraldcapitalism$damagePlayer = null;
            return;
        }

        GossipContainer gossips = ((Villager) (Object) this).getGossips();
        Map<UUID, Object2IntMap<GossipType>> entries = gossips.getGossipEntries();
        Object2IntMap<GossipType> playerEntries = entries.get(emeraldcapitalism$damagePlayer);
        if (playerEntries == null || !playerEntries.containsKey(GossipType.MINOR_NEGATIVE)) {
            gossips.add(emeraldcapitalism$damagePlayer, GossipType.MINOR_NEGATIVE, 2);
            playerEntries = gossips.getGossipEntries().get(emeraldcapitalism$damagePlayer);
            if (playerEntries != null) {
                playerEntries.put(GossipType.MINOR_NEGATIVE, 1);
            }
        }

        emeraldcapitalism$minimumOnePending = false;
        emeraldcapitalism$damagePlayer = null;
    }

    /**
     * Rewrites only the damage-related gossip emitted by Villager's reputation
     * event handler; unrelated gossip mutations keep their vanilla values.
     */
    @ModifyArgs(
        method = "onReputationEventFrom(Lnet/minecraft/world/entity/ai/village/ReputationEventType;Lnet/minecraft/world/entity/Entity;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/gossip/GossipContainer;add(Ljava/util/UUID;Lnet/minecraft/world/entity/ai/gossip/GossipType;I)V"
        ),
        require = 1
    )
    private void modifyReputationLoss(Args args) {
        GossipType gossipType = args.get(1);

        if (emeraldcapitalism$suppressNextPlayerDamageReputation
                && (gossipType == GossipType.MAJOR_NEGATIVE || gossipType == GossipType.MINOR_NEGATIVE)) {
            args.set(2, 0);
            emeraldcapitalism$suppressNextPlayerDamageReputation = false;
            return;
        }

        if (!emeraldcapitalism$wasPlayerDamage) {
            return;
        }

        if (!com.orangevillager61.emeraldcapitalism.Config.proportionalVillagerReputation) {
            emeraldcapitalism$wasPlayerDamage = false;
            emeraldcapitalism$minimumOnePending = false;
            emeraldcapitalism$damagePlayer = null;
            return;
        }

        if (gossipType != GossipType.MAJOR_NEGATIVE && gossipType != GossipType.MINOR_NEGATIVE) {
            emeraldcapitalism$wasPlayerDamage = false;
            emeraldcapitalism$minimumOnePending = false;
            emeraldcapitalism$damagePlayer = null;
            return;
        }

        int proportionalLoss = Math.round(emeraldcapitalism$lastDamage * 5.0f);

        if (emeraldcapitalism$lastDamage > 0 && proportionalLoss == 0) {
            proportionalLoss = 1;
            emeraldcapitalism$minimumOnePending = true;
        }

        args.set(2, proportionalLoss);
        if (!emeraldcapitalism$minimumOnePending) {
            emeraldcapitalism$damagePlayer = null;
        }

        emeraldcapitalism$wasPlayerDamage = false;
    }
}
