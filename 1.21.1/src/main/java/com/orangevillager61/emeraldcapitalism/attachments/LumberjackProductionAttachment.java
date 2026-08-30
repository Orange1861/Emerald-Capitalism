package com.orangevillager61.emeraldcapitalism.attachments;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.orangevillager61.emeraldcapitalism.world.forestry.CharcoalProductionPolicy;

/** Durable, profession-specific state for a lumberjack's charcoal quota. */
public final class LumberjackProductionAttachment {
    private static final Codec<Double> CHARCOAL_QUOTA_CODEC = Codec.DOUBLE.validate(value ->
            CharcoalProductionPolicy.isValidQuota(value)
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Lumberjack charcoal quota is out of bounds"));

    public static final Codec<LumberjackProductionAttachment> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    CHARCOAL_QUOTA_CODEC.optionalFieldOf("charcoal_quota", 0.0D)
                            .forGetter(LumberjackProductionAttachment::getCharcoalQuota)
            ).apply(instance, LumberjackProductionAttachment::new));

    private double charcoalQuota;

    public LumberjackProductionAttachment() {
    }

    private LumberjackProductionAttachment(double charcoalQuota) {
        setCharcoalQuota(charcoalQuota);
    }

    public double getCharcoalQuota() {
        return charcoalQuota;
    }

    public void setCharcoalQuota(double quota) {
        charcoalQuota = CharcoalProductionPolicy.sanitizeQuota(quota);
    }
}
