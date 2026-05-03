package com.rewindmod.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RewindModClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("rewindmod-client");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Rewind Mod client initialized!");

        // Register client-side networking (S2C cooldown sync + rewind start)
        RewindClientNetworking.register();

        // Register keybinding
        RewindKeyBinding.register();

        // Register HUD renderer (Minecraft-style clock slot)
        RewindHudRenderer.register();

        // Register screen effect renderer (vignette/border during rewind)
        RewindEffectRenderer.register();
    }
}
