package com.qisumei.c4.util;

import com.qisumei.c4.qis4c4;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 找出"掉在地上的 C4"（玩家被击杀掉落 / 换队被强制丢下产生的那种物品实体）。
 * <p>
 * 纯逻辑、不碰渲染，服务端也能调用，方便在自检里验证"掉落→能定位、捡起→不能定位"。
 */
public final class DroppedC4Locator {

    /** 同屏最多画多少个标记，避免有人在地上铺一地 C4 把画面糊住。 */
    public static final int MAX_MARKERS = 12;

    private DroppedC4Locator() {
    }

    public static boolean isDroppedC4(Entity entity) {
        if (!(entity instanceof ItemEntity item)) {
            return false;
        }
        return item.isAlive() && isC4Stack(item.getItem());
    }

    public static boolean isC4Stack(ItemStack stack) {
        return !stack.isEmpty() && stack.is(qis4c4.QISC4_ITEM.get());
    }

    /**
     * 找出半径内所有掉落的 C4，按距离从近到远排序，最多 {@link #MAX_MARKERS} 个。
     */
    public static List<ItemEntity> find(Level level, Vec3 center, double radius) {
        if (level == null || center == null || radius <= 0.0) {
            return new ArrayList<>();
        }
        AABB box = new AABB(center, center).inflate(radius + 1.0);
        List<ItemEntity> found = new ArrayList<>();
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (!isDroppedC4(item)) {
                continue;
            }
            if (item.position().distanceToSqr(center) > radius * radius) {
                continue;
            }
            found.add(item);
        }
        found.sort(Comparator.comparingDouble(item -> item.position().distanceToSqr(center)));
        if (found.size() > MAX_MARKERS) {
            return new ArrayList<>(found.subList(0, MAX_MARKERS));
        }
        return found;
    }
}
