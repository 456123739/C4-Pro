package com.qisumei.c4;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nonnull;

@Mod.EventBusSubscriber(modid = qis4c4.MODID)
public class ModInitializer {

    private static final String SCOREBOARD_NAME = "c4_condition";
    private static final String SCOREBOARD_P1 = "p1score";
    private static final String SCOREBOARD_P2 = "p2score";
    private static final String SCOREBOARD_P3 = "p3score";
    private static final String FAKE_PLAYER = "#C4";

    // 记分板状态常量
    public static final int STATE_IDLE = 0;
    public static final int STATE_PLACED = 1;
    public static final int STATE_EXPLODED = 2;
    public static final int STATE_DEFUSED = 3;

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        initScoreboard(server);
        server.sendSystemMessage(Component.literal("§a[C4] 记分板已初始化"));
    }

    private static void initScoreboard(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();

        // 主状态计分板
        Objective objective = scoreboard.getObjective(SCOREBOARD_NAME);
        if (objective == null) {
            objective = scoreboard.addObjective(
                    SCOREBOARD_NAME,
                    ObjectiveCriteria.DUMMY,
                    Component.literal("C4状态"),
                    ObjectiveCriteria.RenderType.INTEGER
            );
        }

        // X坐标计分板
        Objective p1 = scoreboard.getObjective(SCOREBOARD_P1);
        if (p1 == null) {
            p1 = scoreboard.addObjective(
                    SCOREBOARD_P1,
                    ObjectiveCriteria.DUMMY,
                    Component.literal("C4位置X"),
                    ObjectiveCriteria.RenderType.INTEGER
            );
        }

        // Y坐标计分板
        Objective p2 = scoreboard.getObjective(SCOREBOARD_P2);
        if (p2 == null) {
            p2 = scoreboard.addObjective(
                    SCOREBOARD_P2,
                    ObjectiveCriteria.DUMMY,
                    Component.literal("C4位置Y"),
                    ObjectiveCriteria.RenderType.INTEGER
            );
        }

        // Z坐标计分板
        Objective p3 = scoreboard.getObjective(SCOREBOARD_P3);
        if (p3 == null) {
            p3 = scoreboard.addObjective(
                    SCOREBOARD_P3,
                    ObjectiveCriteria.DUMMY,
                    Component.literal("C4位置Z"),
                    ObjectiveCriteria.RenderType.INTEGER
            );
        }

        // 初始化分数为0
        if (scoreboard.getOrCreatePlayerScore(FAKE_PLAYER, objective).getScore() == 0) {
            scoreboard.getOrCreatePlayerScore(FAKE_PLAYER, objective).setScore(STATE_IDLE);
        }
        if (scoreboard.getOrCreatePlayerScore(FAKE_PLAYER, p1).getScore() == 0) {
            scoreboard.getOrCreatePlayerScore(FAKE_PLAYER, p1).setScore(0);
        }
        if (scoreboard.getOrCreatePlayerScore(FAKE_PLAYER, p2).getScore() == 0) {
            scoreboard.getOrCreatePlayerScore(FAKE_PLAYER, p2).setScore(0);
        }
        if (scoreboard.getOrCreatePlayerScore(FAKE_PLAYER, p3).getScore() == 0) {
            scoreboard.getOrCreatePlayerScore(FAKE_PLAYER, p3).setScore(0);
        }
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new SimplePreparableReloadListener<Void>() {
            @Nonnull
            @Override
            protected Void prepare(@Nonnull ResourceManager resourceManager, @Nonnull ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(@Nonnull Void object, @Nonnull ResourceManager resourceManager, @Nonnull ProfilerFiller profiler) {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    broadcastReloadMessage(server);
                }
            }
        });
    }

    private static void broadcastReloadMessage(MinecraftServer server) {
        Component reloadMessage = Component.literal(
                "§a[系统] §eC4模组 §a已重载！\n" +
                        "§7- 长按右键安装C4炸药\n" +
                        "§7- 安装后40秒自动引爆\n" +
                        "§7- 使用剪刀可拆除C4\n" +
                        "§7- C4状态存于虚拟玩家#C4的c4_condition记分板下\n" +
                        "§7- 坐标存储于p1score/p2score/p3score\n" +
                        "§7- 1=已安装；2=已爆炸；3=已拆除"
        );

        server.getPlayerList().getPlayers().forEach(player -> {
            player.sendSystemMessage(reloadMessage);
        });
        server.sendSystemMessage(Component.literal("§a[C4] 模组已重载"));
    }

    public static void updateC4State(MinecraftServer server, int state) {
        if (server == null) {
            server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;
        }

        Scoreboard scoreboard = server.getScoreboard();
        Objective objective = scoreboard.getObjective(SCOREBOARD_NAME);
        if (objective != null) {
            scoreboard.getOrCreatePlayerScore(FAKE_PLAYER, objective).setScore(state);
        }
    }

    public static void updateC4Position(MinecraftServer server, int x, int y, int z) {
        if (server == null) {
            server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;
        }

        Scoreboard scoreboard = server.getScoreboard();
        Objective p1 = scoreboard.getObjective(SCOREBOARD_P1);
        Objective p2 = scoreboard.getObjective(SCOREBOARD_P2);
        Objective p3 = scoreboard.getObjective(SCOREBOARD_P3);

        if (p1 != null) scoreboard.getOrCreatePlayerScore(FAKE_PLAYER, p1).setScore(x);
        if (p2 != null) scoreboard.getOrCreatePlayerScore(FAKE_PLAYER, p2).setScore(y);
        if (p3 != null) scoreboard.getOrCreatePlayerScore(FAKE_PLAYER, p3).setScore(z);
    }

    public static void clearC4Position(MinecraftServer server) {
        updateC4Position(server, 0, 0, 0);
    }

    public static int getCurrentC4State(MinecraftServer server) {
        if (server == null) {
            server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return STATE_IDLE;
        }

        Scoreboard scoreboard = server.getScoreboard();
        Objective objective = scoreboard.getObjective(SCOREBOARD_NAME);
        if (objective != null) {
            return scoreboard.getOrCreatePlayerScore(FAKE_PLAYER, objective).getScore();
        }
        return STATE_IDLE;
    }
}