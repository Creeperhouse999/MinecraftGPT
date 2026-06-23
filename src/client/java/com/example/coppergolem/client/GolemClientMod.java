package com.example.coppergolem.client;

import com.example.coppergolem.client.net.ClientNetworking;
import com.example.coppergolem.client.screen.GolemInventoryScreen;
import com.example.coppergolem.inventory.GolemMenu;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;

public class GolemClientMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        GolemKeybind.register();
        ClientNetworking.register();
        // Register the container screen for GolemMenu; accessible via fabric-menu-api-v1 classtweaker.
        MenuScreens.register(GolemMenu.TYPE, GolemInventoryScreen::new);
    }
}
