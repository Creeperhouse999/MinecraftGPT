package com.example.coppergolem.client.screen;

import com.example.coppergolem.client.net.ClientNetworking;
import com.example.coppergolem.net.Packets.ZoneLine;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Zone management screen.
 *
 * <p>Left column: scrollable list of current zones (read from
 * {@link ClientNetworking#zones()}, updated each server push).</p>
 *
 * <p>Right column: form with fields name, minX, minZ, maxX, maxZ + buttons
 * Add / Update / Remove (zone edit), plus an X/Y/Z set-home row.</p>
 */
public final class GolemZoneScreen extends Screen {

    private static final int COL_LABEL   = 0xFFFFFFFF;
    private static final int COL_HEADER  = 0xFFFFDD55;
    private static final int COL_ZONE    = 0xFFCCCCCC;
    private static final int COL_ERR     = 0xFFFF5555;

    private final Screen parent;

    // Zone form fields
    private EditBox fieldName;
    private EditBox fieldMinX;
    private EditBox fieldMinZ;
    private EditBox fieldMaxX;
    private EditBox fieldMaxZ;

    // Home point fields
    private EditBox fieldHomeX;
    private EditBox fieldHomeY;
    private EditBox fieldHomeZ;

    /** Non-null error message displayed for one render pass after a parse error. */
    private String errorMsg = null;

    public GolemZoneScreen(Screen parent) {
        super(Component.literal("Golem Zones"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int right = this.width - 10;
        int formX = this.width / 2 + 5;
        int fieldW = right - formX;
        int y = 30;
        int fh = 18;
        int gap = 4;

        // ── Zone form ──────────────────────────────────────────────────────────
        fieldName = addEditBox(formX, y, fieldW, fh, "zone name");        y += fh + gap;
        fieldMinX = addEditBox(formX, y, fieldW, fh, "minX");             y += fh + gap;
        fieldMinZ = addEditBox(formX, y, fieldW, fh, "minZ");             y += fh + gap;
        fieldMaxX = addEditBox(formX, y, fieldW, fh, "maxX");             y += fh + gap;
        fieldMaxZ = addEditBox(formX, y, fieldW, fh, "maxZ");             y += fh + gap + 2;

        int btnW = (fieldW - 4) / 3;

        addRenderableWidget(Button.builder(Component.literal("Add"), b -> submitZone("add"))
                .bounds(formX, y, btnW, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Update"), b -> submitZone("update"))
                .bounds(formX + btnW + 2, y, btnW, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
            String name = fieldName.getValue().trim();
            if (!name.isEmpty()) {
                ClientNetworking.sendZoneEdit("remove", name, 0, 0, 0, 0);
            }
        }).bounds(formX + (btnW + 2) * 2, y, btnW, 16).build());

        y += 24;

        // ── Home point ────────────────────────────────────────────────────────
        fieldHomeX = addEditBox(formX, y, btnW, fh, "X");
        fieldHomeY = addEditBox(formX + btnW + 2, y, btnW, fh, "Y");
        fieldHomeZ = addEditBox(formX + (btnW + 2) * 2, y, btnW, fh, "Z");
        y += fh + gap + 2;

        addRenderableWidget(Button.builder(Component.literal("Set Home"), b -> submitHome())
                .bounds(formX, y, fieldW, 16).build());

        y += 24;

        // ── Back button ───────────────────────────────────────────────────────
        addRenderableWidget(Button.builder(Component.literal("Back"), b ->
                this.minecraft.setScreenAndShow(parent)
        ).bounds(formX, y, fieldW, 16).build());
    }

    private EditBox addEditBox(int x, int y, int w, int h, String hint) {
        EditBox box = new EditBox(this.font, x, y, w, h, Component.literal(hint));
        box.setMaxLength(64);
        box.setHint(Component.literal(hint));
        addRenderableWidget(box);
        return box;
    }

    private void submitZone(String op) {
        try {
            String name = fieldName.getValue().trim();
            if (name.isEmpty()) { errorMsg = "Zone name required"; return; }
            int minX = parseInt(fieldMinX.getValue());
            int minZ = parseInt(fieldMinZ.getValue());
            int maxX = parseInt(fieldMaxX.getValue());
            int maxZ = parseInt(fieldMaxZ.getValue());
            ClientNetworking.sendZoneEdit(op, name, minX, minZ, maxX, maxZ);
            errorMsg = null;
        } catch (NumberFormatException e) {
            errorMsg = "Coords must be integers";
        }
    }

    private void submitHome() {
        try {
            int x = parseInt(fieldHomeX.getValue());
            int y = parseInt(fieldHomeY.getValue());
            int z = parseInt(fieldHomeZ.getValue());
            ClientNetworking.sendHome(x, y, z);
            errorMsg = null;
        } catch (NumberFormatException e) {
            errorMsg = "Home coords must be integers";
        }
    }

    private static int parseInt(String s) throws NumberFormatException {
        return Integer.parseInt(s.trim());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        int listX = 10;
        int listW = this.width / 2 - 15;
        int formX = this.width / 2 + 5;
        int rightW = this.width - 10 - formX;

        g.centeredText(this.font, this.title, this.width / 2, 8, COL_LABEL);

        // Left: zone list header
        g.text(this.font, "Active Zones:", listX, 20, COL_HEADER);

        List<ZoneLine> zones = ClientNetworking.zones();
        if (zones.isEmpty()) {
            g.text(this.font, "(none)", listX, 32, COL_ZONE);
        } else {
            int y = 32;
            for (ZoneLine z : zones) {
                String line = z.name() + "  (" + z.minX() + "," + z.minZ()
                        + ") -> (" + z.maxX() + "," + z.maxZ() + ")";
                // Clip to list width
                String clipped = this.font.plainSubstrByWidth(line, listW);
                g.text(this.font, clipped, listX, y, COL_ZONE);
                y += 11;
                if (y > this.height - 20) break;
            }
        }

        // Right: form labels
        int fy = 30;
        int lx = formX - this.font.width("minX  ") - 2;
        // Only draw if we have space
        if (lx >= listX + listW) {
            g.text(this.font, "Name",  lx, fy,      COL_LABEL); fy += 22;
            g.text(this.font, "minX",  lx, fy,      COL_LABEL); fy += 22;
            g.text(this.font, "minZ",  lx, fy,      COL_LABEL); fy += 22;
            g.text(this.font, "maxX",  lx, fy,      COL_LABEL); fy += 22;
            g.text(this.font, "maxZ",  lx, fy,      COL_LABEL); fy += 22 + 2 + 16 + 4;
            g.text(this.font, "Home",  lx, fy,      COL_HEADER);
        }

        // Error line
        if (errorMsg != null) {
            g.text(this.font, errorMsg, formX, this.height - 28, COL_ERR);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
