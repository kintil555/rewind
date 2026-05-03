package com.rewindmod.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import com.rewindmod.network.RewindServerNetworking;

/**
 * Minecraft-style GUI screen for selecting rewind duration (1–15 seconds).
 *
 * Opened when player holds a Diamond in offhand and presses the rewind key (,).
 * Styled after vanilla Minecraft options screens: dark semi-transparent background,
 * centered panel, classic button style.
 */
@net.fabricmc.api.Environment(net.fabricmc.api.EnvType.CLIENT)
public class RewindConfigScreen extends Screen {

    private static final int MAX_SECONDS = 15;
    private static final int MIN_SECONDS = 1;

    private int selectedSeconds = 5;
    private RewindDurationSlider slider;

    public RewindConfigScreen() {
        super(Text.literal("⏪ Rewind Configuration"));
    }

    @Override
    protected void init() {
        int panelW = 240;
        int panelH = 140;
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2;

        int centerX = this.width / 2;
        int sliderY = panelY + 55;
        int buttonY = panelY + 95;

        // Slider for duration
        slider = new RewindDurationSlider(
                centerX - 110, sliderY, 220, 20,
                selectedSeconds
        );
        this.addDrawableChild(slider);

        // Confirm button
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("✔ Rewind!").formatted(Formatting.GREEN),
                btn -> {
                    if (!RewindCooldownTracker.getInstance().isOnCooldown()) {
                        RewindCooldownTracker.getInstance().triggerLocalCooldown();
                        ClientPlayNetworking.send(
                                new RewindServerNetworking.RewindRequestDurationPayload(slider.getSelectedSeconds()));
                    }
                    this.close();
                })
                .dimensions(centerX - 110, buttonY, 105, 20)
                .build());

        // Cancel button
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("✖ Batal").formatted(Formatting.RED),
                btn -> this.close())
                .dimensions(centerX + 5, buttonY, 105, 20)
                .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // ── Draw background dimming ──────────────────────────────────────────
        this.renderBackground(context, mouseX, mouseY, delta);

        int panelW = 240;
        int panelH = 140;
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2;

        // Draw panel background (dark stone-like box, vanilla style)
        context.fill(panelX - 2, panelY - 2, panelX + panelW + 2, panelY + panelH + 2,
                0xFF000000);
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH,
                0xBB1A1A2E);

        // Panel border (gold-ish, like Minecraft chest UI)
        drawBorder(context, panelX, panelY, panelW, panelH, 0xFF8B6914);

        // ── Title ─────────────────────────────────────────────────────────────
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("⏪ Rewind Config").formatted(Formatting.AQUA),
                this.width / 2, panelY + 12, 0xFFFFFF);

        // ── Diamond indicator ─────────────────────────────────────────────────
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("💎 Diamond Offhand terdeteksi").formatted(Formatting.DARK_AQUA),
                this.width / 2, panelY + 26, 0xFFFFFF);

        // ── Slider label ─────────────────────────────────────────────────────
        int currentSecs = slider != null ? slider.getSelectedSeconds() : selectedSeconds;
        String durationLabel = "Durasi: " + currentSecs + " detik";
        if (currentSecs == MAX_SECONDS) durationLabel += " (MAKS)";
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(durationLabel).formatted(
                        currentSecs >= 10 ? Formatting.YELLOW : Formatting.WHITE),
                this.width / 2, panelY + 42, 0xFFFFFF);

        // ── Render widgets ────────────────────────────────────────────────────
        super.render(context, mouseX, mouseY, delta);

        // ── Cooldown warning ─────────────────────────────────────────────────
        if (RewindCooldownTracker.getInstance().isOnCooldown()) {
            int remaining = (RewindCooldownTracker.getInstance().getCooldownTicks() + 19) / 20;
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("⚠ Cooldown: " + remaining + "s").formatted(Formatting.RED),
                    this.width / 2, panelY + panelH + 8, 0xFFFFFF);
        }
    }

    /** Draw a 1-pixel border around a rect with the given color */
    private void drawBorder(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);           // top
        ctx.fill(x, y + h - 1, x + w, y + h, color);   // bottom
        ctx.fill(x, y, x + 1, y + h, color);            // left
        ctx.fill(x + w - 1, y, x + w, y + h, color);   // right
    }

    @Override
    public boolean shouldPause() {
        return false; // Don't pause the game
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    // ── Custom slider ─────────────────────────────────────────────────────────

    private static class RewindDurationSlider extends SliderWidget {

        private int selectedSeconds;

        public RewindDurationSlider(int x, int y, int width, int height, int initialSeconds) {
            super(x, y, width, height,
                    Text.empty(),
                    (initialSeconds - MIN_SECONDS) / (double)(MAX_SECONDS - MIN_SECONDS));
            this.selectedSeconds = initialSeconds;
            updateMessage();
        }

        public int getSelectedSeconds() {
            return MIN_SECONDS + (int) Math.round(this.value * (MAX_SECONDS - MIN_SECONDS));
        }

        @Override
        protected void updateMessage() {
            int secs = getSelectedSeconds();
            this.selectedSeconds = secs;
            String label = secs + " detik";
            this.setMessage(Text.literal("◀ " + label + " ▶").formatted(
                    secs >= 10 ? Formatting.YELLOW : Formatting.AQUA));
        }

        @Override
        protected void applyValue() {
            this.selectedSeconds = getSelectedSeconds();
        }
    }
}
