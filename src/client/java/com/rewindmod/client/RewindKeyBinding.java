package com.rewindmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Registers the "," (comma) keybinding for triggering a rewind.
 *
 * Behaviour:
 *  - If player holds a Diamond in offhand → open RewindConfigScreen (slider GUI).
 *  - Otherwise → trigger rewind immediately with default duration (server-side 5s).
 */
public class RewindKeyBinding {

    public static KeyBinding rewindKey;

    private static final KeyBinding.Category REWIND_CATEGORY =
            KeyBinding.Category.create(Identifier.of("rewindmod", "main"));

    public static void register() {
        rewindKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rewindmod.rewind",       // translation key
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_COMMA,          // "," key (rebindable)
                REWIND_CATEGORY               // category in controls menu
        ));

        // Poll the keybinding each client tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Tick the cooldown tracker
            RewindCooldownTracker.getInstance().tick();

            if (rewindKey.wasPressed()) {
                handleRewindKeyPress(client);
            }
        });
    }

    private static void handleRewindKeyPress(MinecraftClient client) {
        if (client.player == null) return;

        // Check if holding Diamond in offhand
        boolean holdingDiamond = client.player.getOffHandStack().isOf(Items.DIAMOND);

        if (holdingDiamond) {
            // Open config GUI — no cooldown check yet; user hasn't committed to rewind
            client.execute(() -> client.setScreen(new RewindConfigScreen()));
        } else {
            // Immediate rewind with default duration (5s)
            if (!RewindCooldownTracker.getInstance().isOnCooldown()) {
                RewindCooldownTracker.getInstance().triggerLocalCooldown();
                RewindClientNetworking.sendRewindRequest();
            }
        }
    }
}
