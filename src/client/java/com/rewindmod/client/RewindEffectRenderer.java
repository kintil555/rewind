package com.rewindmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/**
 * Client-side visual effect during rewind playback.
 *
 * Effects during rewind:
 *  - Desaturated blue vignette overlay (screen darkens at edges)
 *  - Screen border flashing in cyan/blue
 *  - Progress bar showing rewind completion
 *
 * This is purely visual — the actual rewind logic is server-side.
 */
public class RewindEffectRenderer {

    private static RewindEffectRenderer INSTANCE;

    public static RewindEffectRenderer getInstance() {
        if (INSTANCE == null) INSTANCE = new RewindEffectRenderer();
        return INSTANCE;
    }

    private boolean active = false;
    private int totalFrames = 0;
    private int framesRemaining = 0;
    private int flashTick = 0;

    public static void register() {
        HudRenderCallback.EVENT.register(RewindEffectRenderer.getInstance()::render);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            RewindEffectRenderer.getInstance().tick();
        });
    }

    public void startRewind(int frames) {
        this.active = true;
        this.totalFrames = frames;
        this.framesRemaining = frames;
        this.flashTick = 0;
    }

    private void tick() {
        if (!active) return;
        flashTick++;
        if (framesRemaining > 0) {
            framesRemaining--;
        } else {
            active = false;
        }
    }

    private void render(DrawContext context, RenderTickCounter tickCounter) {
        if (!active) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        int W = context.getScaledWindowWidth();
        int H = context.getScaledWindowHeight();

        float progress = totalFrames > 0
                ? 1.0f - ((float) framesRemaining / totalFrames)
                : 1.0f;

        // Pulsing alpha based on tick (creates shimmer)
        float pulse = (float)(Math.sin(flashTick * 0.4) * 0.15 + 0.25);
        int vigAlpha = (int)(pulse * 255);

        // Blue-tinted vignette (4 gradient strips from edges)
        int vigColor = (vigAlpha << 24) | 0x001133;
        int edgeSize = W / 6;

        // Left edge
        context.fill(0, 0, edgeSize, H, vigColor);
        // Right edge
        context.fill(W - edgeSize, 0, W, H, vigColor);
        // Top edge
        context.fill(0, 0, W, H / 8, vigColor);
        // Bottom edge
        context.fill(0, H - H / 8, W, H, vigColor);

        // Cyan border flash
        int borderAlpha = (int)((Math.sin(flashTick * 0.6) * 0.3 + 0.5) * 200);
        int borderColor = (borderAlpha << 24) | 0x00AACC;
        int borderThickness = 3;
        // Top
        context.fill(0, 0, W, borderThickness, borderColor);
        // Bottom
        context.fill(0, H - borderThickness, W, H, borderColor);
        // Left
        context.fill(0, 0, borderThickness, H, borderColor);
        // Right
        context.fill(W - borderThickness, 0, W, H, borderColor);

        // "REWINDING..." text in center-top
        String rewindText = "⏪ REWINDING...";
        int textWidth = client.textRenderer.getWidth(rewindText);
        int textX = (W - textWidth) / 2;
        int textY = H / 6;
        // Shadow
        context.drawTextWithShadow(client.textRenderer,
                net.minecraft.text.Text.literal(rewindText),
                textX, textY, 0xFF00CCFF);

        // Progress bar below text
        int barW = 120;
        int barH = 4;
        int barX = (W - barW) / 2;
        int barY = textY + 12;
        context.fill(barX, barY, barX + barW, barY + barH, 0x88000000);
        int fillW = (int)(progress * barW);
        if (fillW > 0) {
            context.fill(barX, barY, barX + fillW, barY + barH, 0xFF00AAFF);
        }
    }

    public boolean isActive() { return active; }
}
