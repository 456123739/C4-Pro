package com.qisumei.c4.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.platform.GlStateManager;
import com.qisumei.c4.Config;
import com.qisumei.c4.util.DroppedC4Locator;
import com.qisumei.c4.util.MarkerMath;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 地上掉了 C4 时，在它头顶画一个悬浮标记（隔墙可见）。
 * <p>
 * 为什么放在客户端：物品实体本身是同步给所有客户端的，不需要额外的网络包，
 * 也就不会给服务端增加任何负担；C4 被捡起/被清掉时实体消失，标记自然跟着消失。
 * <p>
 * 视觉：一根很淡的竖直光柱从物品升起 → 顶端是一枚"别针"样式的 C4 图标（会轻微上下浮动、呼吸），
 * 下方一行小字给出距离。全部关闭深度测试，所以在墙后也能看到；
 * 被方块挡住时整体压暗到 {@link MarkerMath#OCCLUDED_ALPHA}，看起来像"透视提示"而不是糊在屏幕上。
 */
public final class DroppedC4MarkerRenderer {

    private static final ResourceLocation TEX_ICON = new ResourceLocation("qis4c4", "textures/gui/c4_marker.png");
    private static final ResourceLocation TEX_BEAM = new ResourceLocation("qis4c4", "textures/gui/c4_beam.png");

    /** 图标在 1.0 倍时的边长（格）。 */
    private static final float ICON_BASE_SIZE = 1.25f;
    /** 图标悬在物品上方多高（格）。 */
    private static final double ICON_HEIGHT = 1.35;
    /** 贴图里别针尖端位于图标中心下方多少（128px 图：尖端 y=126，中心 y=64）。 */
    private static final float PIN_TIP_OFFSET = (126.0f - 64.0f) / 128.0f;
    /** 光柱半宽（格）。 */
    private static final float BEAM_HALF_WIDTH = 0.15f;
    /** 超过这个距离就不再做遮挡检测了（基本都在墙后，直接按"隔墙"处理）。 */
    private static final double OCCLUSION_CHECK_DISTANCE = 48.0;
    /** 遮挡检测的缓存时长（毫秒）。射线检测不算便宜，没必要每帧都做。 */
    private static final long OCCLUSION_CACHE_MS = 250L;
    /** 距离小字只在这个距离内显示：再远就算画出来也认不出，反而显得脏。 */
    private static final double LABEL_MAX_DISTANCE = 24.0;

    private static RenderType iconType;
    private static RenderType beamType;

    /** entityId -> {上次检测时间, 是否被挡住} */
    private static final java.util.Map<Integer, long[]> OCCLUSION_CACHE = new java.util.HashMap<>();

    /** 给 HUD 边缘指示器用的一帧结果；没有掉落 C4 时为 null。 */
    private static volatile HudHint hint;

    private DroppedC4MarkerRenderer() {
    }

    public static HudHint getHint() {
        return hint;
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            hint = null;
            return;
        }
        if (!Config.droppedC4Marker) {
            hint = null;
            return;
        }

        double range = Config.droppedC4MarkerRange;
        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        float partial = event.getPartialTick();

        List<ItemEntity> items = DroppedC4Locator.find(mc.level, camPos, range);
        if (items.isEmpty()) {
            hint = null;
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Matrix4f view = poseStack.last().pose();
        Matrix4f projection = event.getProjectionMatrix();

        double time = (mc.level.getGameTime() + partial) / 20.0;
        double bob = MarkerMath.bob(time);
        float pulse = MarkerMath.pulse(time);
        Font font = mc.font;

        HudHint nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (ItemEntity item : items) {
            double px = Mth.lerp(partial, item.xo, item.getX()) - camPos.x;
            double py = Mth.lerp(partial, item.yo, item.getY()) - camPos.y;
            double pz = Mth.lerp(partial, item.zo, item.getZ()) - camPos.z;
            double distance = Math.sqrt(px * px + py * py + pz * pz);

            float fade = MarkerMath.fadeFor(distance, range);
            if (fade <= 0.02f) {
                continue;
            }

            double iconY = py + ICON_HEIGHT + bob;
            boolean occluded = isOccludedCached(mc, item, camera.getPosition(),
                    new Vec3(camPos.x + px, camPos.y + iconY, camPos.z + pz), distance);
            float alpha = fade * (occluded ? MarkerMath.OCCLUDED_ALPHA : 1.0f);

            float scale = (float) MarkerMath.scaleFor(distance);
            float size = ICON_BASE_SIZE * scale;

            drawBeam(buffers, poseStack, px, py, pz, iconY, size, alpha * pulse);
            drawIcon(buffers, poseStack, camera, px, iconY, pz, size, alpha, pulse);
            if (distance <= LABEL_MAX_DISTANCE) {
                drawLabel(font, buffers, poseStack, camera, px, iconY, size, pz, distance, alpha);
            }

            if (distance < nearestDist) {
                nearestDist = distance;
                nearest = new HudHint(distance, alpha, px, iconY, pz, view, projection);
            }
        }

        buffers.endBatch();
        hint = nearest;
    }

    // ------------------------------------------------------------------ 光柱

    private static void drawBeam(MultiBufferSource.BufferSource buffers, PoseStack poseStack,
                                 double x, double y, double z, double iconY, float size, float alpha) {
        float bottom = (float) (y + 0.05);
        float top = (float) (iconY - size * PIN_TIP_OFFSET - 0.02);
        if (top <= bottom) {
            return;
        }
        Matrix4f matrix = poseStack.last().pose();
        float halfWidth = BEAM_HALF_WIDTH * Math.max(0.8f, Math.min(size / ICON_BASE_SIZE, 1.8f));
        int bottomAlpha = (int) (210 * alpha);
        int topAlpha = 0;

        VertexConsumer vc = buffers.getBuffer(beamType());
        // 两片互相垂直的面，任何角度看起来都是一根光柱
        for (int plane = 0; plane < 2; plane++) {
            float ox = plane == 0 ? halfWidth : 0.0f;
            float oz = plane == 0 ? 0.0f : halfWidth;
            vc.vertex(matrix, (float) x - ox, bottom, (float) z - oz).color(255, 126, 96, bottomAlpha).uv(0.0f, 1.0f).endVertex();
            vc.vertex(matrix, (float) x + ox, bottom, (float) z + oz).color(255, 126, 96, bottomAlpha).uv(1.0f, 1.0f).endVertex();
            vc.vertex(matrix, (float) x + ox, top, (float) z + oz).color(255, 126, 96, topAlpha).uv(1.0f, 0.0f).endVertex();
            vc.vertex(matrix, (float) x - ox, top, (float) z - oz).color(255, 126, 96, topAlpha).uv(0.0f, 0.0f).endVertex();
        }
    }

    // ------------------------------------------------------------------ 图标

    private static void drawIcon(MultiBufferSource.BufferSource buffers, PoseStack poseStack, Camera camera,
                                 double x, double y, double z, float size, float alpha, float pulse) {
        poseStack.pushPose();
        poseStack.translate(x, y, z);
        poseStack.mulPose(camera.rotation());
        Matrix4f matrix = poseStack.last().pose();

        float half = size * 0.5f;
        // 呼吸时整体亮度轻微起伏，而不是改变大小（改变大小会让人误判距离）
        int a = (int) (255 * alpha * (0.85f + 0.15f * pulse));
        VertexConsumer vc = buffers.getBuffer(iconType());
        vc.vertex(matrix, -half, half, 0.0f).color(255, 255, 255, a).uv(0.0f, 0.0f).endVertex();
        vc.vertex(matrix, half, half, 0.0f).color(255, 255, 255, a).uv(1.0f, 0.0f).endVertex();
        vc.vertex(matrix, half, -half, 0.0f).color(255, 255, 255, a).uv(1.0f, 1.0f).endVertex();
        vc.vertex(matrix, -half, -half, 0.0f).color(255, 255, 255, a).uv(0.0f, 1.0f).endVertex();

        poseStack.popPose();
    }

    // ------------------------------------------------------------------ 距离小字

    private static void drawLabel(Font font, MultiBufferSource.BufferSource buffers, PoseStack poseStack,
                                  Camera camera, double x, double y, float size, double z,
                                  double distance, float alpha) {
        poseStack.pushPose();
        // 画在小牌正上方：下方是光柱，字压上去会很脏
        poseStack.translate(x, y + size * 0.5f + 0.10, z);
        poseStack.mulPose(camera.rotation());
        // 让字在 8~24 格之间保持"屏幕尺寸基本不变"（等效于原版表签 8 格时的观感），
        // 不用 ICON 的缩放曲线：那是给贴图用的，字跟太大会盖住准心视野。
        float textScale = 0.022f * (float) Math.max(1.0, Math.min(distance / 8.0, 3.0));
        poseStack.scale(-textScale, -textScale, textScale);
        Matrix4f matrix = poseStack.last().pose();

        Component label = Component.literal("C4")
                .append(Component.literal(" " + Math.round(distance) + "m")
                        .withStyle(net.minecraft.ChatFormatting.GRAY));
        int color = ((int) (235 * alpha) << 24) | 0xFFFFFF;
        float width = font.width(label);
        font.drawInBatch(label, -width / 2.0f, 0.0f, color, false, matrix, buffers,
                Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
        poseStack.popPose();
    }

    // ------------------------------------------------------------------ 工具

    private static boolean isOccludedCached(Minecraft mc, ItemEntity item, Vec3 from, Vec3 to, double distance) {
        if (distance > OCCLUSION_CHECK_DISTANCE) {
            return true;
        }
        long now = System.currentTimeMillis();
        int id = item.getId();
        long[] cached = OCCLUSION_CACHE.get(id);
        if (cached != null && now - cached[0] < OCCLUSION_CACHE_MS) {
            return cached[1] != 0L;
        }
        boolean blocked = isOccluded(mc, from, to);
        if (OCCLUSION_CACHE.size() > 128) {
            OCCLUSION_CACHE.clear();
        }
        OCCLUSION_CACHE.put(id, new long[]{now, blocked ? 1L : 0L});
        return blocked;
    }

    private static boolean isOccluded(Minecraft mc, Vec3 from, Vec3 to) {
        if (mc.level == null || mc.player == null) {
            return false;
        }
        HitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() != HitResult.Type.MISS;
    }

    private static RenderType iconType() {
        if (iconType == null) {
            iconType = buildType("qis4c4_marker_icon", TEX_ICON, false);
        }
        return iconType;
    }

    private static RenderType beamType() {
        if (beamType == null) {
            beamType = buildType("qis4c4_marker_beam", TEX_BEAM, true);
        }
        return beamType;
    }

    /**
     * 常亮、关闭深度测试的贴图四边形渲染类型。
     * 用默认的 position_color_tex 着色器，避免依赖任何自定义 shader 文件。
     */
    private static RenderType buildType(String name, ResourceLocation texture, boolean additive) {
        RenderStateShard.TransparencyStateShard transparency;
        if (additive) {
            transparency = new RenderStateShard.TransparencyStateShard("qis4c4_additive", () -> {
                RenderSystem.enableBlend();
                RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            }, () -> {
                RenderSystem.disableBlend();
                RenderSystem.defaultBlendFunc();
            });
        } else {
            transparency = new RenderStateShard.TransparencyStateShard("qis4c4_translucent", () -> {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
            }, () -> {
                RenderSystem.disableBlend();
                RenderSystem.defaultBlendFunc();
            });
        }

        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorTexShader))
                .setTextureState(new RenderStateShard.TextureStateShard(texture, true, false))
                .setTransparencyState(transparency)
                .setDepthTestState(new RenderStateShard.DepthTestStateShard("always", 519))
                .setCullState(new RenderStateShard.CullStateShard(false))
                .setLightmapState(new RenderStateShard.LightmapStateShard(false))
                .setOverlayState(new RenderStateShard.OverlayStateShard(false))
                .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                .createCompositeState(false);

        return RenderType.create(name, DefaultVertexFormat.POSITION_COLOR_TEX, VertexFormat.Mode.QUADS,
                1024, false, true, state);
    }

    /** 屏幕外的方向提示（HUD 那条小箭头用）。 */
    public static final class HudHint {
        public final double distance;
        public final float alpha;
        public final boolean onScreen;
        public final double directionDegrees;

        HudHint(double distance, float alpha, double dx, double dy, double dz, Matrix4f view, Matrix4f projection) {
            this.distance = distance;
            this.alpha = alpha;
            MarkerMath.ScreenDirection dir = MarkerMath.projectToScreen(dx, dy, dz, view, projection);
            this.onScreen = dir.onScreen;
            this.directionDegrees = dir.degrees;
        }
    }
}
