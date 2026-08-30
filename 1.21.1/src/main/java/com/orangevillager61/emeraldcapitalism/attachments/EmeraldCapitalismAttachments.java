package com.orangevillager61.emeraldcapitalism.attachments;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.mojang.serialization.Codec;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public class EmeraldCapitalismAttachments {

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, EmeraldCapitalism.MODID);

    public static void register(IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }

    public static final Supplier<AttachmentType<VillagerStatsAttachment>> VILLAGER_STATS =
            ATTACHMENT_TYPES.register("villager_stats", () ->
                    AttachmentType.builder(VillagerStatsAttachment::new)
                            .serialize(VillagerStatsAttachment.CODEC)
                            .build()
            );

    public static final Supplier<AttachmentType<LumberjackProductionAttachment>> LUMBERJACK_PRODUCTION =
            ATTACHMENT_TYPES.register("lumberjack_production", () ->
                    AttachmentType.builder(LumberjackProductionAttachment::new)
                            .serialize(LumberjackProductionAttachment.CODEC)
                            .build()
            );

    /** Durable identity for bank-spawned stationary guards; display names are presentation only. */
    public static final Supplier<AttachmentType<Boolean>> VAULT_GOLEM =
            ATTACHMENT_TYPES.register("vault_golem", () ->
                    AttachmentType.builder(() -> false)
                            .serialize(Codec.BOOL)
                            .build()
            );
}
