package com.qisumei.c4.command;

import com.mojang.brigadier.CommandDispatcher;
import com.qisumei.c4.Config;
import com.qisumei.c4.qis4c4;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Scoreboard;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 只读诊断指令：把 config 里两份队伍名单和指令执行者自己的队伍打出来。
 * <p>
 * 存在的意义是排错——如果“匪队也能拆包”，通常不是代码把两份名单用反了，
 * 而是服务端 config 里 {@code allowed_defuse_teams} 留空（留空 = 所有人都可以，原始模组就是这样），
 * 或者填的名字和 {@code /team add} 的队伍名不一致。这个指令能一眼看出来。
 */
public final class C4RolesCommand {

    private C4RolesCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("c4roles")
                .requires(source -> source.hasPermission(0))
                .executes(context -> {
                    CommandSourceStack source = context.getSource();
                    ServerPlayer player = source.getPlayer();
                    String playerTeam = Config.teamNameOf(player);
                    var scoreboard = source.getServer().getScoreboard();

                    source.sendSuccess(() -> Component.literal(
                            "§6[C4] §f安装队（匪）: §e" + describe(Config.allowedInstallTeams, scoreboard)), false);
                    source.sendSuccess(() -> Component.literal(
                            "§6[C4] §f拆除队（警）: §e" + describe(Config.allowedDefuseTeams, scoreboard)), false);
                    source.sendSuccess(() -> Component.literal(
                            "§6[C4] §f你的队伍: §e" + (player == null ? "控制台" 
                                    : playerTeam.isEmpty() ? "无队伍" : playerTeam)), false);

                    boolean canPlant = isAllowed(Config.allowedInstallTeams, playerTeam);
                    boolean canDefuse = isAllowed(Config.allowedDefuseTeams, playerTeam);
                    source.sendSuccess(() -> Component.literal(
                            "§6[C4] §f你的权限: 安装 " + (canPlant ? "§a✔" : "§c✘")
                                    + " §7· §f拆除 " + (canDefuse ? "§a✔" : "§c✘")), false);

                    qis4c4.LOGGER.info("[C4] /c4roles by {} (team={}): install={} defuse={}",
                            source.getTextName(), playerTeam.isEmpty() ? "<none>" : playerTeam,
                            Config.allowedInstallTeams, Config.allowedDefuseTeams);
                    return 1;
                }));
    }

    private static String describe(@Nullable List<? extends String> teams, Scoreboard scoreboard) {
        if (teams == null || teams.isEmpty()) {
            return "未设置（所有人都可以）";
        }
        List<String> parts = new ArrayList<>(teams.size());
        for (String team : teams) {
            if (team.isEmpty()) {
                parts.add("\"\"（无队伍玩家）");
                continue;
            }
            boolean exists = scoreboard.getPlayerTeam(team) != null;
            parts.add("\"" + team + "\"" + (exists ? " §a✔" : " §c✘不存在"));
        }
        return String.join("§7, §e", parts);
    }

    private static boolean isAllowed(@Nullable List<? extends String> teams, String playerTeam) {
        if (teams == null || teams.isEmpty()) {
            return true;
        }
        return teams.contains(playerTeam);
    }


}
