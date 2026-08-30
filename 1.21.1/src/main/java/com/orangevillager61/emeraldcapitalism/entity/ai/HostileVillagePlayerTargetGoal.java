package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.world.village.VillageHostility;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/** Makes every iron golem attack a visible player hostile to its village. */
public final class HostileVillagePlayerTargetGoal extends NearestAttackableTargetGoal<ServerPlayer> {

    private final IronGolem golem;

    public HostileVillagePlayerTargetGoal(IronGolem golem) {
        super(golem, ServerPlayer.class, 10, true, false,
                target -> target instanceof Player player
                        && VillageHostility.isHostilePlayer(golem, player));
        this.golem = golem;
    }

    @Override
    public boolean canContinueToUse() {
        @Nullable ServerPlayer target = golem.getTarget() instanceof ServerPlayer player ? player : null;
        return target != null
                && VillageHostility.isHostilePlayer(golem, target)
                && golem.hasLineOfSight(target)
                && super.canContinueToUse();
    }
}
