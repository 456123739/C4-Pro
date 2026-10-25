package com.qisumei.c4;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.Arrays;
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
            if (allowedInstallTeams == null) {
                allowedInstallTeams = Arrays.asList();
            }
            if (allowedDefuseTeams == null) {
                allowedDefuseTeams = Arrays.asList();
            }
        }
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