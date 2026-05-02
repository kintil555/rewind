package com.rewindmod.mixin.client;

import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Placeholder mixin for GameRenderer – reserved for future visual effects
 * (e.g. screen flash / blur when a rewind occurs).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    // Reserved for rewind visual effect (screen rewind flash)
}
