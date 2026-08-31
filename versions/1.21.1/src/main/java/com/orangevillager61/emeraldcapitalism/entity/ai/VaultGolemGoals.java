package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.animal.IronGolem;

/** Owns the durable vault-guard marker and its stationary goal policy. */
public final class VaultGolemGoals {
    private VaultGolemGoals() {
    }

    public static void markAsVaultGuard(IronGolem golem) {
        golem.setData(EmeraldCapitalismAttachments.VAULT_GOLEM, true);
        suppressWandering(golem);
    }

    public static boolean isVaultGuard(IronGolem golem) {
        return golem.getData(EmeraldCapitalismAttachments.VAULT_GOLEM);
    }

    public static void suppressWandering(IronGolem golem) {
        if (isVaultGuard(golem)) {
            golem.goalSelector.removeAllGoals(goal -> goal instanceof RandomStrollGoal);
        }
    }
}
