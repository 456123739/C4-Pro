package com.qisumei.c4;

import com.mojang.logging.LogUtils;
import com.qisumei.c4.entity.C4Entity;
import com.qisumei.c4.entity.ModEntities;
import com.qisumei.c4.item.C4Item;
import com.qisumei.c4.network.PacketHandler;
import com.qisumei.c4.sound.ModSounds;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

@Mod(qis4c4.MODID)
public class qis4c4 {
    public static final String MODID = "qis4c4";
    public static final Logger LOGGER = LogUtils.getLogger();

    // 物品注册
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    // C4 物品
    public static final RegistryObject<C4Item> QISC4_ITEM =
            ITEMS.register("c4", C4Item::new);

    // C4 实体类型
    public static final RegistryObject<net.minecraft.world.entity.EntityType<C4Entity>> C4_ENTITY = ModEntities.C4_ENTITY;

    public qis4c4() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 注册配置
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // 注册网络包
        PacketHandler.register();

        // 注册内容
        ITEMS.register(modBus);
        ModSounds.register(modBus);
        ModEntities.register(modBus);

        // 注册指令（使用 Forge 总线）
        MinecraftForge.EVENT_BUS.register(new CommandHandler());

        LOGGER.info("Loaded mod {} v1.7.0", MODID);

        // 自检模式：只在显式传入 -Dqis4c4.selftest=true 时启用，正常游戏不会加载
        if (com.qisumei.c4.test.SelfTest.isEnabled()) {
            LOGGER.warn("[C4] self-test mode enabled");
            MinecraftForge.EVENT_BUS.register(com.qisumei.c4.test.SelfTest.class);
        }

        modBus.addListener(this::onBuildCreativeModeTabContents);
    }

    private void onBuildCreativeModeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(QISC4_ITEM.get());
        }
    }
}