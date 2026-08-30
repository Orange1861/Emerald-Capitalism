package com.orangevillager61.emeraldcapitalism.client.screen;

import com.orangevillager61.emeraldcapitalism.network.UpdateWelcomeMessagePacket;
import com.orangevillager61.emeraldcapitalism.network.VillagePOIClientCache;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Screen for editing the village welcome message.
 * Opened from the VillagePOIScreen via the "Welcome Msg" button.
 */
public class WelcomeMessageScreen extends Screen {

    private static final int PADDING = 10;
    private final Screen parent;
    private EditBox messageBox;

    public WelcomeMessageScreen(Screen parent) {
        super(Component.literal("Edit Welcome Message"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = width / 2;
        int centerY = height / 2;

        int boxWidth = 250;
        messageBox = new EditBox(font, centerX - boxWidth / 2, centerY - 20, boxWidth, 20, Component.literal("Welcome Message"));
        messageBox.setMaxLength(512);
        messageBox.setValue(VillagePOIClientCache.getWelcomeMessage());
        addRenderableWidget(messageBox);

        setInitialFocus(messageBox);

        addRenderableWidget(Button.builder(
                Component.literal("Save"),
                btn -> {
                    UUID villageId = VillagePOIClientCache.getVillageId();
                    if (villageId != null) {
                        PacketDistributor.sendToServer(new UpdateWelcomeMessagePacket(villageId, messageBox.getValue().trim()));
                    }
                    onClose();
                })
                .bounds(centerX - 130, centerY + 10, 80, 20)
                .build());

        addRenderableWidget(Button.builder(
                Component.literal("No Message"),
                btn -> {
                    UUID villageId = VillagePOIClientCache.getVillageId();
                    if (villageId != null) {
                        PacketDistributor.sendToServer(new UpdateWelcomeMessagePacket(villageId, ""));
                    }
                    onClose();
                })
                .bounds(centerX - 45, centerY + 10, 90, 20)
                .build());

        addRenderableWidget(Button.builder(
                Component.literal("Cancel"),
                btn -> onClose())
                .bounds(centerX + 50, centerY + 10, 80, 20)
                .build());
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 40, 0xFFFFFF);
        graphics.drawString(font, "Format: [Village Name]: <your message>", width / 2 - 125, height / 2 + 36, 0x888888);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (messageBox.isFocused()) {
            if (keyCode == 256) { // Escape
                onClose();
                return true;
            }
            return messageBox.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (messageBox.isFocused()) {
            return messageBox.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
