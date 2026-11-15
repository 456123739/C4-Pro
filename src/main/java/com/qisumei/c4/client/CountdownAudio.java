package com.qisumei.c4.client;

import com.qisumei.c4.sound.CountdownSegments;
import com.qisumei.c4.sound.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * 40 秒倒计时音轨的“从中途开始播”。
 * <p>
 * 原版声音包只能从文件开头播放，所以服务端把音轨按静音点切成了若干段
 * （{@link CountdownSegments}），并告诉客户端“已经过去多少毫秒”。
 * 这里从对应的那一段开始，按每段的真实时长往后排队播放，听起来与“下包时就在场”完全一致。
 * <p>
 * 线程：全部在客户端主线程（tick）调用，因此用 {@code synchronized} 只是为了保险。
 */
public final class CountdownAudio {

    /** 排队落后超过这个时间就直接跳段，避免越拖越远。 */
    private static final long LATE_SKIP_NANOS = 400_000_000L;

    private static int[] order;
    private static long[] due;
    private static int cursor;
    private static float volume = 1.0F;
    private static SoundInstance playing;

    private CountdownAudio() {
    }

    /**
     * 从指定的音轨位置开始播放。
     *
     * @param elapsedMs 已经从 0 秒过去的时间（毫秒）
     * @param vol       播放音量（服务端按距离算好）
     */
    public static synchronized void start(int elapsedMs, float vol) {
        stop();
        volume = vol <= 0.0F ? 1.0F : vol;
        int first = CountdownSegments.segmentIndexFor(elapsedMs);
        int count = CountdownSegments.COUNT - first;
        if (count <= 0) {
            return;
        }
        order = new int[count];
        due = new long[count];
        long now = System.nanoTime();
        long offset = 0L;
        for (int i = 0; i < count; i++) {
            int segment = first + i;
            order[i] = segment;
            due[i] = now + offset;
            offset += CountdownSegments.LENGTH_MS[segment] * 1_000_000L;
        }
        cursor = 0;
    }

    /** 停止并清空队列（C4 被拆/炸/清除，或玩家走出听觉半径）。 */
    public static synchronized void stop() {
        order = null;
        due = null;
        cursor = 0;
        if (playing != null) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.getSoundManager() != null) {
                minecraft.getSoundManager().stop(playing);
            }
            playing = null;
        }
    }

    /** 每个客户端 tick 调用：把到点的段播出去。 */
    public static synchronized void tick() {
        if (order == null) {
            return;
        }
        long now = System.nanoTime();
        while (cursor < order.length && now >= due[cursor]) {
            if (now - due[cursor] > LATE_SKIP_NANOS) {
                cursor++;
                continue;
            }
            play(order[cursor]);
            cursor++;
        }
        if (cursor >= order.length) {
            order = null;
            due = null;
            cursor = 0;
        }
    }

    private static void play(int index) {
        if (index < 0 || index >= ModSounds.COUNTDOWN_SEGMENTS.size()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getSoundManager() == null) {
            return;
        }
        SoundInstance instance = new SimpleSoundInstance(
                ModSounds.COUNTDOWN_SEGMENTS.get(index).get().getLocation(), SoundSource.BLOCKS,
                volume, 1.0F, RandomSource.create(), false, 0,
                SoundInstance.Attenuation.NONE, 0.0D, 0.0D, 0.0D, true);
        minecraft.getSoundManager().play(instance);
        playing = instance;
    }
}
