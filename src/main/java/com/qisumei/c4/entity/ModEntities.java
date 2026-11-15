package com.qisumei.c4.entity;

import com.qisumei.c4.qis4c4;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, qis4c4.MODID);

    public static final RegistryObject<EntityType<C4Entity>> C4_ENTITY =
            ENTITIES.register("c4", () -> EntityType.Builder.<C4Entity>of(C4Entity::new, MobCategory.MISC)
                    .sized(0.6f, 0.3f)
                    .clientTrackingRange(64)
                    .updateInterval(10)
                    .build("c4"));

    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
    }
}