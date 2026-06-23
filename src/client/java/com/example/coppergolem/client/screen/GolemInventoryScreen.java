package com.example.coppergolem.client.screen;

import com.example.coppergolem.client.net.ClientNetworking;
import com.example.coppergolem.net.Packets.SlotLine;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only golem inventory screen showing all 41 slots in a grid layout.
 *
 * <p>Layout:
 * <pre>
 *   Row label "Hotbar"    → slots  0-8   (9 slots)
 *   Row label "Armor"     → slots 36-39  (4 slots)
 *   Rows label "Inventory"→ slots  9-35  (27 slots, 9-wide × 3 rows)
 *   Row label "Offhand"   → slot  40     (1 slot)
 * </pre>
 * Each slot box is 18×18 pixels. Non-empty slots are drawn white; empty slots
 * are drawn dark-grey. The short item name (part after ":") and count (if >1)
 * are drawn inside each box.</p>
 */
public final class GolemInventoryScreen extends Screen {

    private static final int COL_LABEL  = 0xFFFFFFFF;
    private static final int COL_HEADER = 0xFFFFDD55;
    private static final int COL_EMPTY  = 0xFF333333;
    private static final int COL_FILLED = 0xFFFFFFFF;
    private static final int COL_COUNT  = 0xFFFFFF55;

    private static final int SLOT_SIZE = 18;
    private static final int SLOT_PAD  = 2;

    private final Screen parent;

    public GolemInventoryScreen(Screen parent) {
        super(Component.literal("Golem Inventory"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int bottomY = rowBaseY(6) + SLOT_SIZE + 10;

        addRenderableWidget(Button.builder(Component.literal("Back"), b ->
                this.minecraft.setScreenAndShow(parent)
        ).bounds(cx - 50, bottomY, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        // Build a lookup: slot -> SlotLine
        List<SlotLine> inv = ClientNetworking.inventory();
        Map<Integer, SlotLine> slotMap = new HashMap<>();
        for (SlotLine sl : inv) {
            slotMap.put(sl.slot(), sl);
        }

        int titleX = this.width / 2 - this.font.width(this.title) / 2;
        g.text(this.font, this.title, titleX, 6, COL_LABEL);

        int labelX = 10;
        int gridX  = labelX + 60; // leave room for labels

        int row = 0;

        // Row 1: Hotbar (slots 0-8)
        row = drawRow(g, slotMap, "Hotbar",    labelX, gridX, row, new int[]{0,1,2,3,4,5,6,7,8});
        // Row 2: Armor (slots 36-39)
        row = drawRow(g, slotMap, "Armor",     labelX, gridX, row, new int[]{36,37,38,39});
        // Row 3-5: Inventory (slots 9-35, 9-wide)
        row = drawRow(g, slotMap, "Inventory", labelX, gridX, row,
                      range(9, 17));
        row = drawRow(g, slotMap, "",          labelX, gridX, row,
                      range(18, 26));
        row = drawRow(g, slotMap, "",          labelX, gridX, row,
                      range(27, 35));
        // Row 6: Offhand (slot 40)
        drawRow(g, slotMap, "Offhand",     labelX, gridX, row, new int[]{40});
    }

    /** Draw a labeled row of slot boxes. Returns the next row index. */
    private int drawRow(GuiGraphicsExtractor g,
                        Map<Integer, SlotLine> slotMap,
                        String label,
                        int labelX, int gridX,
                        int row, int[] slots) {
        int y = rowBaseY(row);
        if (!label.isEmpty()) {
            g.text(this.font, label, labelX, y + 4, COL_HEADER);
        }
        for (int i = 0; i < slots.length; i++) {
            int slotIdx = slots[i];
            int x = gridX + i * (SLOT_SIZE + SLOT_PAD);
            SlotLine sl = slotMap.get(slotIdx);
            drawSlot(g, x, y, slotIdx, sl);
        }
        return row + 1;
    }

    private void drawSlot(GuiGraphicsExtractor g, int x, int y, int slot, SlotLine sl) {
        if (sl == null || sl.itemId() == null || sl.itemId().isEmpty()) {
            // Empty slot — draw dark box
            g.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, COL_EMPTY);
        } else {
            // Non-empty — draw white box background outline
            g.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, 0xFF555555);
            // Short name: part after ":"
            String id = sl.itemId();
            int colon = id.lastIndexOf(':');
            String shortName = colon >= 0 ? id.substring(colon + 1) : id;
            // Truncate to fit in slot (16 px wide, font ~5px per char → ~3 chars)
            String display = shortName.length() > 3 ? shortName.substring(0, 3) : shortName;
            g.text(this.font, display, x + 1, y + 2, COL_FILLED);
            if (sl.count() > 1) {
                String cnt = String.valueOf(sl.count());
                g.text(this.font, cnt, x + 1, y + 9, COL_COUNT);
            }
        }
    }

    private int rowBaseY(int row) {
        // Title at y=6 (height ~8), start rows at y=20
        return 20 + row * (SLOT_SIZE + SLOT_PAD + 2);
    }

    private static int[] range(int from, int to) {
        int[] r = new int[to - from + 1];
        for (int i = 0; i < r.length; i++) r[i] = from + i;
        return r;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
