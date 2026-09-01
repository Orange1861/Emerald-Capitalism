package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Bridges the GUI texture overloads that changed in 1.21.4. */
public final class GuiGraphicsCompat {
    private GuiGraphicsCompat() {
    }

    public static void blit(GuiGraphics guiGraphics, ResourceLocation texture,
                            int x, int y, int u, int v, int width, int height) {
//? if >=1.21.4 {
        guiGraphics.blit(net.minecraft.client.renderer.RenderType::guiTextured, texture,
                x, y, u, v, width, height, 256, 256);
//?} else {
/*        guiGraphics.blit(texture, x, y, u, v, width, height);
 *///?}
    }

    public static void blit(GuiGraphics guiGraphics, ResourceLocation texture,
                            int x, int y, int u, int v, int width, int height,
                            int textureWidth, int textureHeight) {
//? if >=1.21.4 {
        guiGraphics.blit(net.minecraft.client.renderer.RenderType::guiTextured, texture,
                x, y, u, v, width, height, textureWidth, textureHeight);
//?} else {
/*        guiGraphics.blit(texture, x, y, u, v, width, height, textureWidth, textureHeight);
 *///?}
    }

    public static void blitSprite(GuiGraphics guiGraphics, ResourceLocation sprite,
                                  int x, int y, int width, int height) {
//? if >=1.21.4 {
        guiGraphics.blitSprite(net.minecraft.client.renderer.RenderType::guiTextured,
                sprite, x, y, width, height);
//?} else {
/*        guiGraphics.blitSprite(sprite, x, y, width, height);
 *///?}
    }

    public static void blitSprite(GuiGraphics guiGraphics, ResourceLocation sprite,
                                  int textureWidth, int textureHeight, int uPosition,
                                  int vPosition, int x, int y, int width, int height) {
//? if >=1.21.4 {
        guiGraphics.blitSprite(net.minecraft.client.renderer.RenderType::guiTextured,
                sprite, textureWidth, textureHeight, uPosition, vPosition,
                x, y, width, height);
//?} else {
/*        guiGraphics.blitSprite(sprite, textureWidth, textureHeight, uPosition, vPosition,
                x, y, width, height);
 *///?}
    }
}
