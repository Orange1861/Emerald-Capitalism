package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import com.orangevillager61.emeraldcapitalism.world.bank.BankReputationData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageGovernance;
import com.orangevillager61.emeraldcapitalism.world.village.VillageHostility;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/** Makes iron golems attack players in range who are hostile to their village or bank,
 * including contested candidates. */
public final class HostileVillagePlayerTargetGoal extends NearestAttackableTargetGoal<ServerPlayer> {

    private final IronGolem golem;

    public HostileVillagePlayerTargetGoal(IronGolem golem) {
        super(golem, ServerPlayer.class, 10, false, false,
//? if >=1.21.4 {
                /*(target, level) -> target instanceof Player player
*///?} else {
                target -> target instanceof Player player
 //?}
                        && isHostilePlayer(golem, player));
        this.golem = golem;
    }

    @Override
    public boolean canUse() {
        double followDistance = this.getFollowDistance();
        double followDistanceSq = followDistance * followDistance;
        ServerPlayer closest = null;
        double closestDistanceSq = Double.MAX_VALUE;
        HostilityContext hostilityContext = new HostilityContext(golem);
        for (ServerPlayer player : golem.level().getEntitiesOfClass(
                ServerPlayer.class,
                golem.getBoundingBox().inflate(followDistance, 4.0D, followDistance))) {
            double distanceSq = golem.distanceToSqr(player);
            if (distanceSq > followDistanceSq
                    || !player.isAlive()
                    || player.isSpectator()
                    || !isHostilePlayer(golem, player, hostilityContext)) {
                continue;
            }
            if (distanceSq < closestDistanceSq) {
                closest = player;
                closestDistanceSq = distanceSq;
            }
        }
        this.target = closest;
        return this.target != null;
    }

    private static boolean isHostilePlayer(IronGolem golem, Player player) {
        return isHostilePlayer(golem, player, new HostilityContext(golem));
    }

    private static boolean isHostilePlayer(
            IronGolem golem, Player player, HostilityContext hostilityContext) {
        if (golem instanceof EmeraldGolem emeraldGolem
                && emeraldGolem.isOwnedByBank(player.getUUID())) {
            return false;
        }

        boolean vaultGuard = hostilityContext.isVaultGuard();
        ServerLevel level = vaultGuard ? hostilityContext.getServerLevel() : null;
        BankBlockEntity bank = vaultGuard && level != null ? hostilityContext.getBank() : null;
        if (bank != null && bank.isAttackAllPlayersEnabled()) {
            if (bank.getVillageId() != null) {
                VillageRecord village = VillageRegistryData.get(level).getVillages().get(bank.getVillageId());
                if (village != null
                        && VillageGovernance.isContestedGovernorCandidateForBank(
                                level, village, bank, player.getUUID())) {
                    return VillageGovernance.isContestedGovernorAttackAllowed(
                            level, village, bank, player.getUUID());
                }
            }
            return player.isAlive() && !player.isSpectator()
                    && !bank.isControlledBy(player.getUUID());
        }
        if (isContestedGovernorCandidate(player, hostilityContext)) {
            return true;
        }
        if (VillageHostility.isHostilePlayer(golem, player)) {
            return true;
        }
        if (!vaultGuard
                || !player.isAlive()
                || player.isSpectator()
                || level == null) {
            return false;
        }
        int opinion = bank == null
                ? BankReputationData.get(level).getReputation(player.getUUID())
                : BankReputationData.get(level).getBankOpinion(bank, player.getUUID());
        return opinion <= BankReputationData.HOSTILITY_THRESHOLD;
    }

    private static boolean isContestedGovernorCandidate(
            Player player, HostilityContext hostilityContext) {
        if (!hostilityContext.isVaultGuard()
                || !player.isAlive()
                || player.isSpectator()
                || hostilityContext.getServerLevel() == null) {
            return false;
        }

        ServerLevel level = hostilityContext.getServerLevel();
        BankBlockEntity bank = hostilityContext.getBank();
        if (bank == null
                || bank.getVillageId() == null) {
            return false;
        }

        VillageRecord village = VillageRegistryData.get(level).getVillages().get(bank.getVillageId());
        return village != null
                && VillageGovernance.isContestedGovernorCandidateForBank(
                        level, village, bank, player.getUUID())
                && VillageGovernance.isContestedGovernorAttackAllowed(
                        level, village, bank, player.getUUID());
    }

    private static final class HostilityContext {

        private final IronGolem golem;
        private boolean vaultGuardResolved;
        private boolean vaultGuard;
        private boolean serverLevelResolved;
        @Nullable
        private ServerLevel serverLevel;
        private boolean bankResolved;
        @Nullable
        private BankBlockEntity bank;

        private HostilityContext(IronGolem golem) {
            this.golem = golem;
        }

        private boolean isVaultGuard() {
            if (!vaultGuardResolved) {
                vaultGuard = VaultGolemGoals.isVaultGuard(golem);
                vaultGuardResolved = true;
            }
            return vaultGuard;
        }

        @Nullable
        private ServerLevel getServerLevel() {
            if (!serverLevelResolved) {
                serverLevel = golem.level() instanceof ServerLevel level ? level : null;
                serverLevelResolved = true;
            }
            return serverLevel;
        }

        @Nullable
        private BankBlockEntity getBank() {
            if (!bankResolved) {
                ServerLevel level = getServerLevel();
                if (isVaultGuard() && level != null) {
                    bank = BankBlockEntity.findBankForGolem(level, golem);
                }
                bankResolved = true;
            }
            return bank;
        }
    }

    @Override
    public boolean canContinueToUse() {
        @Nullable ServerPlayer target = golem.getTarget() instanceof ServerPlayer player ? player : null;
        return target != null
                && golem.distanceToSqr(target) <= this.getFollowDistance() * this.getFollowDistance()
                && target.isAlive()
                && !target.isSpectator()
                && isHostilePlayer(golem, target);
    }
}
