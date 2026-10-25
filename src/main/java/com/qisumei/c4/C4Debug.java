package com.qisumei.c4;

import com.qisumei.c4.network.C4HudPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * 轻量级观测钩子。
 * <p>
 * 默认关闭，对正常游戏没有任何影响；打开后可以把“每一次滴滴声、每一个发给玩家的 HUD 包”
 * 交给监听器，用于自动化测试（{@code -Dqis4c4.selftest=true}）或服务端排障日志。
 */
public final class C4Debug {

    public interface Listener {
        default void onBeep(ServerLevel level, Vec3 bombPos, int ticksLeft) {
        }

        default void onPacket(ServerPlayer player, C4HudPacket packet) {
        }
    }

    @Nullable
    private static volatile Listener listener;

    private C4Debug() {
    }

    public static void setListener(@Nullable Listener newListener) {
        listener = newListener;
    }

    public static void beep(ServerLevel level, Vec3 bombPos, int ticksLeft) {
        Listener current = listener;
        if (current != null) {
            current.onBeep(level, bombPos, ticksLeft);
        }
    }

    public static void packet(ServerPlayer player, C4HudPacket packet) {
        Listener current = listener;
        if (current != null) {
            current.onPacket(player, packet);
        }
    }
}
