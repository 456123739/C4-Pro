package com.qisumei.c4.sound;

/**
 * {@code c4countdown.ogg} 的分段表（由 {@code tools/gen_countdown_segments.py} 生成，不要手改）。
 * <p>
 * 每段的起点/长度都是相对整段音轨的真实毫秒数；切点落在静音处，
 * 所以客户端从任意一段开始播放都不会有接缝声。
 */
public final class CountdownSegments {

    private CountdownSegments() {
    }

    public static final int COUNT = 39;

    /** 每段的资源名（{@code qis4c4:cd_NN}）。 */
    public static final String[] NAMES = {
            "cd_00", "cd_01", "cd_02", "cd_03", "cd_04", "cd_05", "cd_06", "cd_07", "cd_08", "cd_09", "cd_10", "cd_11", "cd_12", "cd_13", "cd_14", "cd_15", "cd_16", "cd_17", "cd_18", "cd_19", "cd_20", "cd_21", "cd_22", "cd_23", "cd_24", "cd_25", "cd_26", "cd_27", "cd_28", "cd_29", "cd_30", "cd_31", "cd_32", "cd_33", "cd_34", "cd_35", "cd_36", "cd_37", "cd_38"
    };

    /** 每段在整段音轨里的起始毫秒。 */
    public static final int[] START_MS = {
            0, 1000, 2000, 3000, 4000, 5140, 6010, 6850, 8000, 9000, 10070, 10830, 12000, 12990, 14000, 15000, 16000, 16900, 18090, 19220, 19780, 20840, 21860, 22830, 24200, 25070, 25900, 27070, 28160, 28850, 30150, 31030, 32130, 32900, 34050, 35080, 36010, 36980, 38040
    };

    /** 每段时长（毫秒）。 */
    public static final int[] LENGTH_MS = {
            1000, 1000, 1000, 1000, 1140, 870, 840, 1150, 1000, 1070, 760, 1170, 990, 1010, 1000, 1000, 900, 1190, 1130, 560, 1060, 1020, 970, 1370, 870, 830, 1170, 1090, 690, 1300, 880, 1100, 770, 1150, 1030, 930, 970, 1060, 1945
    };

    /** 整段音轨总长（毫秒）。 */
    public static final int TOTAL_MS = 39985;

    /** 给定“已经过去多少毫秒”，返回应当从哪一段开始播放。 */
    public static int segmentIndexFor(int elapsedMs) {
        int clamped = Math.max(0, Math.min(TOTAL_MS, elapsedMs));
        for (int i = START_MS.length - 1; i >= 0; i--) {
            if (clamped >= START_MS[i]) {
                return i;
            }
        }
        return 0;
    }
}
