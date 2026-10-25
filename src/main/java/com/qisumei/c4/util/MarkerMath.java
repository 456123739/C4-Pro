package com.qisumei.c4.util;

import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * 掉落 C4 悬浮标记用的纯数学，抽出来是为了能在自检里直接验证（不依赖客户端渲染）。
 */
public final class MarkerMath {

    /** 距离越远标记越大，保持"屏幕上的大小"大致恒定，但有上下限，避免近处糊脸、远处看不见。 */
    public static final double MIN_SCALE = 0.55;
    public static final double MAX_SCALE = 2.30;
    public static final double SCALE_DISTANCE = 14.0;

    /** 被方块挡住时（隔墙）整体透明度，压暗一点，看起来像"透视提示"而不是贴脸。 */
    public static final float OCCLUDED_ALPHA = 0.62f;

    /** 距离到量程的这个比例之后开始淡出。 */
    public static final double FADE_START_RATIO = 0.72;

    private MarkerMath() {
    }

    public static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    /** 标记的放大倍数（1.0 = 基准大小）。 */
    public static double scaleFor(double distance) {
        return clamp(distance / SCALE_DISTANCE, MIN_SCALE, MAX_SCALE);
    }

    /** 距离淡出系数 0~1；超出量程为 0。 */
    public static float fadeFor(double distance, double range) {
        if (range <= 0.0) {
            return 0.0f;
        }
        double start = range * FADE_START_RATIO;
        if (distance <= start) {
            return 1.0f;
        }
        if (distance >= range) {
            return 0.0f;
        }
        return (float) ((range - distance) / (range - start));
    }

    /** 上下浮动（格），让标记看起来是"飘"的而不是钉死的。 */
    public static double bob(double timeSeconds) {
        return Math.sin(timeSeconds * 1.9) * 0.055;
    }

    /** 光晕呼吸 0.5~1.0。 */
    public static float pulse(double timeSeconds) {
        return (float) (0.75 + 0.25 * Math.sin(timeSeconds * 3.2));
    }

    /** 箭头方向（屏幕空间角度，度，+x 向右、+y 向上）。 */
    public static double directionDegrees(double dx, double dy) {
        return Math.toDegrees(Math.atan2(dy, dx));
    }

    /**
     * 把"相机空间里的相对坐标"（DX/DY/DZ 为相对观察者的位移）投影到屏幕，
     * 判断目标是否在视野内，并给出"应该往屏幕哪个方向找"的角度。
     * 角度约定：0° = 右，90° = 上，180°/-180° = 左，-90° = 下。
     */
    public static ScreenDirection projectToScreen(double dx, double dy, double dz,
                                                  Matrix4f view, Matrix4f projection) {
        Vector4f clip = new Vector4f((float) dx, (float) dy, (float) dz, 1.0f);
        clip.mul(view);
        clip.mul(projection);

        boolean behind = clip.w <= 0.0f;
        float w = Math.abs(clip.w) < 1.0E-4f ? 1.0E-4f : Math.abs(clip.w);
        float nx = clip.x / w;
        float ny = clip.y / w;
        if (behind) {
            // 在身后时投影会左右/上下翻转，取反才是"该往哪边转"的方向
            nx = -nx;
            ny = -ny;
        }
        boolean onScreen = !behind && Math.abs(nx) <= 1.0f && Math.abs(ny) <= 1.0f;
        return new ScreenDirection(onScreen, directionDegrees(nx, ny));
    }

    /** 屏幕方向结果。 */
    public static final class ScreenDirection {
        public final boolean onScreen;
        public final double degrees;

        public ScreenDirection(boolean onScreen, double degrees) {
            this.onScreen = onScreen;
            this.degrees = degrees;
        }
    }
}
