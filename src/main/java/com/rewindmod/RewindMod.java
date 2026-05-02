package com.rewindmod;

import com.rewindmod.network.RewindServerNetworking;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RewindMod implements ModInitializer {

    public static final String MOD_ID = "rewindmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Rewind Mod initialized! Waktu bisa dimundurkan.");

        // Register server-side networking
        RewindServerNetworking.register();
    }
}
