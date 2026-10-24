package com.qisumei.c4.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.qisumei.c4.entity.C4Entity;
import com.qisumei.c4.entity.C4EntityTracker;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

public class C4ClearCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("c4clear")
                .requires(source -> source.hasPermission(2))
                .executes(C4ClearCommand::executeClear));
    }

    private static int executeClear(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();

        int count = C4EntityTracker.getCount();

        if (count == 0) {
            source.sendSystemMessage(Component.literal("§e 没有找到任何C4实体。"));
            return 0;
        }

        // 清除所有C4
        C4EntityTracker.clearAll();

        // 额外：遍历所有已加载区块，清理可能遗漏的C4
        ServerLevel level = source.getLevel();
        var entities = level.getAllEntities();
        int extraRemoved = 0;
        for (var entity : entities) {
            if (entity instanceof C4Entity) {
                entity.discard();
                extraRemoved++;
            }
        }

        int totalRemoved = count + extraRemoved;
        source.sendSystemMessage(Component.literal("§a 已清除 §e" + totalRemoved + " §a个C4实体！"));

        return totalRemoved;
    }
}