package com.qisumei.c4.sound;

import com.qisumei.c4.qis4c4;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, qis4c4.MODID);

    // C4 安装音效（在玩家位置播放）
    public static final RegistryObject<SoundEvent> C4_PLANT =
            SOUND_EVENTS.register("c4plant",
                    () -> SoundEvent.createVariableRangeEvent(
                            new ResourceLocation(qis4c4.MODID, "c4plant")
                    ));

    // C4 倒计时整段音效（保留给地图/资源包使用）
    public static final RegistryObject<SoundEvent> C4_COUNTDOWN =
            SOUND_EVENTS.register("c4countdown",
                    () -> SoundEvent.createVariableRangeEvent(
                            new ResourceLocation(qis4c4.MODID, "c4countdown")
                    ));

    /**
     * 单次滴滴声。
     * <p>
     * 从原版 40 秒倒计时音轨中截取的单个滴答声，由服务端按节奏逐次广播，
     * 这样无论玩家在哪儿、什么时候进服，听到的节奏都和引信完全同步。
     */
    public static final RegistryObject<SoundEvent> C4_BEEP =
            SOUND_EVENTS.register("c4beep",
                    () -> SoundEvent.createVariableRangeEvent(
                            new ResourceLocation(qis4c4.MODID, "c4beep")
                    ));

    // 拆除成功音效
    public static final RegistryObject<SoundEvent> CTW_SOUND =
            SOUND_EVENTS.register("ctw",
                    () -> SoundEvent.createVariableRangeEvent(
                            new ResourceLocation(qis4c4.MODID, "ctw")
                    ));

    // 爆炸音效
    public static final RegistryObject<SoundEvent> TW_SOUND =
            SOUND_EVENTS.register("tw",
                    () -> SoundEvent.createVariableRangeEvent(
                            new ResourceLocation(qis4c4.MODID, "tw")
                    ));

    public static void register(IEventBus bus) {
        SOUND_EVENTS.register(bus);
    }
}
