package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.entity.EmeraldSkrimisher;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Random stroll that keeps an assigned Skrimisher inside its village. */
public final class VillageBoundedRandomStrollGoal extends RandomStrollGoal {

    private static final int POSITION_ATTEMPTS = 16;

    private final EmeraldSkrimisher skrimisher;
    @Nullable
    private AABB lastVillageBounds;

    public VillageBoundedRandomStrollGoal(EmeraldSkrimisher skrimisher, double speedModifier) {
        super(skrimisher, speedModifier);
        this.skrimisher = skrimisher;
    }

    @Override
    protected @Nullable Vec3 getPosition() {
        AABB villageBounds = findVillageBounds();
        if (villageBounds == null) {
            return super.getPosition();
        }

        for (int attempt = 0; attempt < POSITION_ATTEMPTS; attempt++) {
            Vec3 candidate = super.getPosition();
            if (candidate != null && villageBounds.contains(candidate.x, candidate.y, candidate.z)) {
                return candidate;
            }
        }
        return null;
    }

    @Nullable
    private AABB findVillageBounds() {
        if (!(skrimisher.level() instanceof ServerLevel level)) {
            return lastVillageBounds;
        }

        VillageRegistryData registry = VillageRegistryData.get(level);
        VillageRecord village = registry.getVillageFor(skrimisher.blockPosition());
        if (village == null && skrimisher.getBankEmployeePos() != null) {
            village = registry.getVillageFor(skrimisher.getBankEmployeePos());
        }
        if (village != null) {
            lastVillageBounds = village.getBoundingBox();
        }
        return lastVillageBounds;
    }
}
