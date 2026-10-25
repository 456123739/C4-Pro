package com.qisumei.c4.handler;

import com.qisumei.c4.entity.C4Entity;
import com.qisumei.c4.entity.C4EntityTracker;
import com.qisumei.c4.qis4c4;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * C4 的“兜底 tick 推进器”。
 * <p>
 * 为什么需要它：C4 的引信不能依赖区块是否处于 entity ticking 状态。
 * 实测（真实专用服务器）：给区块加 FORCED 票据后，区块确实保持加载，
 * 但 {@code DistanceManager#inEntityTickingRange} 的传播并不保证及时 ——
 * 没人在附近时实体可能几十秒都不被 tick，引信和滴滴声就会“停摆”。
 * <p>
 * 所以这里在每个服务端 tick 结束时遍历所有未被卸载的 C4，
 * 如果它这一游戏刻还没有被区块 tick 到（{@link C4Entity#tickLogicIfNeeded()}），就补一次逻辑。
 * 实体正常 tick 时会记录 {@code level.getGameTime()}，因此不会重复推进。
 */
@Mod.EventBusSubscriber(modid = qis4c4.MODID)
public final class C4Manager {

    private C4Manager() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        for (C4Entity c4 : C4EntityTracker.getAllC4()) {
            c4.tickLogicIfNeeded();
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // 开发环境里同一个 JVM 可能反复启停服务器，避免旧实体残留
        C4EntityTracker.clear();
    }
}
