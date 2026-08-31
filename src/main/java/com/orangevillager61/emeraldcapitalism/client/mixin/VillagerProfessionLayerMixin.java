package com.orangevillager61.emeraldcapitalism.client.mixin;

import com.orangevillager61.emeraldcapitalism.util.ModIds;
import net.minecraft.client.renderer.entity.layers.VillagerProfessionLayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/** Maps custom Emerald Capitalism professions to their temporary vanilla profession layers. */
@Mixin(VillagerProfessionLayer.class)
public abstract class VillagerProfessionLayerMixin {

    // TODO: Replace these temporary vanilla texture aliases with dedicated villager art.
    private static final Map<ResourceLocation, ResourceLocation> PROFESSION_TEXTURE_OVERRIDES = Map.of(
            ModIds.id("banker"), ResourceLocation.withDefaultNamespace("librarian"),
            ModIds.id("mayor"), ResourceLocation.withDefaultNamespace("fletcher"),
            ModIds.id("emeraldsmith"), ResourceLocation.withDefaultNamespace("weaponsmith"),
            ModIds.id("lumberjack"), ResourceLocation.withDefaultNamespace("shepherd")
    );

    @Inject(method = "getResourceLocation", at = @At("RETURN"), cancellable = true)
    private void ecap$useVanillaProfessionTexture(
            String folder, ResourceLocation location, CallbackInfoReturnable<ResourceLocation> cir) {
        if (!"profession".equals(folder)) {
            return;
        }

        ResourceLocation vanillaProfession = PROFESSION_TEXTURE_OVERRIDES.get(location);
        if (vanillaProfession == null) {
            return;
        }

        ResourceLocation resolved = cir.getReturnValue();
        String resolvedPath = resolved.getPath();
        int fileNameStart = resolvedPath.lastIndexOf('/') + 1;
        cir.setReturnValue(vanillaProfession.withPath(vanillaPath ->
                resolvedPath.substring(0, fileNameStart) + vanillaPath + ".png"));
    }
}
