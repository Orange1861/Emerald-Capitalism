package com.orangevillager61.emeraldcapitalism.attachments;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.orangevillager61.emeraldcapitalism.world.forestry.CharcoalProductionPolicy;
import net.minecraft.core.GlobalPos;

import javax.annotation.Nullable;
import java.util.Optional;

/** Durable, profession-specific state for a lumberjack's charcoal production. */
public final class LumberjackProductionAttachment {
    private static final Codec<Double> CHARCOAL_QUOTA_CODEC = Codec.DOUBLE.validate(value ->
            CharcoalProductionPolicy.isValidQuota(value)
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Lumberjack charcoal quota is out of bounds"));

    public static final Codec<LumberjackProductionAttachment> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    CHARCOAL_QUOTA_CODEC.optionalFieldOf("charcoal_quota", 0.0D)
                            .forGetter(LumberjackProductionAttachment::getCharcoalQuota),
                    GlobalPos.CODEC.optionalFieldOf("charcoal_furnace")
                            .forGetter(LumberjackProductionAttachment::getPendingCharcoalFurnace)
            ).apply(instance, LumberjackProductionAttachment::new));

    private double charcoalQuota;
    private Optional<GlobalPos> pendingCharcoalFurnace = Optional.empty();

    public LumberjackProductionAttachment() {
    }

    private LumberjackProductionAttachment(double charcoalQuota, Optional<GlobalPos> pendingCharcoalFurnace) {
        setCharcoalQuota(charcoalQuota);
        this.pendingCharcoalFurnace = pendingCharcoalFurnace;
    }

    public double getCharcoalQuota() {
        return charcoalQuota;
    }

    public void setCharcoalQuota(double quota) {
        charcoalQuota = CharcoalProductionPolicy.sanitizeQuota(quota);
    }

    public Optional<GlobalPos> getPendingCharcoalFurnace() {
        return pendingCharcoalFurnace;
    }

    public void setPendingCharcoalFurnace(@Nullable GlobalPos furnace) {
        pendingCharcoalFurnace = Optional.ofNullable(furnace);
    }
}
