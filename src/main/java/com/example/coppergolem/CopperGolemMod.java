package com.example.coppergolem;

import com.example.coppergolem.net.Packets;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CopperGolemMod implements ModInitializer {
    public static final String MOD_ID = "coppergolem";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOG.info("[coppergolem] server init");
        // Plan A foundation: reference the field so the persistent AttachmentType registers.
        if (GolemAttachments.GOLEM_DATA == null) {
            throw new IllegalStateException("golem attachment failed to register");
        }
        // Register all custom packet payload types (B10).
        Packets.register();
    }
}
