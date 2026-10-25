package com.qisumei.c4.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 掉落 C4 标记的事件钩子：世界内的悬浮标记 + 屏幕边缘的方向指示。
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = "qis4c4")
public class ClientMarkerEvents {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        DroppedC4MarkerRenderer.onRenderLevel(event);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        DroppedC4Hud.render(event.getGuiGraphics());
    }
}
