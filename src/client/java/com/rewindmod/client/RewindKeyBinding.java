package com.rewindmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Registers the "<" (comma/less-than) keybinding for triggering a rewind.
 *
 * Note: GLFW key for "<" on most keyboards is GLFW_KEY_COMMA (44) or
 *       GLFW_KEY_WORLD_1 depending on locale.
 *       We use GLFW_KEY_COMMA which corresponds to the "," key;
 *       users can rebind in controls menu.
 */
public class RewindKeyBinding {

    public static KeyBinding rewindKey;

    private static final KeyBinding.Category REWIND_CATEGORY =
            KeyBinding.Category.create(Identifier.of("rewindmod", "main"));

    public static void register() {
        rewindKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rewindmod.rewind",       // translation key
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_COMMA,          // "<" / "," key (rebindable)
                REWIND_CATEGORY               // category in controls menu
        ));

        // Poll the keybinding each client tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Tick the cooldown tracker
            RewindCooldownTracker.getInstance().tick();

            if (rewindKey.wasPressed()) {
                if (!RewindCooldownTracker.getInstance().isOnCooldown()) {
                    // Optimistic local cooldown for immediate feedback
                    RewindCooldownTracker.getInstance().triggerLocalCooldown();
                    // Send request to server
                    RewindClientNetworking.sendRewindRequest();
                }
            }
        });
    }
}
