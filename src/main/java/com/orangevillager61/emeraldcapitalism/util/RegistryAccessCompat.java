package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

/** Version bridge for the dynamic-registry lookup rename. */
public final class RegistryAccessCompat {
    private RegistryAccessCompat() {
    }

    @SuppressWarnings("unchecked")
    public static <T> Registry<T> get(RegistryAccess access, ResourceKey<?> key) {
//? if >=1.21.4 {
        return (Registry<T>) access.lookupOrThrow((ResourceKey) key);
//?} else {
/*        return (Registry<T>) access.registryOrThrow((ResourceKey) key);
 *///?}
    }

    public static <T> T getValue(Registry<T> registry, ResourceKey<T> key) {
//? if >=1.21.4 {
        return registry.getOrThrow(key).value();
//?} else {
/*        return registry.get(key);
 *///?}
    }

    public static <T> T getValue(Registry<T> registry, ResourceLocation key) {
//? if >=1.21.4 {
        return registry.get(key).map(Holder::value).orElse(null);
//?} else {
/*        return registry.get(key);
 *///?}
    }

    public static <T> java.util.Optional<HolderSet.Named<T>> getTag(Registry<T> registry, TagKey<T> key) {
//? if >=1.21.4 {
        return registry.get(key);
//?} else {
/*        return registry.getTag(key);
 *///?}
    }

    public static <T> Holder<T> getHolder(Registry<T> registry, ResourceKey<T> key) {
//? if >=1.21.4 {
        return registry.get(key).orElseThrow(() -> new IllegalStateException("Missing registry holder: " + key));
//?} else {
/*        return registry.getHolderOrThrow(key);
 *///?}
    }
}
