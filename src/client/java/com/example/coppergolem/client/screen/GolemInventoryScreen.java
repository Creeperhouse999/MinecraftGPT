package com.example.coppergolem.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Placeholder inventory screen.
 *
 * <p>A full synced container inventory is out of scope for this build.
 * This screen compiles and shows a status label so the navigation button in
 * {@link GolemControlScreen} is functional.</p>
 *
 * <p>TODO: implement synced container once the server-side container handler
 * and slot protocol are in place.</p>
 */
public final class GolemInventoryScreen extends Screen {

    private static final int COL_LABEL = 0xFFFFFFFF;
    private static final int COL_NOTE  = 0xFFAAAAAA;

    private final Screen parent;

    public GolemInventoryScreen(Screen parent) {
        super(Component.literal("Golem Inventory"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        addRenderableWidget(Button.builder(Component.literal("Back"), b ->
                this.minecraft.setScreenAndShow(parent)
        ).bounds(cx - 50, this.height / 2 + 30, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int cy = this.height / 2;

        g.centeredText(this.font, this.title, cx, cy - 20, COL_LABEL);
        g.centeredText(this.font,
                Component.literal("Golem inventory (open via server handler)"),
                cx, cy, COL_NOTE);
        g.centeredText(this.font,
                Component.literal("[TODO: full synced container out of scope]"),
                cx, cy + 12, COL_NOTE);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
