package com.rewindmod.mixin.client;

import com.rewindmod.client.RewindEffectRenderer;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Placeholder mixin for GameRenderer.
 * Visual effects during rewind are applied via HudRenderCallback in RewindEffectRenderer.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    // Rewind screen effect is handled in RewindEffectRenderer (via HudRenderCallback)
}
