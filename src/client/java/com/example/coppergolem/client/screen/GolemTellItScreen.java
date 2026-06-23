package com.example.coppergolem.client.screen;

import com.example.coppergolem.client.net.ClientNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Screen shown when a plan step has FAILED. Lets the player type a custom
 * instruction to guide the golem's error recovery.
 *
 * <p>Sends {@link ClientNetworking#sendErrorChoice(String, String)} with
 * choice="instruction" on Submit.</p>
 */
public final class GolemTellItScreen extends Screen {

    private static final int COL_LABEL = 0xFFFFFFFF;
    private static final int COL_HINT  = 0xFFAAAAAA;

    private final Screen parent;
    private EditBox instructionBox;

    public GolemTellItScreen(Screen parent) {
        super(Component.literal("Tell the Golem"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        instructionBox = new EditBox(this.font, cx - 150, cy - 10, 300, 20,
                Component.literal("instruction"));
        instructionBox.setMaxLength(256);
        instructionBox.setHint(Component.literal("Enter a new instruction for the golem..."));
        addRenderableWidget(instructionBox);

        // Submit
        addRenderableWidget(Button.builder(Component.literal("Submit"), b -> {
            String text = instructionBox.getValue().trim();
            if (!text.isEmpty()) {
                ClientNetworking.sendErrorChoice("instruction", text);
            }
            this.minecraft.setScreenAndShow(parent);
        }).bounds(cx - 110, cy + 18, 100, 20).build());

        // Cancel / back
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b ->
                this.minecraft.setScreenAndShow(parent)
        ).bounds(cx + 10, cy + 18, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int cy = this.height / 2;

        g.centeredText(this.font, this.title, cx, cy - 30, COL_LABEL);
        g.centeredText(this.font, Component.literal("A plan step failed. Tell the golem what to do:"),
                cx, cy - 18, COL_HINT);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
