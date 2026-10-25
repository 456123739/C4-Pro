package com.qisumei.c4.handler;

import com.qisumei.c4.Config;
import com.qisumei.c4.network.PacketHandler;
import com.qisumei.c4.network.C4HudPacket;
import com.qisumei.c4.qis4c4;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = qis4c4.MODID)
public class C4DropHandler {

    // ========== 工具方法 ==========

    private static boolean hasC4(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == qis4c4.QISC4_ITEM.get()) {
                return true;
            }
        }
        return false;
    }

    private static int getC4Count(Player player) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == qis4c4.QISC4_ITEM.get()) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void removeAllC4(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == qis4c4.QISC4_ITEM.get()) {
                player.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
    }

    private static String getPlayerTeamName(Player player) {
        net.minecraft.world.scores.Team team = player.getTeam();
        if (team == null) return "";
        return team.getName();
    }

    private static boolean isInstallTeamAllowed(Player player) {
        if (Config.allowedInstallTeams == null || Config.allowedInstallTeams.isEmpty()) return true;
        String teamName = getPlayerTeamName(player);
        if (teamName.isEmpty()) return Config.allowedInstallTeams.contains("");
        return Config.allowedInstallTeams.contains(teamName);
    }

    // ========== 核心逻辑 ==========

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.player.level().isClientSide) return;

        Player player = event.player;

        if (!hasC4(player)) return;
        if (isInstallTeamAllowed(player)) return;

        int c4Count = getC4Count(player);
        Vec3 pos = player.position();

        removeAllC4(player);

        if (c4Count > 0) {
            ItemStack dropStack = new ItemStack(qis4c4.QISC4_ITEM.get(), c4Count);
            ItemEntity dropItem = new ItemEntity(player.level(), pos.x, pos.y, pos.z, dropStack);
            dropItem.setPickUpDelay(60);
            dropItem.setUnlimitedLifetime();

            CompoundTag tag = dropItem.getPersistentData();
            tag.putBoolean("c4_drop", true);

            player.level().addFreshEntity(dropItem);
        }
    }

    @SubscribeEvent
    public static void onItemPickup(PlayerEvent.ItemPickupEvent event) {
        if (event.getEntity().level().isClientSide) return;

        ItemEntity item = event.getOriginalEntity();
        if (item == null) return;

        CompoundTag tag = item.getPersistentData();
        if (!tag.getBoolean("c4_drop")) return;

        Player player = event.getEntity();

        if (isInstallTeamAllowed(player)) {
            if (player instanceof ServerPlayer serverPlayer) {
                PacketHandler.sendToPlayer(serverPlayer, C4HudPacket.selfMessage("§a 你已捡起C4！", 2000));
            }

            Component globalMsg = Component.literal("§e" + player.getName().getString() + " §a捡起了C4！");
            if (player.getServer() != null) {
                for (ServerPlayer p : player.getServer().getPlayerList().getPlayers()) {
                    p.sendSystemMessage(globalMsg);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!Config.enableC4Drop) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;

        ServerLevel serverLevel = (ServerLevel) player.level();

        if (!serverLevel.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return;
        if (!hasC4(player)) return;

        int c4Count = getC4Count(player);
        Vec3 pos = player.position();

        removeAllC4(player);

        ItemStack c4Stack = new ItemStack(qis4c4.QISC4_ITEM.get(), c4Count);
        ItemEntity itemEntity = new ItemEntity(serverLevel, pos.x, pos.y, pos.z, c4Stack);
        itemEntity.setPickUpDelay(40);
        itemEntity.setUnlimitedLifetime();

        CompoundTag tag = itemEntity.getPersistentData();
        tag.putBoolean("c4_drop", true);

        serverLevel.addFreshEntity(itemEntity);
        BlockPos blockPos = itemEntity.blockPosition();

        String coordText = "§6X:" + blockPos.getX() + " Y:" + blockPos.getY() + " Z:" + blockPos.getZ();
        Component message = Component.literal("§c[C4] §e" + player.getName().getString() + " §a被击杀，C4已掉落！坐标: " + coordText);
        if (serverLevel.getServer() != null) {
            for (ServerPlayer p : serverLevel.getServer().getPlayerList().getPlayers()) {
                p.sendSystemMessage(message);
            }
        }
    }
}