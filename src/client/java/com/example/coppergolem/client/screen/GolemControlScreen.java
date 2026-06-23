package com.example.coppergolem.client.screen;

import com.example.coppergolem.client.net.ClientNetworking;
import com.example.coppergolem.net.Packets.StepLine;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Main owner control screen: prompt box + pre-approve checkbox + Send, a colored
 * plan checklist (grey/blue/green/red), Stop, per-failed-step recovery buttons,
 * and navigation to the Zone / Inventory screens. Status line at the bottom.
 *
 * <p>MC 26.2 GUI notes: rendering is done by overriding
 * {@link #extractRenderState(GuiGraphicsExtractor, int, int, float)} (the old
 * {@code render(GuiGraphics,...)} hook); text is drawn with
 * {@link GuiGraphicsExtractor#text(net.minecraft.client.gui.Font, String, int, int, int)}.
 * Widgets are added with {@link #addRenderableWidget}.</p>
 */
public final class GolemControlScreen extends Screen {

    // Plan-state colours (ARGB; alpha forced opaque).
    private static final int COL_PENDING = 0xFFAAAAAA;
    private static final int COL_RUNNING = 0xFF5599FF;
    private static final int COL_DONE    = 0xFF55FF55;
    private static final int COL_FAILED  = 0xFFFF5555;
    private static final int COL_LABEL   = 0xFFFFFFFF;

    private EditBox promptBox;
    private Checkbox preApprove;

    /** Pixel y where the plan list starts (set in init, used in render). */
    private int planTop;

    public GolemControlScreen() {
        super(Component.literal("Copper Golem"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        // ── Prompt row ──────────────────────────────────────────────────────
        promptBox = new EditBox(this.font, cx - 150, 24, 240, 20,
                Component.literal("prompt"));
        promptBox.setMaxLength(256);
        promptBox.setHint(Component.literal("Tell the golem what to do..."));
        addRenderableWidget(promptBox);

        addRenderableWidget(Button.builder(Component.literal("Send"), b -> {
            String text = promptBox.getValue().trim();
            if (!text.isEmpty()) {
                ClientNetworking.sendPrompt(text, preApprove != null && preApprove.selected());
            }
        }).bounds(cx + 96, 24, 54, 20).build());

        // ── Pre-approve + Stop row ──────────────────────────────────────────
        preApprove = Checkbox.builder(
                        Component.literal("Pre-approve tools/armor/crafting"), this.font)
                .pos(cx - 150, 48)
                .selected(false)
                .build();
        addRenderableWidget(preApprove);

        addRenderableWidget(Button.builder(Component.literal("Stop"),
                        b -> ClientNetworking.sendStop())
                .bounds(cx + 96, 48, 54, 20).build());

        // ── Navigation row ──────────────────────────────────────────────────
        addRenderableWidget(Button.builder(Component.literal("Zones"),
                        b -> minecraft.setScreenAndShow(new GolemZoneScreen(this)))
                .bounds(cx - 150, 72, 70, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Inventory"),
                        b -> minecraft.setScreenAndShow(new GolemInventoryScreen(this)))
                .bounds(cx - 76, 72, 70, 20).build());

        // ── Per-failed-step recovery buttons ────────────────────────────────
        planTop = 104;
        List<StepLine> plan = ClientNetworking.plan();
        int y = planTop;
        for (StepLine step : plan) {
            if ("FAILED".equals(step.state())) {
                addRenderableWidget(Button.builder(Component.literal("Tell it"),
                                b -> minecraft.setScreenAndShow(new GolemTellItScreen(this)))
                        .bounds(cx + 40, y - 2, 50, 14).build());
                addRenderableWidget(Button.builder(Component.literal("Stop"),
                                b -> ClientNetworking.sendErrorChoice("stop", ""))
                        .bounds(cx + 92, y - 2, 40, 14).build());
                addRenderableWidget(Button.builder(Component.literal("Self"),
                                b -> ClientNetworking.sendErrorChoice("self", ""))
                        .bounds(cx + 134, y - 2, 40, 14).build());
            }
            y += 14;
        }

        // ── Ask-gate modal: open immediately if one is pending ──────────────
        if (ClientNetworking.pendingAskGate() != null) {
            minecraft.setScreenAndShow(new GolemAskGateScreen(this));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        g.text(this.font, this.title, this.width / 2 - this.font.width(this.title) / 2, 8, COL_LABEL);

        // Colored plan checklist.
        int x = this.width / 2 - 150;
        int y = planTop;
        List<StepLine> plan = ClientNetworking.plan();
        if (plan.isEmpty()) {
            g.text(this.font, "(no active plan)", x, y, COL_PENDING);
        } else {
            for (StepLine step : plan) {
                int color = colorFor(step.state());
                String mark = switch (step.state()) {
                    case "DONE" -> "[x] ";
                    case "RUNNING" -> "[>] ";
                    case "FAILED" -> "[!] ";
                    default -> "[ ] ";
                };
                g.text(this.font, mark + step.label(), x, y, color);
                y += 14;
            }
        }

        // Status line at the bottom.
        String statusLine = "Status: " + ClientNetworking.status()
                + "   keys: " + ClientNetworking.activeKeys() + " active / "
                + ClientNetworking.coolingKeys() + " cooling";
        g.text(this.font, statusLine, x, this.height - 16, COL_LABEL);
    }

    private static int colorFor(String state) {
        return switch (state) {
            case "RUNNING" -> COL_RUNNING;
            case "DONE"    -> COL_DONE;
            case "FAILED"  -> COL_FAILED;
            default        -> COL_PENDING; // PENDING + unknown
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
