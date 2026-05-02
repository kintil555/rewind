package com.rewindmod.mixin.client;

import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Placeholder mixin for InGameHud.
 * HUD rendering is handled via HudRenderCallback instead.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    // Reserved for future hotbar integration if needed
}
