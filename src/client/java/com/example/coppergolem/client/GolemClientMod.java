package com.example.coppergolem.client;

import com.example.coppergolem.client.net.ClientNetworking;
import net.fabricmc.api.ClientModInitializer;

public class GolemClientMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        GolemKeybind.register();
        ClientNetworking.register();
    }
}
