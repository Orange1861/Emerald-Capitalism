package com.orangevillager61.emeraldcapitalism.mixin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Build-only selector used by {@code auditMixins}. Normal launches apply every
 * configured mixin because the selector ignores disable properties unless the
 * explicit audit marker is present.
 */
public final class MixinAuditPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogManager.getLogger("EmeraldCapitalism/MixinAudit");
    private static final String CONFIG_RESOURCE = "emeraldcapitalism.mixins.json";
    private static final String AUDIT_MARKER = "emeraldcapitalism.mixinAudit";
    private static final String AUDIT_UNIT = "emeraldcapitalism.mixinAudit.unit";
    private static final String DISABLED_MIXINS = "emeraldcapitalism.mixinAudit.disabledMixins";
    private static final Pattern MIXIN_ARRAY = Pattern.compile("\\\"mixins\\\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern JSON_STRING = Pattern.compile("\\\"([^\\\"]+)\\\"");

    private Set<String> disabledMixins = Set.of();

    @Override
    public void onLoad(String mixinPackage) {
        if (!Boolean.getBoolean(AUDIT_MARKER)) {
            return;
        }

        String unit = System.getProperty(AUDIT_UNIT, "").trim();
        if (unit.isEmpty()) {
            throw new IllegalStateException("Mixin audit marker is set without an audit unit");
        }

        Set<String> configuredMixins = loadConfiguredMixins();
        Set<String> requested = new HashSet<>();
        for (String name : System.getProperty(DISABLED_MIXINS, "").split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                requested.add(trimmed);
            }
        }

        Set<String> unknown = new HashSet<>(requested);
        unknown.removeAll(configuredMixins);
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("Mixin audit requested unknown mixins: " + unknown);
        }

        disabledMixins = Set.copyOf(requested);
        LOGGER.warn("MIXIN_AUDIT_ACTIVE unit={} disabledMixins={}", unit, disabledMixins);
    }

    private static Set<String> loadConfiguredMixins() {
        try (InputStream stream = MixinAuditPlugin.class.getClassLoader().getResourceAsStream(CONFIG_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Could not read live mixin config " + CONFIG_RESOURCE);
            }
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Matcher arrayMatcher = MIXIN_ARRAY.matcher(json);
            if (!arrayMatcher.find()) {
                throw new IllegalStateException("Mixin config has no mixins array: " + CONFIG_RESOURCE);
            }
            Set<String> configured = new HashSet<>();
            Matcher nameMatcher = JSON_STRING.matcher(arrayMatcher.group(1));
            while (nameMatcher.find()) {
                configured.add(nameMatcher.group(1));
            }
            return configured;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read live mixin config " + CONFIG_RESOURCE, exception);
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        int separator = mixinClassName.lastIndexOf('.');
        String simpleName = separator < 0 ? mixinClassName : mixinClassName.substring(separator + 1);
        return !disabledMixins.contains(simpleName);
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
