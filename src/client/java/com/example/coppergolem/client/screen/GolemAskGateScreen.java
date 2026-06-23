package com.example.coppergolem.client.screen;

import com.example.coppergolem.client.net.ClientNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Modal screen shown when the server sends an AskGateS2C packet.
 * Displays the item description and Approve / Deny buttons.
 */
public final class GolemAskGateScreen extends Screen {

    private static final int COL_LABEL = 0xFFFFFFFF;
    private static final int COL_DESC  = 0xFFFFDD55;

    private final Screen parent;

    public GolemAskGateScreen(Screen parent) {
        super(Component.literal("Golem — Approval Required"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        // Approve
        addRenderableWidget(Button.builder(Component.literal("Approve"), b -> {
            ClientNetworking.sendApproval(true);
            ClientNetworking.clearAskGate();
            this.minecraft.setScreenAndShow(parent);
        }).bounds(cx - 110, cy + 20, 100, 20).build());

        // Deny
        addRenderableWidget(Button.builder(Component.literal("Deny"), b -> {
            ClientNetworking.sendApproval(false);
            ClientNetworking.clearAskGate();
            this.minecraft.setScreenAndShow(parent);
        }).bounds(cx + 10, cy + 20, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int cy = this.height / 2;

        g.centeredText(this.font, this.title, cx, cy - 30, COL_LABEL);

        String desc = ClientNetworking.pendingAskGate();
        if (desc == null) desc = "(no pending request)";
        g.centeredText(this.font, Component.literal("Request: " + desc), cx, cy - 10, COL_DESC);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // force an explicit Approve/Deny
    }
}
