package com.example.coppergolem.client.screen;

import com.example.coppergolem.inventory.GolemMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * Proper container screen for the golem's 41-slot inventory.
 *
 * <p>Extends {@link AbstractContainerScreen} so Minecraft handles:
 * item icon rendering, slot highlighting, click/drag, shift-click
 * ({@link GolemMenu#quickMoveStack}), and tooltips on hover — all automatic.</p>
 *
 * <p>MC 26.2 API: the render pipeline uses
 * {@link #extractRenderState(GuiGraphicsExtractor, int, int, float)} as the
 * entry point; background drawing is done by overriding
 * {@link #extractBackground(GuiGraphicsExtractor, int, int, float)}.</p>
 */
public class GolemInventoryScreen extends AbstractContainerScreen<GolemMenu> {

    public GolemInventoryScreen(GolemMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title, 176, 256);
    }

    /**
     * Draw the screen background: a dark panel and slot outlines.
     * Called automatically by {@link AbstractContainerScreen#extractContents}.
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Dark background panel
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xC0101010);

        // Draw slot outlines for each slot in the menu
        for (Slot slot : this.menu.slots) {
            int x = leftPos + slot.x - 1;
            int y = topPos  + slot.y - 1;
            graphics.fill(x,     y,     x + 18, y + 18, 0xFF555555); // border
            graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B); // inner
        }
    }

    /**
     * Full render: background, slot contents (items), labels, tooltip.
     */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        // Section labels
        graphics.text(font, "Hotbar",         leftPos + 8, topPos + 7,   0xFFAAAAAA);
        graphics.text(font, "Inventory",      leftPos + 8, topPos + 43,  0xFFAAAAAA);
        graphics.text(font, "Armor",          leftPos + 8, topPos + 115, 0xFFAAAAAA);
        graphics.text(font, "Offhand",        leftPos + 8, topPos + 133, 0xFFAAAAAA);
        graphics.text(font, "Your Inventory", leftPos + 8, topPos + 163, 0xFFAAAAAA);
    }
}
