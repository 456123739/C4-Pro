package com.qisumei.c4.client;

import com.qisumei.c4.network.C4HudPacket;
import com.qisumei.c4.qis4c4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * C4 的客户端 HUD。
 * <p>
 * 三块内容：
 * <ol>
 *     <li><b>全员小字提示</b>（屏幕上方）：C4 已被安装 / 已被拆除 / 已爆炸 / 某人正在拆除；</li>
 *     <li><b>拆除进度条</b>（屏幕中上）：数据来自实体的同步字段，因此<b>所有玩家</b>都能看到，
 *     不再只有拆除者自己可见；</li>
 *     <li><b>个人提示</b>（屏幕下方）：安装进度、以及只发给自己的错误提示。</li>
 * </ol>
 */
@Mod.EventBusSubscriber(modid = qis4c4.MODID, value = Dist.CLIENT)
public class C4Hud {

    private static final float NOTICE_SCALE = 0.8F;
    private static final int NOTICE_SPACING = 11;

    private static final class Notice {
        final String text;
        final long expireAt;

        Notice(String text, long expireAt) {
            this.text = text;
            this.expireAt = expireAt;
        }
    }

    private static final List<Notice> NOTICES = new ArrayList<>();
    private static final int MAX_NOTICES = 3;

    private static String selfMessage = "";
    private static long selfMessageExpireAt = 0L;

    private static long installStartedAt = 0L;
    private static long installDurationMs = 0L;

    // 拆除面板：由服务端每 4 tick 广播一次，因此所有玩家（无论距离）都能看到
    private static String remoteDefuseName = "";
    private static float remoteDefuseProgress = 0.0F;
    private static long remoteDefuseExpireAt = 0L;

    // ======================= 网络包入口 =======================

    public static void handle(C4HudPacket packet) {
        switch (packet.getKind()) {
            case C4HudPacket.KIND_NOTICE -> pushNotice(packet.getText(), packet.getDurationMs());
            case C4HudPacket.KIND_SELF_MESSAGE -> {
                selfMessage = packet.getText();
                selfMessageExpireAt = now() + Math.max(500, packet.getDurationMs());
            }
            case C4HudPacket.KIND_INSTALL_START -> {
                installStartedAt = now();
                installDurationMs = Math.max(100, packet.getDurationMs());
            }
            case C4HudPacket.KIND_INSTALL_END -> {
                installStartedAt = 0L;
                installDurationMs = 0L;
            }
            case C4HudPacket.KIND_DEFUSE_PANEL -> {
                remoteDefuseName = packet.getText();
                remoteDefuseProgress = packet.getValue();
                // 服务端 4 tick（200ms）一次，留一点余量避免闪烁
                remoteDefuseExpireAt = now() + 600L;
            }
            default -> {
            }
        }
    }

    private static void pushNotice(String text, int durationMs) {
        if (text == null || text.isEmpty()) {
            return;
        }
        NOTICES.add(new Notice(text, now() + Math.max(500, durationMs)));
        while (NOTICES.size() > MAX_NOTICES) {
            NOTICES.remove(0);
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    // ======================= 渲染 =======================

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        Font font = mc.font;
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        renderDefuseProgress(graphics, font, mc, width, height);
        renderNotices(graphics, font, width, height);
        renderSelfStatus(graphics, font, width, height);
    }

    /** 全员可见的拆除进度条。 */
    private static void renderDefuseProgress(GuiGraphics graphics, Font font, Minecraft mc, int width, int height) {
        long current = now();
        if (current >= remoteDefuseExpireAt || remoteDefuseName.isEmpty()) {
            return;
        }
        float progress = remoteDefuseProgress;
        int percent = Math.round(progress * 100.0F);
        String name = remoteDefuseName;
        String title = "§e" + name + " §f正在拆除 C4";

        int centerY = (int) (height * 0.30F);
        drawCenteredScaled(graphics, font, title, width, centerY, 1.0F, 0xFFFFFF);

        int barWidth = 120;
        int barHeight = 5;
        int barX = (width - barWidth) / 2;
        int barY = centerY + 13;
        graphics.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + barHeight + 1, 0xAA000000);
        graphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF3A3A3A);
        int filled = Math.max(0, Math.min(barWidth, (int) (barWidth * progress)));
        if (filled > 0) {
            graphics.fill(barX, barY, barX + filled, barY + barHeight, 0xFF55D455);
        }
        drawCenteredScaled(graphics, font, "§7" + percent + "%", width, barY + barHeight + 4, 0.85F, 0xAAAAAA);
    }

    /** 屏幕上方的小字广播。 */
    private static void renderNotices(GuiGraphics graphics, Font font, int width, int height) {
        long current = now();
        NOTICES.removeIf(notice -> notice.expireAt <= current);
        if (NOTICES.isEmpty()) {
            return;
        }
        int baseY = (int) (height * 0.18F);
        for (int i = 0; i < NOTICES.size(); i++) {
            String text = NOTICES.get(i).text;
            float scale = NOTICE_SCALE;
            int textWidth = (int) (font.width(text) * scale);
            int y = baseY + i * NOTICE_SPACING;
            int x = (width - textWidth) / 2;
            graphics.fill(x - 4, y - 3, x + textWidth + 4, y + 9, 0x66000000);
            graphics.pose().pushPose();
            graphics.pose().translate(x, y, 0.0F);
            graphics.pose().scale(scale, scale, 1.0F);
            graphics.drawString(font, text, 0, 0, 0xFFFFFF, true);
            graphics.pose().popPose();
        }
    }

    /** 个人提示 + 安装进度。 */
    private static void renderSelfStatus(GuiGraphics graphics, Font font, int width, int height) {
        long current = now();

        if (installStartedAt > 0L) {
            long elapsed = current - installStartedAt;
            if (elapsed >= installDurationMs) {
                installStartedAt = 0L;
            } else {
                double remainSeconds = (installDurationMs - elapsed) / 1000.0;
                String text = String.format(java.util.Locale.ROOT, "§e安装中... %.1f 秒", remainSeconds);
                int y = (int) (height * 0.62F);
                drawCenteredScaled(graphics, font, text, width, y, 1.0F, 0xFFFFFF);

                int barWidth = 100;
                int barX = (width - barWidth) / 2;
                int barY = y + 12;
                float progress = (float) Math.min(1.0, elapsed / (double) installDurationMs);
                graphics.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + 5, 0xAA000000);
                graphics.fill(barX, barY, barX + barWidth, barY + 4, 0xFF3A3A3A);
                graphics.fill(barX, barY, barX + (int) (barWidth * progress), barY + 4, 0xFFFFC24B);
            }
        }

        if (current < selfMessageExpireAt && !selfMessage.isEmpty()) {
            int y = (int) (height * 0.70F);
            drawCenteredScaled(graphics, font, selfMessage, width, y, 0.9F, 0xFFFFFF);
        } else if (current >= selfMessageExpireAt) {
            selfMessage = "";
        }
    }

    private static void drawCenteredScaled(GuiGraphics graphics, Font font, String text, int width, int y,
                                           float scale, int color) {
        int textWidth = (int) (font.width(text) * scale);
        graphics.pose().pushPose();
        graphics.pose().translate((width - textWidth) / 2.0F, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(font, text, 0, 0, color, true);
        graphics.pose().popPose();
    }

    /** 供调试/测试使用：当前小字提示条数。 */
    public static int getNoticeCount() {
        return NOTICES.size();
    }

    /** 供调试/测试使用：当前个人提示文本。 */
    public static String getSelfMessage() {
        return selfMessage;
    }
}
