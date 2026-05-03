package com.rewindmod.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

/**
 * Renders the Rewind cooldown indicator in Minecraft style.
 *
 * Design: a single hotbar-style slot (20×20 pixels) sitting to the right
 * of the hotbar, containing the Clock item icon and a cooldown overlay.
 * When ready, the slot glows green. When on cooldown, it dims with a
 * standard Minecraft item-cooldown overlay and shows remaining seconds.
 *
 *  ┌────┐
 *  │ 🕐 │  ← Clock item (Minecraft-style slot)
 *  │ 8s │  ← cooldown text, or blank when ready
 *  └────┘
 */
public class RewindHudRenderer {

    // Slot dimensions — same as hotbar slot (20×20 with 1px border)
    private static final int SLOT_SIZE = 20;
    private static final int MARGIN_FROM_HOTBAR = 4; // pixels gap between hotbar and our slot
    private static final int HOTBAR_HEIGHT = 22;     // vanilla hotbar height

    // Clock item stack (singleton, just for rendering)
    private static final ItemStack CLOCK_STACK = new ItemStack(Items.CLOCK);

    // Colors
    private static final int COLOR_SLOT_BG      = 0xFF8B8B8B; // grey slot background (vanilla hotbar tile color)
    private static final int COLOR_SLOT_BORDER   = 0xFF373737; // dark border
    private static final int COLOR_SLOT_HIGHLIGHT= 0xFFFFFFFF; // top-left highlight
    private static final int COLOR_COOLDOWN_DIM  = 0xAA000000; // cooldown shade overlay
    private static final int COLOR_READY_GLOW    = 0x4400FF44; // green tint when ready
    private static final int COLOR_TEXT_CD       = 0xFFFFFFFF;
    private static final int COLOR_TEXT_READY    = 0xFF55FF55;

    public static void register() {
        HudRenderCallback.EVENT.register(RewindHudRenderer::render);
    }

    private static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (client.options.hudHidden) return;

        RewindCooldownTracker tracker = RewindCooldownTracker.getInstance();

        int screenW = context.getScaledWindowWidth();
        int screenH = context.getScaledWindowHeight();

        // Hotbar is 182px wide, centered
        int hotbarW = 182;
        int hotbarX = (screenW - hotbarW) / 2;
        int hotbarY = screenH - HOTBAR_HEIGHT - 2;

        // Place our slot immediately to the right of the hotbar
        int slotX = hotbarX + hotbarW + MARGIN_FROM_HOTBAR;
        int slotY = hotbarY + (HOTBAR_HEIGHT - SLOT_SIZE) / 2; // vertically centered with hotbar

        // Clamp to screen
        if (slotX + SLOT_SIZE > screenW) {
            slotX = hotbarX - SLOT_SIZE - MARGIN_FROM_HOTBAR;
        }

        boolean ready = !tracker.isOnCooldown();
        float progress = tracker.getProgress(); // 0.0 = just triggered, 1.0 = ready

        // ── Draw slot background (Minecraft style) ────────────────────────────

        // Outer dark border
        context.fill(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, COLOR_SLOT_BORDER);

        // Inner grey fill
        context.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, COLOR_SLOT_BG);

        // Top-left highlight (1px)
        context.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 1, slotY + 2, COLOR_SLOT_HIGHLIGHT);
        context.fill(slotX + 1, slotY + 1, slotX + 2, slotY + SLOT_SIZE - 1, COLOR_SLOT_HIGHLIGHT);

        // ── Draw clock item icon ──────────────────────────────────────────────
        // Item renders at 16×16 within the 20×20 slot, offset by 2px
        context.drawItem(CLOCK_STACK, slotX + 2, slotY + 2);

        // ── Cooldown overlay (vanilla-style darkening from bottom up) ─────────
        if (!ready) {
            // How much is still cooling: 1.0 = full cooldown, 0.0 = done
            float cooldownFraction = 1.0f - progress;
            int overlayHeight = (int)(cooldownFraction * (SLOT_SIZE - 2));
            if (overlayHeight > 0) {
                // Dark overlay fills from the BOTTOM up, like vanilla cooldown
                int overlayTop = slotY + 1 + (SLOT_SIZE - 2 - overlayHeight);
                context.fill(slotX + 1, overlayTop,
                        slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1,
                        COLOR_COOLDOWN_DIM);
            }
        } else {
            // Green glow when ready
            context.fill(slotX + 1, slotY + 1,
                    slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1,
                    COLOR_READY_GLOW);
        }

        // ── Cooldown seconds label (below slot) ───────────────────────────────
        if (!ready) {
            String cdText = tracker.getCooldownSeconds() + "s";
            int textW = client.textRenderer.getWidth(cdText);
            int textX = slotX + (SLOT_SIZE - textW) / 2;
            int textY = slotY + SLOT_SIZE + 1;
            context.drawTextWithShadow(client.textRenderer,
                    Text.literal(cdText), textX, textY, COLOR_TEXT_CD);
        }
        // When ready: no text needed — the green glow is enough signal
    }
}
