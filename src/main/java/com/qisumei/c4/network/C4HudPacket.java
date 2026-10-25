package com.qisumei.c4.network;

import com.qisumei.c4.client.ClientMessageRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 -> 客户端的 HUD 提示包。
 * <p>
 * 只承载文字，不承载逻辑：所有状态判定仍然在服务端完成。
 */
public class C4HudPacket {

    /** 准心下方的小字提示（全员可见，例如“C4 已被安装”）。 */
    public static final byte KIND_NOTICE = 0;
    /** 屏幕中下方的个人提示（只有接收者能看到，例如“C4只能安装在指定方块上”）。 */
    public static final byte KIND_SELF_MESSAGE = 1;
    /** 开始显示安装进度条（3.5 秒）。 */
    public static final byte KIND_INSTALL_START = 2;
    /** 结束安装进度条。 */
    public static final byte KIND_INSTALL_END = 3;
    /** 拆除进度面板（全员可见），{@code text} 为拆除者名字，{@code value} 为 0~1 的进度。 */
    public static final byte KIND_DEFUSE_PANEL = 4;

    private final byte kind;
    private final String text;
    private final int durationMs;
    private final float value;

    public C4HudPacket(byte kind, String text, int durationMs) {
        this(kind, text, durationMs, 0.0F);
    }

    public C4HudPacket(byte kind, String text, int durationMs, float value) {
        this.kind = kind;
        this.text = text == null ? "" : text;
        this.durationMs = durationMs;
        this.value = value;
    }

    public static C4HudPacket notice(String text, int durationMs) {
        return new C4HudPacket(KIND_NOTICE, text, durationMs);
    }

    public static C4HudPacket selfMessage(String text, int durationMs) {
        return new C4HudPacket(KIND_SELF_MESSAGE, text, durationMs);
    }

    public static C4HudPacket installStart(int durationMs) {
        return new C4HudPacket(KIND_INSTALL_START, "", durationMs);
    }

    public static C4HudPacket installEnd() {
        return new C4HudPacket(KIND_INSTALL_END, "", 0);
    }

    public static C4HudPacket defusePanel(String defuserName, float progress) {
        return new C4HudPacket(KIND_DEFUSE_PANEL, defuserName, 0,
                Math.max(0.0F, Math.min(1.0F, progress)));
    }

    public byte getKind() {
        return this.kind;
    }

    public String getText() {
        return this.text;
    }

    public int getDurationMs() {
        return this.durationMs;
    }

    public float getValue() {
        return this.value;
    }

    public static void encode(C4HudPacket packet, FriendlyByteBuf buf) {
        buf.writeByte(packet.kind);
        buf.writeUtf(packet.text, 1024);
        buf.writeVarInt(packet.durationMs);
        buf.writeFloat(packet.value);
    }

    public static C4HudPacket decode(FriendlyByteBuf buf) {
        return new C4HudPacket(buf.readByte(), buf.readUtf(1024), buf.readVarInt(), buf.readFloat());
    }

    /** 客户端处理：全部交给模组原有的 Overlay 渲染器，不再另建一套 HUD。 */
    private static void handleClient(C4HudPacket packet) {
        switch (packet.kind) {
            case KIND_NOTICE -> ClientMessageRenderer.showNotice(packet.text, packet.durationMs);
            case KIND_SELF_MESSAGE ->
                    ClientMessageRenderer.showMessage(net.minecraft.network.chat.Component.literal(packet.text), packet.durationMs);
            case KIND_INSTALL_START -> ClientMessageRenderer.startInstallCountdown(packet.durationMs);
            case KIND_INSTALL_END -> ClientMessageRenderer.clearMessage();
            case KIND_DEFUSE_PANEL -> ClientMessageRenderer.showDefuseProgress(packet.text, packet.value);
            default -> {
            }
        }
    }

    public static void handle(C4HudPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> handleClient(packet));
        }
        context.setPacketHandled(true);
    }
}
