package com.qisumei.c4.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 当掉落的 C4 在视野之外（背后 / 侧后）时，在屏幕边缘点一个小红点 + 距离，
 * 告诉你"往哪边看"。在视野内则交给 {@link DroppedC4MarkerRenderer} 的悬浮标记，避免重复。
 */
public final class DroppedC4Hud {

    private static final int EDGE_MARGIN = 30;

    private DroppedC4Hud() {
    }

    public static void render(GuiGraphics graphics) {
        DroppedC4MarkerRenderer.HudHint hint = DroppedC4MarkerRenderer.getHint();
        if (hint == null || hint.onScreen || hint.alpha <= 0.02f) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null) {
            return;
        }

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        double radians = Math.toRadians(hint.directionDegrees);
        double dirX = Math.cos(radians);
        double dirY = -Math.sin(radians);   // 屏幕坐标 y 向下

        double halfWidth = width / 2.0 - EDGE_MARGIN;
        double halfHeight = height / 2.0 - EDGE_MARGIN;
        double t = Math.min(
                halfWidth / Math.max(Math.abs(dirX), 1.0E-4),
                halfHeight / Math.max(Math.abs(dirY), 1.0E-4));

        int x = (int) Math.round(width / 2.0 + dirX * t);
        int y = (int) Math.round(height / 2.0 + dirY * t);

        int alpha = (int) (200 * hint.alpha);
        int dotColor = (alpha << 24) | 0xE84E3E;
        for (int row = -2; row <= 2; row++) {
            int half = 2 - Math.abs(row);
            graphics.fill(x - half, y + row, x + half + 1, y + row + 1, dotColor);
        }

        String text = "C4 " + Math.round(hint.distance) + "m";
        int textColor = (Math.min(255, alpha + 40) << 24) | 0xFFFFFF;
        graphics.drawString(mc.font, text, x - mc.font.width(text) / 2, y + 6, textColor, true);
    }
}
