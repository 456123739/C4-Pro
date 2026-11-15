package com.qisumei.c4.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.qisumei.c4.ModInitializer;
import com.qisumei.c4.entity.C4Entity;
import com.qisumei.c4.entity.C4EntityTracker;
import com.qisumei.c4.entity.C4SavedData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /c4clear}：一口气清掉所有维度里的 C4。
 *
 * <h2>为什么不扫区块</h2>
 * 区块“被加载”不等于实体“已实例化”：一个没加载的区块里可能躺着一个 C4 实体，
 * 只有玩家走近（区块进入 entity ticking）它才会真正进入世界。想在指令里把它找出来，
 * 唯一办法是把区块全部加载一遍 —— 那才是真正的浪费：地图越大越慢，还会顺手把区块写脏。
 * <p>
 * 所以这里换了个方向：<b>让 C4 自己来报到</b>。
 * <ol>
 *     <li>已经加载的 C4 —— 直接 {@link C4Entity#forceRemove(String)}；</li>
 *     <li>只有坐标存档、实体不在场上的 —— 写进 {@link C4SavedData} 的“死亡名单”；</li>
 *     <li>那个 C4 哪天被区块加载出来，实体在 {@code onAddedToWorld}/第一次 tick 时查一次名单，
 *     命中就当场自删，一 tick 都不给它跑。</li>
 * </ol>
 * 整个过程只有哈希查表，代价与地图大小无关，也不会加载任何区块。
 */
public class C4ClearCommand {

    /** 一次清场的结果。 */
    public static final class Result {
        /** 当场删掉的实体数量。 */
        public final int removed;
        /** 登记进死亡名单、等区块加载时自动消失的数量。 */
        public final int tombstoned;

        Result(int removed, int tombstoned) {
            this.removed = removed;
            this.tombstoned = tombstoned;
        }

        public int total() {
            return this.removed + this.tombstoned;
        }
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("c4clear")
                .requires(source -> source.hasPermission(2))
                .executes(C4ClearCommand::executeClear));
    }

    private static int executeClear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Result result = clearAll(source.getServer());

        if (result.total() == 0) {
            source.sendSystemMessage(Component.literal("§e 没有找到任何C4（记分板状态已重置）。"));
        } else if (result.tombstoned == 0) {
            source.sendSystemMessage(Component.literal("§a 已清除 §e" + result.removed + " §a个C4！"));
        } else if (result.removed == 0) {
            source.sendSystemMessage(Component.literal("§a 已清除 §e" + result.tombstoned
                    + " §a个C4！§7（它们藏在未加载的区块里，区块一加载就会立刻销毁，不会再倒计时）"));
        } else {
            source.sendSystemMessage(Component.literal("§a 已清除 §e" + result.removed + " §a个C4！§7另有 §e"
                    + result.tombstoned + " §7个藏在未加载的区块里，区块一加载就会立刻销毁。"));
        }
        return result.total();
    }

    /**
     * 清除所有维度里的 C4。不加载任何区块，不注册任何每 tick 任务。
     */
    public static Result clearAll(MinecraftServer server) {
        if (server == null) {
            return new Result(0, 0);
        }
        int removed = 0;
        int tombstoned = 0;

        for (ServerLevel level : server.getAllLevels()) {
            // 1) 静态 tracker（正常情况下已加载的实体都在这儿）
            for (C4Entity c4 : new ArrayList<>(C4EntityTracker.getAllC4())) {
                if (!c4.isRemoved() && c4.level() == level) {
                    c4.forceRemove("c4clear");
                    removed++;
                }
            }

            // 2) 实体管理器里的全部已加载实体（补 tracker 可能漏掉的）
            List<Entity> loaded = new ArrayList<>();
            level.getAllEntities().forEach(loaded::add);
            for (Entity entity : loaded) {
                if (entity instanceof C4Entity c4 && !c4.isRemoved()) {
                    c4.forceRemove("c4clear");
                    removed++;
                }
            }

            // 3) 剩下的坐标 = 实体不在场上（区块没加载）。不加载区块，登记死亡名单即可。
            C4SavedData data = C4SavedData.get(level);
            for (BlockPos pos : new ArrayList<>(data.getPositions())) {
                data.tombstone(pos);
                tombstoned++;
            }
            data.clear();
        }

        // 4) 记分板兜底：不管有没有找到实体，都不要再留着“已安装”的状态
        if (ModInitializer.getCurrentC4State(server) != ModInitializer.STATE_IDLE) {
            ModInitializer.updateC4State(server, ModInitializer.STATE_IDLE);
        }
        ModInitializer.clearC4Position(server);
        return new Result(removed, tombstoned);
    }
}
