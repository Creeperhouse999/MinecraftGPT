package com.example.coppergolem.client;

import com.example.coppergolem.client.screen.GolemControlScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Registers the "open golem control" key (default {@code G}) and polls it each
 * client tick, opening {@link GolemControlScreen} when pressed.
 *
 * <p>MC 26.2 verified APIs:
 * <ul>
 *   <li>{@link KeyMappingHelper#registerKeyMapping(KeyMapping)} — Fabric
 *       key-mapping-api-v1 (package {@code ...client.keymapping.v1}).</li>
 *   <li>{@link KeyMapping#KeyMapping(String, InputConstants.Type, int, KeyMapping.Category)}
 *       — the category is now a {@link KeyMapping.Category} record, created via
 *       {@link KeyMapping.Category#register(Identifier)}.</li>
 *   <li>{@link ClientTickEvents#END_CLIENT_TICK} — poll {@link KeyMapping#consumeClick()}.</li>
 * </ul>
 */
public final class GolemKeybind {

    private GolemKeybind() {}

    /** Translation-key category id for the keybinds screen grouping. */
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath("coppergolem", "main"));

    /** The "open control screen" mapping; {@code G} by default. */
    public static final KeyMapping OPEN =
            new KeyMapping("key.coppergolem.open",
                    InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY);

    /** Register the mapping and the per-tick poll. Call from client init. */
    public static void register() {
        KeyMappingHelper.registerKeyMapping(OPEN);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // consumeClick() returns true once per press; drain so repeated holds
            // don't reopen the screen every tick.
            while (OPEN.consumeClick()) {
                if (client.player != null) {
                    client.setScreenAndShow(new GolemControlScreen());
                }
            }
        });
    }
}
