package com.qisumei.c4.network;

import com.qisumei.c4.qis4c4;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class PacketHandler {
    /**
     * 协议版本。
     * <p>
     * v2：用 {@link C4HudPacket} 取代旧的 UIMessagePacket（新增“全员可见小字”与安装进度），
     * 旧客户端会因为协议不匹配被拒绝，避免出现“能进服但 UI 错乱”的情况。
     */
    private static final String PROTOCOL_VERSION = "2";

    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(qis4c4.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public static void register() {
        int id = 0;
        INSTANCE.registerMessage(id++, C4HudPacket.class,
                C4HudPacket::encode, C4HudPacket::decode, C4HudPacket::handle);
    }

    /** 发给单个玩家。 */
    public static void sendToPlayer(ServerPlayer player, C4HudPacket packet) {
        if (player == null) {
            return;
        }
        com.qisumei.c4.C4Debug.packet(player, packet);
        if (player.connection == null) {
            return;
        }
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /** 发给某一个维度里的所有玩家（跨维度不会串台）。 */
    public static void sendToLevel(ServerLevel level, C4HudPacket packet) {
        if (level == null) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            sendToPlayer(player, packet);
        }
    }

    /** 发给服务器内所有玩家。 */
    public static void sendToAll(C4HudPacket packet) {
        INSTANCE.send(PacketDistributor.ALL.noArg(), packet);
    }
}
