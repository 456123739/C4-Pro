package com.qisumei.c4.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * C4 的独立 Overlay UI（沿用模组原有的渲染系统）。
 * <p>
 * 原有能力（未改动）：
 * <ul>
 *     <li>单行居中提示 / 安装倒计时，绘制在准星上方的固定偏移处，不挡视野；</li>
 *     <li>100ms 缓存刷新，避免每帧重算文本宽度；</li>
 *     <li>只显示当前玩家自己的消息。</li>
 * </ul>
 * 在此基础上新增两条“全员”通道，位置都在<b>准心下方</b>，不加背景板、不挡准星：
 * <ul>
 *     <li>{@link #showNotice}：小字播报（C4 已被安装 / 已被拆除 / 已爆炸）；</li>
 *     <li>{@link #showDefuseProgress}：拆除进度（名字 + 百分比 + 细进度条），所有人可见。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = "qis4c4")
public class ClientMessageRenderer {

    private static class MessageData {
        String text;
        long expireTime;
        boolean isCountdown;
        long startTime;
        int totalDuration;  // 总时长（毫秒）
        String type;        // "install"
        long lastUpdateTime = 0;

        private static final DecimalFormat FORMAT = new DecimalFormat("0.0");

        MessageData(String text, long expireTime) {
            this.text = text;
            this.expireTime = expireTime;
            this.isCountdown = false;
            this.type = "message";
        }

        MessageData(long startTime, int totalDuration, String type) {
            this.text = "";
            this.isCountdown = true;
            this.startTime = startTime;
            this.totalDuration = totalDuration;
            this.type = type;
            this.expireTime = startTime + totalDuration + 500;
            this.lastUpdateTime = startTime;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireTime;
        }

        String getCountdownText() {
            if (!isCountdown) return text;
            long now = System.currentTimeMillis();
            long elapsed = now - startTime;
            double remaining = Math.max(0, (totalDuration - elapsed) / 1000.0);

            if (remaining <= 0.05) return null;

            String remainingStr = FORMAT.format(remaining);

            if ("install".equals(type)) {
                return "§e 安装中... " + remainingStr + " 秒 ";
            }
            return text;
        }
    }

    private static final Map<UUID, MessageData> currentMessages = new HashMap<>();

    private static long lastRenderTime = 0;
    private static String cachedText = "";
    private static int cachedX = 0;
    private static int cachedY = 0;
    private static final long UPDATE_INTERVAL_MS = 100;
    private static final int Y_OFFSET = -30;

    // ---- 新增：全员小字（准心下方） ----
    private static final int NOTICE_OFFSET_Y = 14;
    private static final float NOTICE_SCALE = 0.85F;
    private static final int NOTICE_COLOR = 0xFFFFFF;
    private static String noticeText = "";
    private static long noticeExpireAt = 0L;

    // ---- 新增：拆除进度（全员可见，准心下方） ----
    private static final int DEFUSE_BAR_WIDTH = 60;
    private static final int DEFUSE_BAR_HEIGHT = 2;
    private static String defuseName = "";
    private static float defuseProgress = 0.0F;
    private static long defuseExpireAt = 0L;

    // ======================= 原有接口 =======================

    public static void showMessage(Component message, int durationMs) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        UUID playerId = mc.player.getUUID();
        long expireTime = System.currentTimeMillis() + durationMs;
        currentMessages.put(playerId, new MessageData(message.getString(), expireTime));
        updateCache(mc);
    }

    /** 开始安装倒计时。 */
    public static void startInstallCountdown(int totalMs) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        UUID playerId = mc.player.getUUID();
        long now = System.currentTimeMillis();
        MessageData msg = new MessageData(now, Math.max(100, totalMs), "install");
        currentMessages.put(playerId, msg);
        updateCache(mc);
    }

    public static void clearMessage() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            currentMessages.remove(mc.player.getUUID());
        }
        cachedText = "";
    }

    // ======================= 新增接口 =======================

    /** 全员小字播报（准心下方，无背景板）。 */
    public static void showNotice(String text, int durationMs) {
        if (text == null || text.isEmpty()) {
            return;
        }
        noticeText = text;
        noticeExpireAt = System.currentTimeMillis() + Math.max(500, durationMs);
    }

    /**
     * 全员可见的拆除进度。
     *
     * @param name     拆除者名字
     * @param progress 0~1
     */
    public static void showDefuseProgress(String name, float progress) {
        defuseName = name == null ? "" : name;
        defuseProgress = Math.max(0.0F, Math.min(1.0F, progress));
        // 服务端每 4 tick 推一次，留一点余量避免闪烁
        defuseExpireAt = System.currentTimeMillis() + 600L;
    }

    // ======================= 渲染 =======================

    private static void updateCache(Minecraft mc) {
        if (mc.player == null) return;

        UUID playerId = mc.player.getUUID();
        MessageData msg = currentMessages.get(playerId);

        if (msg == null || msg.isExpired()) {
            if (msg != null && msg.isExpired()) {
                currentMessages.remove(playerId);
            }
            cachedText = "";
            return;
        }

        String displayText = msg.isCountdown ? msg.getCountdownText() : msg.text;
        if (displayText == null || displayText.isEmpty()) {
            cachedText = "";
            if (msg.isCountdown) {
                currentMessages.remove(playerId);
            }
            return;
        }

        Font font = mc.font;
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int textWidth = font.width(displayText);
        cachedX = (screenWidth - textWidth) / 2;
        cachedY = mc.getWindow().getGuiScaledHeight() / 2 + Y_OFFSET;
        cachedText = displayText;
        lastRenderTime = System.currentTimeMillis();
    }

    private static void cleanExpiredMessages() {
        long now = System.currentTimeMillis();
        currentMessages.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;

        cleanExpiredMessages();

        GuiGraphics guiGraphics = event.getGuiGraphics();
        Font font = mc.font;
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        // ---- 1) 原有：自己的提示 / 安装倒计时 ----
        UUID playerId = mc.player.getUUID();
        MessageData msg = currentMessages.get(playerId);
        if (msg != null && !msg.isExpired()) {
            long now = System.currentTimeMillis();
            if (now - lastRenderTime >= UPDATE_INTERVAL_MS) {
                updateCache(mc);
            }
            if (!cachedText.isEmpty()) {
                guiGraphics.drawString(font, cachedText, cachedX, cachedY, 0xFFFFFF, false);
            }
        } else {
            cachedText = "";
        }

        // ---- 2) 新增：拆除进度（优先）或全员小字，都在准心下方 ----
        long current = System.currentTimeMillis();
        int lineY = height / 2 + NOTICE_OFFSET_Y;
        if (current < defuseExpireAt && !defuseName.isEmpty()) {
            renderDefuse(guiGraphics, font, width, lineY);
        } else if (current < noticeExpireAt) {
            drawCentered(guiGraphics, font, noticeText, width, lineY, NOTICE_SCALE, NOTICE_COLOR);
        }
    }

    /** 拆除进度：一行字 + 一条很细的进度条，整体宽度不超过 120px，不挡准星。 */
    private static void renderDefuse(GuiGraphics guiGraphics, Font font, int width, int y) {
        int percent = Math.round(defuseProgress * 100.0F);
        String text = "§e" + defuseName + " §f拆除中 §e" + percent + "%";
        drawCentered(guiGraphics, font, text, width, y, NOTICE_SCALE, NOTICE_COLOR);

        int barX = (width - DEFUSE_BAR_WIDTH) / 2;
        int barY = y + 10;
        guiGraphics.fill(barX, barY, barX + DEFUSE_BAR_WIDTH, barY + DEFUSE_BAR_HEIGHT, 0x80000000);
        int filled = (int) (DEFUSE_BAR_WIDTH * defuseProgress);
        if (filled > 0) {
            guiGraphics.fill(barX, barY, barX + filled, barY + DEFUSE_BAR_HEIGHT, 0xFF55D455);
        }
    }

    private static void drawCentered(GuiGraphics guiGraphics, Font font, String text, int width, int y,
                                     float scale, int color) {
        int textWidth = (int) (font.width(text) * scale);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate((width - textWidth) / 2.0F, y, 0.0F);
        guiGraphics.pose().scale(scale, scale, 1.0F);
        guiGraphics.drawString(font, text, 0, 0, color, true);
        guiGraphics.pose().popPose();
    }
}
