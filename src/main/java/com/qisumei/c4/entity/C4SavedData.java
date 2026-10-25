package com.qisumei.c4.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 每个维度一份「已安装 C4」索引，存两样东西：
 * <ul>
 *     <li>{@code positions}：还活着的 C4 坐标。实体躲在没加载的区块里时，这是唯一的线索；</li>
 *     <li>{@code tombstones}：被 {@code /c4clear} 判死刑、但当时区块没加载的 C4 坐标。</li>
 * </ul>
 * C4 实体在进入世界/第一次 tick 时会查一次这张“死亡名单”（一次哈希查表），
 * 命中就当场自我删除。于是 {@code /c4clear} 既不用逐个加载区块，也不用每 tick 轮询，
 * 成本只跟“场上 C4 的数量”有关，与地图大小无关。
 */
public class C4SavedData extends SavedData {

    private static final String DATA_NAME = "qis4c4_c4";
    /** 死亡名单上限：正常情况下一两条，防止极端数据无限增长。 */
    private static final int MAX_TOMBSTONES = 64;

    private final Set<BlockPos> positions = new LinkedHashSet<>();
    private final Set<BlockPos> tombstones = new LinkedHashSet<>();

    public static C4SavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(C4SavedData::load, C4SavedData::new, DATA_NAME);
    }

    public static C4SavedData load(CompoundTag tag) {
        C4SavedData data = new C4SavedData();
        ListTag list = tag.getList("Positions", Tag.TAG_LONG);
        for (int i = 0; i < list.size(); i++) {
            BlockPos pos = BlockPos.of(((LongTag) list.get(i)).getAsLong());
            // 旧版本 /summon 时会误记一条 (0,0,0)，这里顺手丢掉
            if (!pos.equals(BlockPos.ZERO)) {
                data.positions.add(pos);
            }
        }
        ListTag dead = tag.getList("Tombstones", Tag.TAG_LONG);
        for (int i = 0; i < dead.size(); i++) {
            data.tombstones.add(BlockPos.of(((LongTag) dead.get(i)).getAsLong()));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (BlockPos pos : this.positions) {
            list.add(LongTag.valueOf(pos.asLong()));
        }
        tag.put("Positions", list);

        ListTag dead = new ListTag();
        for (BlockPos pos : this.tombstones) {
            dead.add(LongTag.valueOf(pos.asLong()));
        }
        tag.put("Tombstones", dead);
        return tag;
    }

    public void add(BlockPos pos) {
        if (pos != null && this.positions.add(pos.immutable())) {
            this.setDirty();
        }
    }

    public void remove(BlockPos pos) {
        if (pos == null) {
            return;
        }
        boolean changed = this.positions.remove(pos);
        changed |= this.tombstones.remove(pos);
        if (changed) {
            this.setDirty();
        }
    }

    public Set<BlockPos> getPositions() {
        return Collections.unmodifiableSet(this.positions);
    }

    public void clear() {
        if (!this.positions.isEmpty()) {
            this.positions.clear();
            this.setDirty();
        }
    }

    // ======================= 死亡名单 =======================

    /** 登记一个“区块一加载就立刻自我删除”的 C4。 */
    public void tombstone(BlockPos pos) {
        if (pos == null || BlockPos.ZERO.equals(pos)) {
            return;
        }
        BlockPos key = pos.immutable();
        this.positions.remove(key);
        if (this.tombstones.add(key)) {
            while (this.tombstones.size() > MAX_TOMBSTONES) {
                Iterator<BlockPos> it = this.tombstones.iterator();
                it.next();
                it.remove();
            }
            this.setDirty();
        }
    }

    /** O(1)：这个坐标上的 C4 是不是已经被判死刑。 */
    public boolean isTombstoned(BlockPos pos) {
        return pos != null && !this.tombstones.isEmpty() && this.tombstones.contains(pos);
    }

    public void consumeTombstone(BlockPos pos) {
        if (pos != null && this.tombstones.remove(pos)) {
            this.setDirty();
        }
    }

    public int getTombstoneCount() {
        return this.tombstones.size();
    }

    public void clearTombstones() {
        if (!this.tombstones.isEmpty()) {
            this.tombstones.clear();
            this.setDirty();
        }
    }
}
