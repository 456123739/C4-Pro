package com.qisumei.c4;

import com.mojang.brigadier.CommandDispatcher;
import com.qisumei.c4.command.C4ClearCommand;
import com.qisumei.c4.command.C4RolesCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class CommandHandler {

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        C4ClearCommand.register(dispatcher);
        C4RolesCommand.register(dispatcher);
    }
}