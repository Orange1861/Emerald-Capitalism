package com.orangevillager61.emeraldcapitalism.registry;

import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.menu.ECAPMenuTypes;
import net.neoforged.bus.api.IEventBus;

/** Central lifecycle entry point; each owner still defines its own registrations. */
public final class ECAPRegistries {
    private ECAPRegistries() {
    }

    public static void registerAll(IEventBus modEventBus) {
        ECAPEffects.register(modEventBus);
        ECAPPotions.register(modEventBus);
        EmeraldCapitalismAttachments.register(modEventBus);
        ECAPMenuTypes.register(modEventBus);
        ECAPRecipeSerializers.register(modEventBus);
        ECAPRecipeTypes.register(modEventBus);
        ECAPBlocks.register(modEventBus);
        ECAPItems.register(modEventBus);
        ECAPBlockEntityTypes.register(modEventBus);
        ECAPEntityTypes.register(modEventBus);
        ECAPPoiTypes.register(modEventBus);
        ECAPStructureProcessorTypes.register(modEventBus);
        ECAPVillagerProfessions.register(modEventBus);
        ECAPCreativeModTabs.register(modEventBus);
    }
}
