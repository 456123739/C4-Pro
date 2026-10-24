package com.qisumei.c4;

/**
 * 所有 C4 玩法相关的数值都集中在这里。
 * <p>
 * 这个类故意不引用任何 Minecraft 类型，方便在没有游戏环境的单元测试里直接验证
 * （引信时间、滴滴声节奏、音效距离衰减、拆除时的推动容差等）。
 */
public final class C4Constants {

    private C4Constants() {
    }

    /** 安装需要按住右键的时长（tick）。 */
    public static final int PLANT_TICKS = 70;

    /** 引信总时长（tick）：40 秒。 */
    public static final int FUSE_TICKS = 20 * 40;

    /** 剪刀拆除耗时（tick）：5 秒。 */
    public static final int DEFUSE_SHEARS_TICKS = 20 * 5;

    /** 空手拆除耗时（tick）：10 秒。 */
    public static final int DEFUSE_HAND_TICKS = 20 * 10;

    /** 拆除时允许玩家离开 C4 的最大距离（格）。 */
    public static final double DEFUSE_MAX_RANGE = 4.0;

    /**
     * 拆除过程中允许的“被推动”距离（格）。
     * <p>
     * 旧版本只要方块坐标发生变化就会中断拆除，所以队友／怪物轻轻一撞就会失败。
     * 现在只有在起点半径 {@code PUSH_TOLERANCE} 之外才判定为中断，
     * 也就是说被推动 1 格以内（含实体碰撞造成的位移）都不会中断。
     */
    public static final double PUSH_TOLERANCE = 1.0;

    /** 拆除时距离判定的额外余量，避免边界抖动导致误判。 */
    public static final double RANGE_SLACK = 0.75;

    /** 滴滴声的最短间隔（tick）：0.2 秒。 */
    public static final int BEEP_MIN_INTERVAL_TICKS = 4;

    /** 滴滴声的最长间隔（tick）：1 秒。 */
    public static final int BEEP_MAX_INTERVAL_TICKS = 20;

    /** 安装完成后第一次滴滴声的延迟（tick），给安装音效留出空间。 */
    public static final int BEEP_FIRST_DELAY_TICKS = 20;

    /** 所有 C4 音效都按“玩家自身位置”播放，因此需要一个音量下限，保证远处也能听见。 */
    public static final float CUE_MIN_VOLUME = 0.65f;

    /** 音量随距离衰减到下限的距离参考值（格）。 */
    public static final double CUE_FALLOFF_DISTANCE = 160.0;

    /**
     * 计算下一次滴滴声的间隔。
     * <p>
     * 间隔随剩余时间线性缩短：40 秒时约 1 秒一次，最后 4 秒时约 0.2 秒一次，
     * 与原始 {@code c4countdown.ogg} 里逐渐加快的滴答节奏一致。
     *
     * @param remainingTicks 剩余引信 tick，允许传入任意值
     * @return 间隔 tick，始终位于 [{@link #BEEP_MIN_INTERVAL_TICKS}, {@link #BEEP_MAX_INTERVAL_TICKS}]
     */
    public static int beepIntervalTicks(int remainingTicks) {
        int clamped = Math.max(0, Math.min(FUSE_TICKS, remainingTicks));
        int interval = Math.round(clamped / 40.0f);
        return Math.max(BEEP_MIN_INTERVAL_TICKS, Math.min(BEEP_MAX_INTERVAL_TICKS, interval));
    }

    /**
     * 全局提示音的音量：近处 1.0，远处逐渐降到 {@link #CUE_MIN_VOLUME}，永远不会变成 0。
     * <p>
     * 这样无论玩家在 map 的哪个角落（警或匪）都能听见滴滴声。
     *
     * @param distance 玩家与 C4 的距离（格）
     * @return 播放音量
     */
    public static float cueVolume(double distance) {
        if (distance <= 0) {
            return 1.0f;
        }
        float volume = (float) (1.0 - distance / CUE_FALLOFF_DISTANCE);
        return Math.max(CUE_MIN_VOLUME, Math.min(1.0f, volume));
    }

    /**
     * 拆除总时长：剪刀 5 秒，空手 10 秒。
     *
     * @param hasShears 是否手持剪刀
     */
    public static int defuseDurationTicks(boolean hasShears) {
        return hasShears ? DEFUSE_SHEARS_TICKS : DEFUSE_HAND_TICKS;
    }

    /**
     * 判断玩家相对拆除起点是否还在容差范围内。
     *
     * @param dx 相对起点的 X 偏移
     * @param dy 相对起点的 Y 偏移
     * @param dz 相对起点的 Z 偏移
     */
    public static boolean withinPushTolerance(double dx, double dy, double dz) {
        // 水平方向严格一些（防止跑开），竖直方向放宽（台阶、被顶起、摔落抖动）
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return horizontal <= PUSH_TOLERANCE && Math.abs(dy) <= PUSH_TOLERANCE + 0.5;
    }
}
