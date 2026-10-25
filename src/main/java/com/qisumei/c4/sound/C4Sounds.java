package com.qisumei.c4.sound;

import com.qisumei.c4.C4Constants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * C4 的所有提示音都在服务端统一广播。
 * <p>
 * 旧实现有两个致命问题：
 * <ol>
 *     <li>用 {@code level.playSound(...)} 做一次性播放，只有离 C4 半径 24~48 格内的玩家能收到声音包，
 *     另一方在地图另一头就完全听不到；</li>
 *     <li>实体卸载／被移除时执行 {@code stopsound @a * qis4c4:c4countdown}，
 *     会把所有人的倒计时音效全部掐断，而 {@code COUNTDOWN_SOUND_PLAYED} 又保证它不会重播。</li>
 * </ol>
 * 现在改成：每一个声音包都直接发给同维度的每一位玩家，且坐标取玩家自身位置，
 * 这样客户端不存在“距离太远被衰减到 0”的问题，警匪双方在任何位置都能听见。
 */
public final class C4Sounds {

    private C4Sounds() {
    }

    /** 倒计时滴滴声。 */
    public static void beep(ServerLevel level, Vec3 bombPos, int ticksLeft) {
        com.qisumei.c4.C4Debug.beep(level, bombPos, ticksLeft);
        broadcast(level, ModSounds.C4_BEEP.get(), bombPos, 1.0f, 1.0f);
    }

    /** 安装完成的闷响。 */
    public static void planted(ServerLevel level, Vec3 bombPos) {
        broadcast(level, ModSounds.C4_PLANT.get(), bombPos, 1.0f, 1.0f);
    }

    /** 拆除成功的提示音。 */
    public static void defused(ServerLevel level, Vec3 bombPos) {
        broadcast(level, ModSounds.CTW_SOUND.get(), bombPos, 1.0f, 1.0f);
    }

    /** 爆炸音效。 */
    public static void exploded(ServerLevel level, Vec3 bombPos) {
        broadcast(level, ModSounds.TW_SOUND.get(), bombPos, 1.0f, 0.95f);
    }

    /** 开始拆除的金属摩擦声（用原版剪刀音效，短促、不吵）。 */
    public static void defuseStart(ServerLevel level, Vec3 bombPos) {
        broadcast(level, net.minecraft.sounds.SoundEvents.SHEEP_SHEAR, bombPos, 0.8f, 1.2f);
    }

    /**
     * 把声音播给同一维度里的所有玩家。
     *
     * @param level   炸弹所在维度
     * @param event   音效事件
     * @param bombPos 炸弹坐标，仅用于计算音量衰减
     * @param volume  基础音量
     * @param pitch   音调
     */
    public static void broadcast(ServerLevel level, SoundEvent event, Vec3 bombPos, float volume, float pitch) {
        if (event == null) {
            return;
        }
        if (level.getServer() == null) {
            return;
        }
        long seed = level.getRandom().nextLong();
        Holder<SoundEvent> holder = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(event);
        for (ServerPlayer player : level.players()) {
            if (player.connection == null) {
                continue;
            }
            double distance = Math.sqrt(player.distanceToSqr(bombPos));
            float playerVolume = volume * C4Constants.cueVolume(distance);
            try {
                // 关键：坐标使用玩家自身位置，客户端不会因为距离过远而把音量衰减成 0
                player.connection.send(new ClientboundSoundPacket(
                        holder, SoundSource.BLOCKS,
                        player.getX(), player.getY(), player.getZ(),
                        playerVolume, pitch, seed));
            } catch (Exception e) {
                // 单个玩家连接异常不应该影响其它人听见声音
            }
        }
    }
}
