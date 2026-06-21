package com.example.coppergolem;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CopperGolemMod implements ModInitializer {
    public static final String MOD_ID = "coppergolem";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOG.info("[coppergolem] server init");
    }
}
