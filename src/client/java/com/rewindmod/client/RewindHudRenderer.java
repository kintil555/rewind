package com.rewindmod.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

/**
 * Renders the Rewind cooldown indicator on the HUD.
 *
 * Shows a small box near the hotbar with:
 *  - Animated fill bar showing cooldown progress
 *  - "REWIND" label
 *  - Remaining seconds when on cooldown
 *  - Green "READY" when available
 */
public class RewindHudRenderer {

    // Position & size of the indicator box
    private static final int BOX_WIDTH = 60;
    private static final int BOX_HEIGHT = 18;
    private static final int MARGIN_BOTTOM = 24; // above hotbar
    private static final int MARGIN_RIGHT = 5;   // from right edge of hotbar area

    // Colors
    private static final int COLOR_BG = 0xAA000000;       // semi-transparent black bg
    private static final int COLOR_BORDER = 0xFF888888;    // grey border
    private static final int COLOR_FILL_READY = 0xFF00CC44;    // green when ready
    private static final int COLOR_FILL_COOLDOWN = 0xFF3399FF;  // blue when cooling down
    private static final int COLOR_TEXT_READY = 0xFFFFFFFF;
    private static final int COLOR_TEXT_CD = 0xFFCCCCCC;
    private static final int COLOR_LABEL = 0xFFAAEEFF;

    public static void register() {
        HudRenderCallback.EVENT.register(RewindHudRenderer::render);
    }

    private static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (client.options.hudHidden) return;

        RewindCooldownTracker tracker = RewindCooldownTracker.getInstance();
        int scaledWidth = context.getScaledWindowWidth();
        int scaledHeight = context.getScaledWindowHeight();

        // Center the box above the hotbar (hotbar is 182px wide, centered)
        int hotbarWidth = 182;
        int hotbarX = (scaledWidth - hotbarWidth) / 2;
        // Place the rewind box just to the right of the hotbar
        int boxX = hotbarX + hotbarWidth + MARGIN_RIGHT;
        int boxY = scaledHeight - MARGIN_BOTTOM - BOX_HEIGHT;

        // If box would go off screen, place left of hotbar
        if (boxX + BOX_WIDTH > scaledWidth) {
            boxX = hotbarX - BOX_WIDTH - MARGIN_RIGHT;
        }

        boolean ready = !tracker.isOnCooldown();
        float progress = tracker.getProgress(); // 0.0 = just triggered, 1.0 = ready

        // Background
        context.fill(boxX, boxY, boxX + BOX_WIDTH, boxY + BOX_HEIGHT, COLOR_BG);

        // Border
        drawBorder(context, boxX, boxY, BOX_WIDTH, BOX_HEIGHT, COLOR_BORDER);

        // Fill bar (cooldown progress)
        int fillWidth = (int) (progress * (BOX_WIDTH - 2));
        int fillColor = ready ? COLOR_FILL_READY : COLOR_FILL_COOLDOWN;
        if (fillWidth > 0) {
            context.fill(boxX + 1, boxY + 1, boxX + 1 + fillWidth, boxY + BOX_HEIGHT - 1, fillColor);
        }

        // Label: "⏪ REWIND"
        String label = "\u23ea REWIND";
        int labelWidth = client.textRenderer.getWidth(label);
        int labelX = boxX + (BOX_WIDTH - labelWidth) / 2;
        int labelY = boxY + 2;
        context.drawTextWithShadow(client.textRenderer, Text.literal(label), labelX, labelY, COLOR_LABEL);

        // Status text
        String statusText;
        int statusColor;
        if (ready) {
            statusText = "SIAP!";
            statusColor = COLOR_TEXT_READY;
        } else {
            statusText = tracker.getCooldownSeconds() + "s";
            statusColor = COLOR_TEXT_CD;
        }
        int statusWidth = client.textRenderer.getWidth(statusText);
        int statusX = boxX + (BOX_WIDTH - statusWidth) / 2;
        int statusY = boxY + BOX_HEIGHT - 9;
        context.drawTextWithShadow(client.textRenderer, Text.literal(statusText), statusX, statusY, statusColor);
    }

    private static void drawBorder(DrawContext context, int x, int y, int w, int h, int color) {
        // top
        context.fill(x, y, x + w, y + 1, color);
        // bottom
        context.fill(x, y + h - 1, x + w, y + h, color);
        // left
        context.fill(x, y, x + 1, y + h, color);
        // right
        context.fill(x + w - 1, y, x + w, y + h, color);
    }
}
