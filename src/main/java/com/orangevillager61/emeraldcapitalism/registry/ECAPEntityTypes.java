package com.orangevillager61.emeraldcapitalism.registry;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldSkrimisher;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.bus.api.IEventBus;

import java.util.function.Supplier;

public final class ECAPEntityTypes {

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, EmeraldCapitalism.MODID);

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
    }

    public static final Supplier<EntityType<EmeraldGolem>> EMERALD_GOLEM = ENTITY_TYPES.register(
            "emerald_golem",
            () -> EntityType.Builder.of(EmeraldGolem::new, MobCategory.MISC)
                    .sized(1.0F, 2.0F)  // Exactly 1 block wide and 2 blocks tall
                    .clientTrackingRange(10)
                    .build("emerald_golem")
    );

    public static final Supplier<EntityType<EmeraldSkrimisher>> EMERALD_SKRIMISHER = ENTITY_TYPES.register(
            "emerald_skrimisher",
            () -> EntityType.Builder.of(EmeraldSkrimisher::new, MobCategory.MISC)
                    .sized(0.75F, 1.0F)
                    .clientTrackingRange(10)
                    .build("emerald_skrimisher")
    );
}
