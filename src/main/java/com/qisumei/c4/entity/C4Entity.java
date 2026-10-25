package com.qisumei.c4.entity;

import com.qisumei.c4.C4Constants;
import com.qisumei.c4.Config;
import com.qisumei.c4.ModInitializer;
import com.qisumei.c4.network.C4HudPacket;
import com.qisumei.c4.network.PacketHandler;
import com.qisumei.c4.qis4c4;
import com.qisumei.c4.sound.C4Sounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 已经安装好的 C4。
 * <p>
 * 设计要点：
 * <ul>
 *     <li><b>服务端权威</b>：引信、滴滴声节奏、拆除进度、拆除中断判定全部在服务端 tick 里推进，
 *     客户端只负责显示，避免两端计时器（旧版用的是 System.currentTimeMillis）漂移；</li>
 *     <li><b>区块票据</b>：安装后给所在区块加 FORCED 票据，保证没有玩家在附近时引信依然会走、
 *     滴滴声依然会响（旧版实体会随区块卸载而“停摆”，这是“有时听不到滴滴声”的主要原因之一）；</li>
 *     <li><b>全员广播</b>：安装、拆除、爆炸、滴滴声都会发给同维度的所有玩家。</li>
 * </ul>
 */
public class C4Entity extends Entity {

    private static final EntityDataAccessor<Integer> DATA_TICKS_LEFT =
            SynchedEntityData.defineId(C4Entity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_PLAYER_PLACED =
            SynchedEntityData.defineId(C4Entity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_DEFUSE_TICKS =
            SynchedEntityData.defineId(C4Entity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_DEFUSE_TOTAL =
            SynchedEntityData.defineId(C4Entity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_DEFUSER =
            SynchedEntityData.defineId(C4Entity.class, EntityDataSerializers.STRING);

    public static final float WIDTH = 0.7f;
    public static final float HEIGHT = 0.3f;

    /** 区块票据半径，2 对应 ENTITY_TICKING 等级（与原版 setChunkForced 一致）。 */
    private static final int CHUNK_TICKET_RADIUS = 2;

    // ---- 仅服务端使用的状态 ----
    private int nextBeepTick;
    private boolean announced20;
    private boolean announced10;
    private boolean announced4;
    private BlockPos c4Pos;
    /** 每个实体实例只查一次死亡名单。 */
    private boolean tombstoneChecked;
    private UUID defuserId;
    /** 拆除者的运行时引用：正常玩家从玩家列表解析，测试用的 FakePlayer 走这里的回退。 */
    private ServerPlayer defuserRef;
    private Vec3 defuseAnchor;
    private boolean forcedChunk;
    private boolean initialized;
    private boolean finished;
    /** 上一次推进逻辑的游戏刻，用于配合 {@code C4Manager} 避免重复 tick。 */
    private long lastLogicGameTime = Long.MIN_VALUE;

    public C4Entity(EntityType<? extends C4Entity> type, Level level) {
        super(type, level);
        this.noCulling = true;
    }

    public C4Entity(Level level, BlockPos pos, boolean playerPlaced) {
        this(qis4c4.C4_ENTITY.get(), level);
        this.c4Pos = pos.immutable();
        this.setPos(pos.getX() + 0.5D, pos.getY() + 0.05D, pos.getZ() + 0.5D);
        this.setTicksLeft(C4Constants.FUSE_TICKS);
        this.entityData.set(DATA_PLAYER_PLACED, playerPlaced);
        this.clearDefuseData();
        // 安装音效播放 1 秒之后再开始滴滴，避免两个声音糊在一起
        this.nextBeepTick = C4Constants.FUSE_TICKS - C4Constants.BEEP_FIRST_DELAY_TICKS;
        C4EntityTracker.addC4(this);
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_TICKS_LEFT, C4Constants.FUSE_TICKS);
        this.entityData.define(DATA_PLAYER_PLACED, true);
        this.entityData.define(DATA_DEFUSE_TICKS, 0);
        this.entityData.define(DATA_DEFUSE_TOTAL, 0);
        this.entityData.define(DATA_DEFUSER, "");
    }

    // ======================= 生命周期 =======================

    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        if (this.level().isClientSide) {
            return;
        }
        ServerLevel serverLevel = (ServerLevel) this.level();
        // 被 /c4clear 判过死刑的 C4：区块一加载就直接消失，连一 tick 都不给它
        if (this.consumeTombstone(serverLevel)) {
            this.discard();
            return;
        }
        this.forceChunk(serverLevel);
    }

    /** 命中“死亡名单”时返回 true，并把名单条目消耗掉。O(1) 哈希查表。 */
    private boolean consumeTombstone(ServerLevel level) {
        C4SavedData data = C4SavedData.get(level);
        if (!data.isTombstoned(this.getC4Pos())) {
            return false;
        }
        data.consumeTombstone(this.getC4Pos());
        this.finished = true;
        qis4c4.LOGGER.info("[C4] removed by an earlier /c4clear at {}", this.getC4Pos().toShortString());
        return true;
    }

    @Override
    public void onRemovedFromWorld() {
        super.onRemovedFromWorld();
        if (this.level().isClientSide) {
            return;
        }
        C4EntityTracker.removeC4(this);
        if (this.level() instanceof ServerLevel serverLevel) {
            qis4c4.LOGGER.debug("[C4] removed from world at {} reason={} removed={} fuse={}",
                    this.blockPosition().toShortString(), this.getRemovalReason(), this.isRemoved(), this.getTicksLeft());
        }
        if (this.isRemoved()) {
            this.releaseChunk((ServerLevel) this.level());
        }
    }

    // ======================= 每 tick 逻辑 =======================

    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide) {
            return;
        }
        ServerLevel level = (ServerLevel) this.level();
        this.lastLogicGameTime = level.getGameTime();
        this.tickLogic(level);
    }

    /**
     * 兜底推进：当实体所在区块没有 entity ticking 时，由 {@code C4Manager} 每 tick 调用。
     * <p>
     * 用游戏刻做幂等标记，实体自己 tick 过就不会重复推进。
     */
    public void tickLogicIfNeeded() {
        if (this.finished || this.isRemoved()) {
            return;
        }
        if (!(this.level() instanceof ServerLevel level) || level.getServer() == null) {
            return;
        }
        long now = level.getGameTime();
        if (this.lastLogicGameTime >= now) {
            return;
        }
        this.lastLogicGameTime = now;
        this.tickLogic(level);
    }

    private void tickLogic(ServerLevel level) {
        // 区块票据可能会因为 /forceload remove、维度重载等原因丢失，这里每 tick 兜底一次
        this.forceChunk(level);

        // 被 /c4clear 判过死刑、区块刚刚才加载出来：直接删，绝不让它继续倒计时。
        // 注意这里不能只看 !initialized —— 从存档里读回来的实体 initialized 已经是 true，
        // 而 onAddedToWorld 在“区块从磁盘加载实体”这条路径上不一定会被调用。
        if (!this.tombstoneChecked) {
            this.tombstoneChecked = true;
            if (this.consumeTombstone(level)) {
                this.discard();
                return;
            }
            // 位置此时才是最终值：登记进存档，保证 /c4clear 在实体没加载时也知道它在哪
            BlockPos recordedPos = this.getC4Pos();
            C4SavedData.get(level).add(recordedPos);
            // /summon 出来的 C4 带着 TicksLeft 时不会走 onFirstServerTick，
            // 这里补一次记分板，免得“C4 明明装着、状态却是空闲”。
            MinecraftServer server = level.getServer();
            if (this.initialized && server != null
                    && ModInitializer.getCurrentC4State(server) == ModInitializer.STATE_IDLE) {
                ModInitializer.updateC4State(server, ModInitializer.STATE_PLACED);
                ModInitializer.updateC4Position(server, recordedPos.getX(), recordedPos.getY(), recordedPos.getZ());
            }
        }
        if (!this.initialized) {
            this.initialized = true;
            this.onFirstServerTick(level);
        }
        if (this.finished || this.isRemoved()) {
            return;
        }

        int ticksLeft = this.getTicksLeft();
        if (ticksLeft <= 0) {
            this.explode();
            return;
        }
        ticksLeft--;
        this.setTicksLeft(ticksLeft);

        // ---- 滴滴声：服务端按节奏广播给所有人 ----
        if (ticksLeft <= this.nextBeepTick) {
            C4Sounds.beep(level, this.position(), ticksLeft);
            this.nextBeepTick = ticksLeft - C4Constants.beepIntervalTicks(ticksLeft);
        }

        // ---- 阶段提示 ----
        if (!this.announced20 && ticksLeft <= 20 * 20) {
            this.broadcast("§eC4 还剩 20 秒！");
            this.announced20 = true;
        }
        if (!this.announced10 && ticksLeft <= 10 * 20) {
            this.broadcast("§6C4 还剩 10 秒！");
            this.announced10 = true;
        }
        if (!this.announced4 && ticksLeft <= 4 * 20) {
            this.broadcast("§cC4 即将爆炸！");
            this.announced4 = true;
        }

        // ---- 拆除进度 ----
        if (this.defuserId != null) {
            this.tickDefuse(level);
        }
    }

    private void onFirstServerTick(ServerLevel level) {
        MinecraftServer server = level.getServer();
        BlockPos pos = this.getC4Pos();
        ModInitializer.updateC4State(server, ModInitializer.STATE_PLACED);
        ModInitializer.updateC4Position(server, pos.getX(), pos.getY(), pos.getZ());
        C4EntityTracker.addC4(this);

        C4SavedData.get(level).add(pos);

        // 安装提示：全员屏幕小字 + 安装音效
        PacketHandler.sendToLevel(level, C4HudPacket.notice("§eC4 已被安装", 4000));
        C4Sounds.planted(level, this.position());
        qis4c4.LOGGER.info("[C4] planted at {}", pos.toShortString());
    }

    // ======================= 区块票据 =======================

    private void forceChunk(ServerLevel level) {
        if (this.forcedChunk) {
            return;
        }
        try {
            ChunkPos chunkPos = new ChunkPos(this.blockPosition());
            level.getChunkSource().addRegionTicket(TicketType.FORCED, chunkPos, CHUNK_TICKET_RADIUS, chunkPos);
            this.forcedChunk = true;
        } catch (Exception e) {
            qis4c4.LOGGER.warn("[C4] failed to force chunk: {}", e.toString());
        }
    }

    private void releaseChunk(ServerLevel level) {
        if (!this.forcedChunk) {
            return;
        }
        this.forcedChunk = false;
        try {
            ChunkPos chunkPos = new ChunkPos(this.blockPosition());
            level.getChunkSource().removeRegionTicket(TicketType.FORCED, chunkPos, CHUNK_TICKET_RADIUS, chunkPos);
        } catch (Exception e) {
            qis4c4.LOGGER.warn("[C4] failed to release chunk: {}", e.toString());
        }
    }

    // ======================= 拆除 =======================

    /**
     * 尝试由某位玩家开始拆除。
     *
     * @return 是否成功开始（或该玩家已经在拆除中）
     */
    public boolean tryStartDefuse(ServerPlayer player) {
        if (this.finished || this.isRemoved()) {
            return false;
        }
        if (!isDefuseTeamAllowed(player)) {
            PacketHandler.sendToPlayer(player, C4HudPacket.selfMessage(
                    "§c您的队伍不允许拆除C4！允许的队伍: §e" + String.join(", ", Config.allowedDefuseTeams), 3000));
            return false;
        }
        if (!canUseDefuseTool(player.getMainHandItem())) {
            PacketHandler.sendToPlayer(player, C4HudPacket.selfMessage(
                    "§c需要使用剪刀（5秒）或空手（10秒）拆除C4！", 3000));
            return false;
        }

        if (this.defuserId != null) {
            if (this.defuserId.equals(player.getUUID())) {
                return true;
            }
            PacketHandler.sendToPlayer(player, C4HudPacket.selfMessage(
                    "§c" + this.getDefuserName() + " 正在拆除C4！", 2000));
            return false;
        }

        this.defuserId = player.getUUID();
        this.defuserRef = player;
        this.defuseAnchor = player.position();
        int total = C4Constants.defuseDurationTicks(player.getMainHandItem().is(Items.SHEARS));
        this.entityData.set(DATA_DEFUSE_TOTAL, total);
        this.entityData.set(DATA_DEFUSE_TICKS, 0);
        this.entityData.set(DATA_DEFUSER, player.getName().getString());

        String tool = player.getMainHandItem().is(Items.SHEARS) ? "剪刀" : "空手";
        PacketHandler.sendToLevel((ServerLevel) this.level(), C4HudPacket.notice(
                "§e" + player.getName().getString() + " §f正在用" + tool + "拆除 C4", 6000));
        C4Sounds.defuseStart((ServerLevel) this.level(), this.position());
        this.broadcastDefusePanel((ServerLevel) this.level(), 0.0F);
        qis4c4.LOGGER.info("[C4] {} starts defusing with {} ({} ticks)",
                player.getName().getString(), tool, total);
        return true;
    }

    /** 玩家手动中断（例如主动松手、被击杀）。 */
    public void cancelDefuse(@Nullable String reason) {
        if (this.defuserId == null) {
            return;
        }
        String name = this.getDefuserName();
        this.clearDefuseData();
        if (reason != null && !reason.isEmpty()) {
            PacketHandler.sendToLevel((ServerLevel) this.level(), C4HudPacket.notice(
                    "§c" + name + " 拆除中断！§7(" + reason + ")", 2500));
        }
        qis4c4.LOGGER.info("[C4] defuse cancelled: {} ({})", name, reason);
    }

    private void broadcastDefusePanel(ServerLevel level, float progress) {
        PacketHandler.sendToLevel(level, C4HudPacket.defusePanel(this.getDefuserName(), progress));
    }

    private void tickDefuse(ServerLevel level) {
        ServerPlayer defuser = this.findDefuser(level);
        int total = this.getDefuseTotal();
        if (total <= 0) {
            this.cancelDefuse("状态异常");
            return;
        }
        String problem = this.checkDefuseValid(defuser);
        if (problem != null) {
            this.cancelDefuse(problem);
            return;
        }
        int elapsed = this.getDefuseTicks() + 1;
        if (elapsed >= total) {
            this.completeDefuse(level, defuser);
            return;
        }
        this.entityData.set(DATA_DEFUSE_TICKS, elapsed);

        // 每 4 tick 广播一次进度，保证距离很远的玩家也能看到拆除面板（不受实体追踪范围限制）
        if (elapsed % 4 == 0) {
            this.broadcastDefusePanel(level, elapsed / (float) total);
        }
    }

    /**
     * 检查拆除是否仍然有效。
     *
     * @return 无效原因；有效时返回 {@code null}
     */
    @Nullable
    private String checkDefuseValid(@Nullable ServerPlayer defuser) {
        if (defuser == null || !defuser.isAlive() || defuser.isSpectator()) {
            return "拆除者离开";
        }
        if (!canUseDefuseTool(defuser.getMainHandItem())) {
            return "工具已切换";
        }
        double distance = defuser.distanceTo(this);
        if (distance > C4Constants.DEFUSE_MAX_RANGE + C4Constants.RANGE_SLACK) {
            return "距离过远";
        }
        if (this.defuseAnchor != null) {
            Vec3 now = defuser.position();
            double dx = now.x - this.defuseAnchor.x;
            double dy = now.y - this.defuseAnchor.y;
            double dz = now.z - this.defuseAnchor.z;
            if (!C4Constants.withinPushTolerance(dx, dy, dz)) {
                return "移动距离过大";
            }
        }
        return null;
    }

    private void completeDefuse(ServerLevel level, @Nullable ServerPlayer defuser) {
        String storedName = this.getDefuserName();
        String defuserName = defuser != null ? defuser.getName().getString() : storedName;
        this.finished = true;
        this.clearDefuseData();

        MinecraftServer server = level.getServer();
        ModInitializer.updateC4State(server, ModInitializer.STATE_DEFUSED);
        ModInitializer.clearC4Position(server);

        C4SavedData.get(level).remove(this.getC4Pos());
        C4Sounds.defused(level, this.position());
        PacketHandler.sendToLevel(level, C4HudPacket.notice("§aC4 已被 " + defuserName + " 拆除！", 5000));
        qis4c4.LOGGER.info("[C4] defused by {}", defuserName);

        this.discard();
    }

    // ======================= 爆炸 =======================

    private void explode() {
        if (this.finished) {
            return;
        }
        this.finished = true;
        this.clearDefuseData();

        ServerLevel level = (ServerLevel) this.level();
        MinecraftServer server = level.getServer();
        ModInitializer.updateC4State(server, ModInitializer.STATE_EXPLODED);
        ModInitializer.clearC4Position(server);

        C4SavedData.get(level).remove(this.getC4Pos());
        C4Sounds.exploded(level, this.position());
        PacketHandler.sendToLevel(level, C4HudPacket.notice("§cC4 已爆炸！", 5000));
        qis4c4.LOGGER.info("[C4] exploded at {}", this.blockPosition().toShortString());

        level.explode(null, this.getX(), this.getY(), this.getZ(),
                4.0F, Level.ExplosionInteraction.NONE);

        this.discard();
    }

    /**
     * 外部强制移除（{@code /kill}、{@code /c4clear}、虚空伤害等）。
     * <p>
     * 会一并收拾干净：拆除状态、记分板、坐标存档、音效提示，避免出现
     * “实体已经没了、记分板还停在 1”或者“清掉了、过一会儿又开始倒计时”的情况。
     *
     * @param reason 写入日志的原因
     */
    public void forceRemove(@Nullable String reason) {
        if (this.level().isClientSide) {
            return;
        }
        if (!this.finished) {
            this.finished = true;
            this.clearDefuseData();

            if (this.level() instanceof ServerLevel level) {
                C4SavedData.get(level).remove(this.getC4Pos());
                C4EntityTracker.removeC4(this);
                MinecraftServer server = level.getServer();
                if (server != null) {
                    ModInitializer.updateC4State(server, ModInitializer.STATE_IDLE);
                    ModInitializer.clearC4Position(server);
                }
                PacketHandler.sendToLevel(level, C4HudPacket.notice("§7C4 已被清除", 2500));
            }
            qis4c4.LOGGER.info("[C4] force-removed at {} ({})", this.blockPosition().toShortString(), reason);
        }
        this.discard();
    }

    /** {@code /kill} 走的是 {@link Entity#kill()}，这里必须显式支持，否则无敌实体杀不掉。 */
    @Override
    public void kill() {
        if (!this.level().isClientSide) {
            this.forceRemove("kill");
        }
    }

    // ======================= 同步数据读写 =======================

    private void clearDefuseData() {
        this.defuserId = null;
        this.defuserRef = null;
        this.defuseAnchor = null;
        this.entityData.set(DATA_DEFUSE_TICKS, 0);
        this.entityData.set(DATA_DEFUSE_TOTAL, 0);
        this.entityData.set(DATA_DEFUSER, "");
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        // 注意：/summon 会带着一个空 CompoundTag 走这里，所以每个字段都必须有默认值，
        // 否则 TicksLeft 会变成 0，C4 一被召唤就立刻爆炸。
        this.setTicksLeft(tag.contains("TicksLeft") ? tag.getInt("TicksLeft") : C4Constants.FUSE_TICKS);
        this.entityData.set(DATA_PLAYER_PLACED, !tag.contains("PlayerPlaced") || tag.getBoolean("PlayerPlaced"));
        this.nextBeepTick = tag.contains("NextBeepTick")
                ? tag.getInt("NextBeepTick")
                : C4Constants.FUSE_TICKS - C4Constants.BEEP_FIRST_DELAY_TICKS;
        this.announced20 = tag.getBoolean("Announced20");
        this.announced10 = tag.getBoolean("Announced10");
        this.announced4 = tag.getBoolean("Announced4");
        // 只有“真正的存档”才算已初始化；/summon 带来的空 tag 需要走一次首 tick 初始化
        // （记分板、安装提示、安装音效）
        this.initialized = tag.contains("TicksLeft");

        if (tag.contains("PosX")) {
            this.c4Pos = new BlockPos(tag.getInt("PosX"), tag.getInt("PosY"), tag.getInt("PosZ"));
        }

        String defuserUuid = tag.getString("DefuserUUID");
        if (!defuserUuid.isEmpty()) {
            try {
                this.defuserId = UUID.fromString(defuserUuid);
                this.defuseAnchor = new Vec3(tag.getDouble("AnchorX"), tag.getDouble("AnchorY"), tag.getDouble("AnchorZ"));
                this.entityData.set(DATA_DEFUSE_TICKS, tag.getInt("DefuseTicks"));
                this.entityData.set(DATA_DEFUSE_TOTAL, tag.getInt("DefuseTotal"));
                this.entityData.set(DATA_DEFUSER, tag.getString("DefuserName"));
            } catch (IllegalArgumentException e) {
                this.clearDefuseData();
            }
        }
        C4EntityTracker.addC4(this);
        // 注意：这里绝对不能往存档里写坐标。/summon 时 readAdditionalSaveData 会在指令把实体摆到
        // 目标坐标之前执行，此时 blockPosition() 还是 (0,0,0)，写进去就是一条错误的“幽灵坐标”。
        // 真正的登记放在第一次 tick（位置已经确定）。
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("TicksLeft", this.getTicksLeft());
        tag.putBoolean("PlayerPlaced", this.entityData.get(DATA_PLAYER_PLACED));
        tag.putInt("NextBeepTick", this.nextBeepTick);
        tag.putBoolean("Announced20", this.announced20);
        tag.putBoolean("Announced10", this.announced10);
        tag.putBoolean("Announced4", this.announced4);
        if (this.c4Pos != null) {
            tag.putInt("PosX", this.c4Pos.getX());
            tag.putInt("PosY", this.c4Pos.getY());
            tag.putInt("PosZ", this.c4Pos.getZ());
        }
        if (this.defuserId != null) {
            tag.putString("DefuserUUID", this.defuserId.toString());
            tag.putString("DefuserName", this.getDefuserName());
            tag.putInt("DefuseTicks", this.getDefuseTicks());
            tag.putInt("DefuseTotal", this.getDefuseTotal());
            if (this.defuseAnchor != null) {
                tag.putDouble("AnchorX", this.defuseAnchor.x);
                tag.putDouble("AnchorY", this.defuseAnchor.y);
                tag.putDouble("AnchorZ", this.defuseAnchor.z);
            }
        }
    }

    // ======================= 访问器 =======================

    public int getTicksLeft() {
        return this.entityData.get(DATA_TICKS_LEFT);
    }

    private void setTicksLeft(int ticks) {
        this.entityData.set(DATA_TICKS_LEFT, Math.max(0, ticks));
    }

    public boolean isPlayerPlaced() {
        return this.entityData.get(DATA_PLAYER_PLACED);
    }

    public int getDefuseTicks() {
        return this.entityData.get(DATA_DEFUSE_TICKS);
    }

    public int getDefuseTotal() {
        return this.entityData.get(DATA_DEFUSE_TOTAL);
    }

    public String getDefuserName() {
        return this.entityData.get(DATA_DEFUSER);
    }

    public boolean isBeingDefused() {
        return !this.finished && this.getDefuseTotal() > 0 && this.getDefuseTicks() < this.getDefuseTotal();
    }

    /** 拆除进度，0~1；未在拆除时返回 0。 */
    public float getDefuseProgress() {
        int total = this.getDefuseTotal();
        if (total <= 0) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, this.getDefuseTicks() / (float) total));
    }

    public boolean isDefusingBy(@Nullable Player player) {
        return player != null && this.defuserId != null && this.defuserId.equals(player.getUUID());
    }

    public BlockPos getC4Pos() {
        // 实体刚创建（尤其是 /summon）时 c4Pos 还是 null，位置也要等指令把它摆好才算数，
        // 所以这里顺手补一次，之后就一直用这个稳定坐标。
        if (this.c4Pos == null) {
            this.c4Pos = this.blockPosition().immutable();
        }
        return this.c4Pos;
    }

    @Nullable
    private ServerPlayer findDefuser(ServerLevel level) {
        if (this.defuserId == null) {
            return null;
        }
        if (level.getServer() != null) {
            ServerPlayer resolved = level.getServer().getPlayerList().getPlayer(this.defuserId);
            if (resolved != null) {
                this.defuserRef = resolved;
                return resolved;
            }
        }
        // 回退：用于 FakePlayer 等不在玩家列表中的场景
        ServerPlayer cached = this.defuserRef;
        if (cached != null && this.defuserId.equals(cached.getUUID())) {
            return cached;
        }
        this.defuserRef = null;
        return null;
    }

    // ======================= 工具方法 =======================

    private static boolean canUseDefuseTool(ItemStack stack) {
        return stack.is(Items.SHEARS) || stack.isEmpty();
    }

    private static boolean isDefuseTeamAllowed(Player player) {
        if (Config.allowedDefuseTeams == null || Config.allowedDefuseTeams.isEmpty()) {
            return true;
        }
        var team = player.getTeam();
        String teamName = team == null ? "" : team.getName();
        return Config.allowedDefuseTeams.contains(teamName);
    }

    private void broadcast(String message) {
        if (this.level() instanceof ServerLevel level) {
            for (ServerPlayer player : level.players()) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
            }
        }
    }

    // ======================= Entity 基础设置 =======================

    @Nonnull
    @Override
    public AABB getBoundingBoxForCulling() {
        return new AABB(this.getX() - WIDTH / 2.0, this.getY(), this.getZ() - WIDTH / 2.0,
                this.getX() + WIDTH / 2.0, this.getY() + HEIGHT, this.getZ() + WIDTH / 2.0);
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public float getPickRadius() {
        return 0.8F;
    }

    @Override
    public boolean hurt(@Nonnull DamageSource source, float amount) {
        // 只放行“无视无敌”的伤害（/kill、/damage ... generic_kill、虚空等），
        // 普通玩家攻击/爆炸依然打不掉 C4。
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            this.forceRemove("damage:" + source.getMsgId());
            return true;
        }
        return false;
    }

    @Override
    public boolean isInvulnerable() {
        return true;
    }

    @Override
    public boolean isInvulnerableTo(@Nonnull DamageSource source) {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }
}
