package com.qisumei.c4.handler;

import com.qisumei.c4.entity.C4Entity;
import com.qisumei.c4.qis4c4;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;

/**
 * 只负责“开始拆除”的入口，拆除过程本身由 {@link C4Entity#tick()} 推进。
 * <p>
 * 旧版本在这个类里用 {@code player.blockPosition()} 做“移动即中断”的判定，
 * 于是被队友撞一下（哪怕只挪动 0.01 格跨过了方块边界）就会中断。
 * 现在位移判定改到实体内部，按<b>距离容差</b>（{@link com.qisumei.c4.C4Constants#PUSH_TOLERANCE}）
 * 处理，小幅推动不会中断拆除。
 */
@Mod.EventBusSubscriber(modid = qis4c4.MODID)
public class C4InteractionHandler {

    /** 拆除的最大交互距离（格）。 */
    private static final double REACH = 4.0;

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof C4Entity c4)) {
            return;
        }
        event.setCanceled(true);
        tryStartDefuse(event.getEntity(), c4);
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getTarget() instanceof C4Entity c4)) {
            return;
        }
        event.setCanceled(true);
        tryStartDefuse(event.getEntity(), c4);
    }

    /**
     * 兜底：C4 的碰撞箱很小（贴在脚边），十字准星不一定能选中实体。
     * 当玩家手持剪刀/空手并且准星指到 C4 时，也允许通过物品交互事件开始拆除。
     */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        ItemStack stack = player.getMainHandItem();
        if (!stack.is(Items.SHEARS) && !stack.isEmpty()) {
            return;
        }
        C4Entity c4 = findLookedAtC4(player);
        if (c4 == null) {
            return;
        }
        event.setCanceled(true);
        tryStartDefuse(player, c4);
    }

    private static void tryStartDefuse(Player player, C4Entity c4) {
        if (player.level().isClientSide) {
            return;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            c4.tryStartDefuse(serverPlayer);
        }
    }

    @Nullable
    private static C4Entity findLookedAtC4(Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        Vec3 end = eye.add(look.x * REACH, look.y * REACH, look.z * REACH);

        AABB searchBox = player.getBoundingBox().inflate(REACH);
        C4Entity closest = null;
        double closestDistance = REACH + 1.0;

        for (var entity : player.level().getEntities(player, searchBox, e -> e instanceof C4Entity)) {
            C4Entity c4 = (C4Entity) entity;
            var hit = c4.getBoundingBox().clip(eye, end);
            if (hit.isPresent()) {
                double distance = eye.distanceTo(hit.get());
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closest = c4;
                }
            }
        }
        return closest;
    }
}
