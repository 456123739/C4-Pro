package com.qisumei.c4.entity;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class C4EntityTracker {
    private static final Set<C4Entity> activeC4Entities = ConcurrentHashMap.newKeySet();

    public static void addC4(C4Entity c4) {
        if (c4 != null && !c4.isRemoved()) {
            activeC4Entities.add(c4);
        }
    }

    public static void removeC4(C4Entity c4) {
        activeC4Entities.remove(c4);
    }

    public static Set<C4Entity> getAllC4() {
        pruneRemoved();
        return Collections.unmodifiableSet(activeC4Entities);
    }

    public static int getCount() {
        pruneRemoved();
        return activeC4Entities.size();
    }

    /**
     * 清掉已经不在世界里的实体。
     * <p>
     * 有些实体从未被 {@code addFreshEntity}（例如只构造来读 NBT 的实例），
     * 它们的 {@code discard()} 不会触发 {@code onRemovedFromWorld}，
     * 如果不裁剪，集合会越积越多，{@code /c4clear} 的判断也会跟着出错。
     */
    private static void pruneRemoved() {
        if (!activeC4Entities.isEmpty()) {
            activeC4Entities.removeIf(C4Entity::isRemoved);
        }
    }

    /** 只清空集合，不丢弃实体（服务器关闭时用）。 */
    public static void clear() {
        activeC4Entities.clear();
    }

    public static void clearAll() {
        // 清理所有C4实体
        for (C4Entity c4 : activeC4Entities) {
            if (!c4.isRemoved()) {
                c4.discard();
            }
        }
        activeC4Entities.clear();
    }
}