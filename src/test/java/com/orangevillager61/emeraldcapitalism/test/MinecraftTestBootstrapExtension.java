package com.orangevillager61.emeraldcapitalism.test;

import net.neoforged.fml.loading.LoadingModList;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.List;
import java.util.Map;

/**
 * Initializes Minecraft's built-in registries before tests access Minecraft
 * classes whose static initialization depends on them. The normal NeoForge
 * launch process is not present in a plain JUnit JVM, so install an empty
 * loading list before invoking Minecraft's bootstrap.
 */
public final class MinecraftTestBootstrapExtension implements BeforeAllCallback {

    private static boolean bootstrapped;

    @Override
    public synchronized void beforeAll(ExtensionContext context) {
        if (!bootstrapped) {
            installTestLoadingModList();
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    private static void installTestLoadingModList() {
        if (LoadingModList.get() != null) {
            return;
        }

        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
    }
}
