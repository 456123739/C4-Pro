package com.qisumei.c4;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Mod.EventBusSubscriber(modid = qis4c4.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue ENABLE_EXTRA =
            BUILDER
                    .comment("是否开启示例物品的额外效果")
                    .define("enableExtraEffect", true);

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ALLOWED_BLOCKS = BUILDER
            .comment("允许放置C4的方块ID列表（支持mod:block格式）",
                    "默认: minecraft:end_stone_bricks")
            .defineList("allowed_blocks",
                    Arrays.asList("minecraft:end_stone_bricks"),
                    obj -> obj instanceof String);

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ALLOWED_INSTALL_TEAMS = BUILDER
            .comment("允许安装C4的队伍名称列表（空列表表示任何队伍都可以）",
                    "例如: [\"red\", \"blue\"]")
            .defineList("allowed_install_teams",
                    Arrays.asList(),
                    obj -> obj instanceof String);

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ALLOWED_DEFUSE_TEAMS = BUILDER
            .comment("允许拆除C4的队伍名称列表（空列表表示任何队伍都可以）",
                    "例如: [\"red\", \"blue\"]")
            .defineList("allowed_defuse_teams",
                    Arrays.asList(),
                    obj -> obj instanceof String);

    public static final ForgeConfigSpec.BooleanValue ENABLE_C4_DROP =
            BUILDER
                    .comment("是否启用C4掉落功能（需要保留物品栏游戏规则开启）")
                    .define("enable_c4_drop", true);

    public static final ForgeConfigSpec.BooleanValue DROPPED_C4_MARKER = BUILDER
            .comment("掉在地上的C4是否显示悬浮标记（隔墙可见，纯客户端渲染）")
            .define("dropped_c4_marker", true);

    public static final ForgeConfigSpec.DoubleValue DROPPED_C4_MARKER_RANGE = BUILDER
            .comment("悬浮标记的可见距离（格）")
            .defineInRange("dropped_c4_marker_range", 64.0, 16.0, 512.0);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    public static boolean enableExtraEffect;
    public static List<? extends String> allowedBlocks = Arrays.asList("minecraft:end_stone_bricks");
    public static List<? extends String> allowedInstallTeams = Arrays.asList();
    public static List<? extends String> allowedDefuseTeams = Arrays.asList();
    public static boolean enableC4Drop = true;
    public static boolean droppedC4Marker = true;
    public static double droppedC4MarkerRange = 64.0;

    @SubscribeEvent
    static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            enableExtraEffect = ENABLE_EXTRA.get();
            allowedBlocks = ALLOWED_BLOCKS.get();
            allowedInstallTeams = ALLOWED_INSTALL_TEAMS.get();
            allowedDefuseTeams = ALLOWED_DEFUSE_TEAMS.get();
            enableC4Drop = ENABLE_C4_DROP.get();
            droppedC4Marker = DROPPED_C4_MARKER.get();
            droppedC4MarkerRange = DROPPED_C4_MARKER_RANGE.get();

            if (allowedBlocks == null || allowedBlocks.isEmpty()) {
                allowedBlocks = Arrays.asList("minecraft:end_stone_bricks");
            }
            // 队伍名单做一次规范化：去掉首尾空格（TOML 里写成 [" red ", "blue"] 很常见）、
            // 去掉重复项；显式写下的空串 "" 保留，语义是“允许没有队伍的玩家”（和原始模组一致）。
            allowedInstallTeams = normalizeTeamList("allowed_install_teams", ALLOWED_INSTALL_TEAMS.get());
            allowedDefuseTeams = normalizeTeamList("allowed_defuse_teams", ALLOWED_DEFUSE_TEAMS.get());

            // 把真正生效的队伍名单打进日志：配置写错、留空、或填了显示名而不是 /team add 的名字时，
            // 这里是第一现场（留空 = 所有人都可以，和原始模组一致）。
            qis4c4.LOGGER.info("[C4] 队伍配置 — 安装队(匪): {} · 拆除队(警): {}",
                    describeTeams(allowedInstallTeams), describeTeams(allowedDefuseTeams));
        }
    }


    /**
     * 规范化队伍名单：trim + 去重，保留显式的空串（= 允许无队伍玩家）。
     * <p>
     * Forge 的 defineList 只会原样读出 TOML 数组里的字符串，不会帮你去空格；
     * 而 {@code player.getTeam().getName()} 是精确比较的 —— 少一个空格就永远匹配不上。
     */
    public static List<? extends String> normalizeTeamList(String key, List<? extends String> raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(raw.size());
        for (Object entryRaw : raw) {
            if (!(entryRaw instanceof String entry)) {
                qis4c4.LOGGER.warn("[C4] 配置 {} 里有非字符串元素，已忽略: {}", key, entryRaw);
                continue;
            }
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                // 显式空串：允许“没有队伍”的玩家，语义与原始模组一致
                if (!out.contains("")) {
                    out.add("");
                }
                continue;
            }
            if (!out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        if (out.isEmpty() && raw != null && !raw.isEmpty()) {
            qis4c4.LOGGER.warn("[C4] 配置 {} 里没有任何有效队伍名，按“未设置 = 所有人都可以”处理", key);
        }
        return Collections.unmodifiableList(out);
    }

    private static String describeTeams(List<? extends String> teams) {
        if (teams == null || teams.isEmpty()) {
            return "未设置（所有人都可以）";
        }
        List<String> quoted = new ArrayList<>(teams.size());
        for (String team : teams) {
            quoted.add(team.isEmpty() ? "\"\"（无队伍玩家）" : "\"" + team + "\"");
        }
        return String.join(", ", quoted);
    }

    /** 该玩家所在队伍名；没有队伍（或执行者是控制台）返回空串。 */
    public static String teamNameOf(@javax.annotation.Nullable net.minecraft.world.entity.player.Player player) {
        if (player == null) {
            return "";
        }
        var team = player.getTeam();
        return team == null ? "" : team.getName();
    }

    public static boolean isBlockAllowed(String blockId) {
        return allowedBlocks != null && allowedBlocks.contains(blockId);
    }

    public static boolean isInstallTeamAllowed(String teamName) {
        if (allowedInstallTeams == null || allowedInstallTeams.isEmpty()) {
            return true;
        }
        if (teamName.isEmpty()) {
            return allowedInstallTeams.contains("");
        }
        return allowedInstallTeams.contains(teamName);
    }

    public static boolean isDefuseTeamAllowed(String teamName) {
        if (allowedDefuseTeams == null || allowedDefuseTeams.isEmpty()) {
            return true;
        }
        if (teamName.isEmpty()) {
            return allowedDefuseTeams.contains("");
        }
        return allowedDefuseTeams.contains(teamName);
    }
}