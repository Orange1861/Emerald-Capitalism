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

/** Makes iron golems attack visible players hostile to their village or bank,
 * including contested candidates. */
public final class HostileVillagePlayerTargetGoal extends NearestAttackableTargetGoal<ServerPlayer> {

    private final IronGolem golem;

    public HostileVillagePlayerTargetGoal(IronGolem golem) {
        super(golem, ServerPlayer.class, 10, true, false,
                target -> target instanceof Player player
                        && isHostilePlayer(golem, player));
        this.golem = golem;
    }

    private static boolean isHostilePlayer(IronGolem golem, Player player) {
        if (golem instanceof EmeraldGolem emeraldGolem
                && emeraldGolem.isOwnedByBank(player.getUUID())) {
            return false;
        }
        if (VaultGolemGoals.isVaultGuard(golem)
                && golem.level() instanceof ServerLevel level
                && BankBlockEntity.findBankForGolem(level, golem) instanceof BankBlockEntity bank
                && bank.isAttackAllPlayersEnabled()) {
            return player.isAlive() && !player.isSpectator()
                    && !bank.isControlledBy(player.getUUID());
        }
        if (isContestedGovernorCandidate(golem, player)) {
            return true;
        }
        if (VillageHostility.isHostilePlayer(golem, player)) {
            return true;
        }
        if (!VaultGolemGoals.isVaultGuard(golem)
                || !player.isAlive()
                || player.isSpectator()
                || !(golem.level() instanceof ServerLevel level)) {
            return false;
        }
        BankBlockEntity bank = BankBlockEntity.findBankForGolem(level, golem);
        int opinion = bank == null
                ? BankReputationData.get(level).getReputation(player.getUUID())
                : BankReputationData.get(level).getBankOpinion(bank, player.getUUID());
        return opinion <= BankReputationData.HOSTILITY_THRESHOLD;
    }

    private static boolean isContestedGovernorCandidate(IronGolem golem, Player player) {
        if (!VaultGolemGoals.isVaultGuard(golem)
                || !player.isAlive()
                || player.isSpectator()
                || !(golem.level() instanceof ServerLevel level)
                || !(BankBlockEntity.findBankForGolem(level, golem) instanceof BankBlockEntity bank)
                || bank.getVillageId() == null) {
            return false;
        }

        VillageRecord village = VillageRegistryData.get(level).getVillages().get(bank.getVillageId());
        return village != null
                && VillageGovernance.isContestedGovernor(level, village, player.getUUID());
    }

    @Override
    public boolean canContinueToUse() {
        @Nullable ServerPlayer target = golem.getTarget() instanceof ServerPlayer player ? player : null;
        return target != null
                && isHostilePlayer(golem, target)
                && golem.hasLineOfSight(target)
                && super.canContinueToUse();
    }
}
