package com.qisumei.c4.test;

import com.mojang.authlib.GameProfile;
import com.qisumei.c4.C4Constants;
import com.qisumei.c4.C4Debug;
import com.qisumei.c4.Config;
import com.qisumei.c4.ModInitializer;
import com.qisumei.c4.entity.C4Entity;
import com.qisumei.c4.entity.C4EntityTracker;
import com.qisumei.c4.entity.C4SavedData;
import com.qisumei.c4.command.C4ClearCommand;
import com.qisumei.c4.network.C4HudPacket;
import com.qisumei.c4.qis4c4;
import com.qisumei.c4.util.DroppedC4Locator;
import com.qisumei.c4.util.MarkerMath;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.joml.Matrix4f;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nullable;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 服务端自检：真实 Forge 专用服务器 + 真实世界 + 真实实体 + FakePlayer。
 * <p>
 * 启动方式：{@code -Dqis4c4.selftest=true}（或环境变量 {@code C4_SELFTEST=true}）。
 * <p>
 * 第一阶段（同步）验证：数值节奏、网络包编解码、物品安装、记分板、区块票据、爆炸、拆除、
 * 推动容差、双人抢拆、NBT。
 * 第二阶段（真实 tick 循环，等待约 4 秒）验证：无人值守时引信真的在走、滴滴声真的在广播、
 * 远处区块真的是 entity ticking。
 * <p>
 * 结束写 {@code c4-selftest-report.txt}，日志出现 {@code RESULT: PASS} 并以 0 退出。
 */
public final class SelfTest {

    private static final String ENABLE_PROPERTY = "qis4c4.selftest";
    private static final int SETTLE_TICKS = 20;
    private static final int REAL_TICK_TICKS = 80;

    private static final List<String> PASSED = new ArrayList<>();
    private static final List<String> FAILED = new ArrayList<>();
    private static final List<C4HudPacket> PACKETS = Collections.synchronizedList(new ArrayList<>());
    private static final List<Integer> BEEPS = Collections.synchronizedList(new ArrayList<>());
    private static final List<SoundRecord> SOUNDS = Collections.synchronizedList(new ArrayList<>());

    /** 记录一次音效广播，用来断言“用的还是原始那几个音效、原始的音量音调”。 */
    private record SoundRecord(net.minecraft.sounds.SoundEvent event, float volume, float pitch) {
        boolean matches(String path, float expectedVolume, float expectedPitch) {
            if (event == null || event.getLocation() == null) {
                return false;
            }
            return path.equals(event.getLocation().getPath())
                    && Math.abs(volume - expectedVolume) < 1.0e-4F
                    && Math.abs(pitch - expectedPitch) < 1.0e-4F;
        }

        boolean isPath(String path) {
            return event != null && event.getLocation() != null && path.equals(event.getLocation().getPath());
        }
    }

    // 状态机
    private static MinecraftServer server;
    private static int phase = 0;
    private static int waitTicks = 0;
    private static boolean waiting = false;
    private static int waitTarget = SETTLE_TICKS;
    private static ServerLevel level;

    // 第二阶段要检查的对象
    private static C4Entity farC4;
    private static C4Entity timedC4;
    private static int timedC4StartTicks;
    private static long timedC4StartGameTime;
    private static int beepsAtPhaseStart;

    private SelfTest() {
    }

    public static boolean isEnabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY)
                || "true".equalsIgnoreCase(System.getenv("C4_SELFTEST"));
    }

    // ======================= 调度 =======================

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        level = server.overworld();
        phase = 1;
        waitTarget = SETTLE_TICKS;
        waitTicks = 0;
        waiting = true;
        qis4c4.LOGGER.info("[C4][SelfTest] scheduled, waiting {} ticks for the world to settle", SETTLE_TICKS);
    }

    /**
     * 用真实 tick 事件做延时。
     * <p>
     * 注意：{@code server.tell(new TickTask(...))} 在这里不可靠 ——
     * {@code MinecraftServer.shouldRun(TickTask)} 的条件是 {@code tick + 3 >= tickCount || haveTime()}，
     * 只要当前 tick 还有剩余时间，任务就会立刻执行，根本不会等到指定 tick。
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !waiting) {
            return;
        }
        if (++waitTicks < waitTarget) {
            return;
        }
        waiting = false;
        try {
            if (phase == 1) {
                runPhaseOne();
                phase = 2;
                waitTarget = REAL_TICK_TICKS;
                waitTicks = 0;
                waiting = true;
            } else if (phase == 2) {
                runPhaseTwo();
            }
        } catch (Throwable t) {
            fail("unexpected exception: " + t);
            qis4c4.LOGGER.error("[C4][SelfTest] crashed", t);
            reportAndExit();
        }
    }

    // ======================= 第一阶段 =======================

    private static void runPhaseOne() {
        C4Debug.setListener(new C4Debug.Listener() {
            @Override
            public void onBeep(ServerLevel lvl, Vec3 bombPos, int ticksLeft) {
                BEEPS.add(ticksLeft);
            }

            @Override
            public void onPacket(ServerPlayer player, C4HudPacket packet) {
                PACKETS.add(packet);
            }

            @Override
            public void onSound(ServerLevel lvl, net.minecraft.sounds.SoundEvent event, Vec3 bombPos,
                                float volume, float pitch) {
                SOUNDS.add(new SoundRecord(event, volume, pitch));
            }
        });

        // 上一次运行可能留下 C4，先清干净，保证可重复
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof C4Entity) {
                entity.discard();
            }
        }
        C4EntityTracker.clearAll();
        ModInitializer.updateC4State(server, ModInitializer.STATE_IDLE);

        Platform.prepare(level, new BlockPos(40, 100, 40));

        testPureMath();
        testPacketCodec();

        FakePlayer planter = fakePlayer(level, "C4Planter", new Vec3(40.5, 100.0, 40.5));
        FakePlayer defuser = fakePlayer(level, "C4Defuser", new Vec3(43.5, 100.0, 40.5));
        FakePlayer intruder = fakePlayer(level, "C4Intruder", new Vec3(43.5, 100.0, 41.5));

        testPlant(level, planter);
        testExplosion(level);
        testDefuse(level, defuser, intruder);
        testPersistence(level);
        testSummon(level);
        testRemoval(level);
        testMarker(level);
        testTeamRoles(level);
        testSoundIdentity(level);

        // 为第二阶段准备两个 C4：一个在远景无人区，一个在平台附近
        BlockPos far = new BlockPos(600, 100, 600);
        Platform.prepareSingle(level, far);
        farC4 = new C4Entity(level, far, true);
        level.addFreshEntity(farC4);
        farC4.tick();

        BlockPos near = new BlockPos(40, 100, 52);
        timedC4 = new C4Entity(level, near, true);
        // 原始节奏只在最后 2 秒（剩余 ≤ 40 tick）才补滴答声，
        // 所以把引信设成“窗口内正好走进最后 2 秒”，才能验证真实 tick 循环里的滴答
        timedC4.setTicksLeft(REAL_TICK_TICKS + 30);
        level.addFreshEntity(timedC4);
        timedC4.tick();

        timedC4StartTicks = timedC4.getTicksLeft();
        timedC4StartGameTime = level.getGameTime();
        beepsAtPhaseStart = BEEPS.size();
        qis4c4.LOGGER.info("[C4][SelfTest] phase 1 done, waiting {} ticks for real-time checks", REAL_TICK_TICKS);
    }

    // ======================= 第二阶段（真实 tick 循环） =======================

    private static void runPhaseTwo() {
        int elapsedTicks = (int) (level.getGameTime() - timedC4StartGameTime);
        int consumed = timedC4StartTicks - timedC4.getTicksLeft();
        int beeps = BEEPS.size() - beepsAtPhaseStart;

        qis4c4.LOGGER.info("[C4][SelfTest] real-tick check: elapsed={} fuseConsumed={} beeps={}",
                elapsedTicks, consumed, beeps);

        check("real server ticks advance the fuse", consumed >= REAL_TICK_TICKS / 2);
        check("fuse advances at roughly 20 ticks per second", consumed <= elapsedTicks + 5);
        check("beeps keep coming from the real tick loop", beeps >= 2);

        int farConsumed = C4Constants.FUSE_TICKS - farC4.getTicksLeft();
        boolean farChunkLoaded = level.getChunkSource().getChunkNow(
                farC4.getBlockX() >> 4, farC4.getBlockZ() >> 4) != null;
        qis4c4.LOGGER.info("[C4][SelfTest] far C4: consumed={} chunkLoaded={} entityTicking={} (entityTicking 仅供参考)",
                farConsumed, farChunkLoaded, level.isPositionEntityTicking(farC4.blockPosition()));
        check("far C4 chunk stays loaded while armed", farChunkLoaded);
        check("far C4 keeps counting down with no player nearby", farConsumed >= REAL_TICK_TICKS / 2);

        farC4.discard();
        timedC4.discard();
        reportAndExit();
    }

    // ======================= 纯逻辑 =======================

    private static void testPureMath() {
        check("beep interval at full fuse is 2s", C4Constants.beepIntervalTicks(C4Constants.FUSE_TICKS) == 40);
        check("beep interval at half fuse is 24 ticks", C4Constants.beepIntervalTicks(C4Constants.FUSE_TICKS / 2) == 24);
        check("beep interval clamps at min", C4Constants.beepIntervalTicks(1) == C4Constants.BEEP_MIN_INTERVAL_TICKS);
        check("beep interval clamps at max", C4Constants.beepIntervalTicks(Integer.MAX_VALUE) == C4Constants.BEEP_MAX_INTERVAL_TICKS);
        // 与原始模组 calculateNextInterval 的逐点对照（progress = 1 - remaining/800）
        boolean matchesOriginal = true;
        for (int t = 0; t <= C4Constants.FUSE_TICKS; t++) {
            float progress = 1 - (t / (float) C4Constants.FUSE_TICKS);
            int expected = Math.max(8, (int) (40 * (1 - progress * 0.8f)));
            if (C4Constants.beepIntervalTicks(t) != expected) {
                matchesOriginal = false;
                break;
            }
        }
        check("beep interval matches the original formula everywhere", matchesOriginal);

        boolean monotonic = true;
        int previous = Integer.MAX_VALUE;
        for (int t = C4Constants.FUSE_TICKS; t >= 0; t--) {
            int interval = C4Constants.beepIntervalTicks(t);
            if (interval > previous || interval < C4Constants.BEEP_MIN_INTERVAL_TICKS
                    || interval > C4Constants.BEEP_MAX_INTERVAL_TICKS) {
                monotonic = false;
                break;
            }
            previous = interval;
        }
        check("beep interval never increases and stays in range", monotonic);

        check("cue volume is full at zero distance", C4Constants.cueVolume(0.0) == 1.0F);
        check("cue volume never drops below floor", C4Constants.cueVolume(10_000.0) == C4Constants.CUE_MIN_VOLUME);
        check("cue volume decreases with distance", C4Constants.cueVolume(10.0) > C4Constants.cueVolume(150.0));

        check("small push is tolerated", C4Constants.withinPushTolerance(0.5, 0.0, 0.5));
        check("0.9 block push is tolerated", C4Constants.withinPushTolerance(0.9, 0.0, 0.0));
        check("1.5 block walk is not tolerated", !C4Constants.withinPushTolerance(1.5, 0.0, 0.0));
        check("vertical knock-up is tolerated inside tolerance", C4Constants.withinPushTolerance(0.0, 1.4, 0.0));

        check("shears defuse takes 5s", C4Constants.defuseDurationTicks(true) == 100);
        check("hand defuse takes 10s", C4Constants.defuseDurationTicks(false) == 200);
    }

    private static void testPacketCodec() {
        C4HudPacket packet = C4HudPacket.notice("§eC4 已被安装", 4000);
        io.netty.buffer.ByteBuf buffer = io.netty.buffer.Unpooled.buffer();
        try {
            C4HudPacket.encode(packet, new net.minecraft.network.FriendlyByteBuf(buffer));
            C4HudPacket decoded = C4HudPacket.decode(new net.minecraft.network.FriendlyByteBuf(buffer));
            check("hud packet roundtrip keeps kind", decoded.getKind() == packet.getKind());
            check("hud packet roundtrip keeps text", packet.getText().equals(decoded.getText()));
            check("hud packet roundtrip keeps duration", decoded.getDurationMs() == 4000);
        } finally {
            buffer.release();
        }

        C4HudPacket panel = C4HudPacket.defusePanel("C4Defuser", 0.42F);
        io.netty.buffer.ByteBuf buffer2 = io.netty.buffer.Unpooled.buffer();
        try {
            C4HudPacket.encode(panel, new net.minecraft.network.FriendlyByteBuf(buffer2));
            C4HudPacket decoded2 = C4HudPacket.decode(new net.minecraft.network.FriendlyByteBuf(buffer2));
            check("defuse panel roundtrip keeps name", "C4Defuser".equals(decoded2.getText()));
            check("defuse panel roundtrip keeps progress", Math.abs(decoded2.getValue() - 0.42F) < 0.001F);
            check("defuse panel kind", decoded2.getKind() == C4HudPacket.KIND_DEFUSE_PANEL);
        } finally {
            buffer2.release();
        }
    }

    // ======================= 安装 =======================

    private static void testPlant(ServerLevel level, FakePlayer planter) {
        BlockPos target = planter.blockPosition();
        PACKETS.clear();

        ItemStack stack = new ItemStack(qis4c4.QISC4_ITEM.get());
        planter.setItemInHand(InteractionHand.MAIN_HAND, stack);
        InteractionResultHolder<ItemStack> result = stack.getItem().use(level, planter, InteractionHand.MAIN_HAND);
        check("item use is accepted on allowed block", result.getResult().consumesAction());
        check("install progress packet is sent to planter", hasPacket(C4HudPacket.KIND_INSTALL_START));

        int duration = stack.getItem().getUseDuration(stack);
        check("install duration is 3.5s", duration == C4Constants.PLANT_TICKS);
        for (int remaining = duration - 1; remaining >= 1; remaining--) {
            stack.getItem().onUseTick(level, planter, stack, remaining);
        }

        C4Entity c4 = findC4(level, target);
        check("C4 entity was created", c4 != null);
        if (c4 == null) {
            return;
        }
        check("item stack was consumed", planter.getMainHandItem().isEmpty());
        check("fuse starts at 40s", c4.getTicksLeft() == C4Constants.FUSE_TICKS);

        c4.tick();
        check("C4 已被安装 notice is broadcast", hasNotice("已被安装"));
        check("scoreboard state is PLACED", ModInitializer.getCurrentC4State(server) == 1);
        check("scoreboard position is stored", readScore("p1score") == target.getX()
                && readScore("p2score") == target.getY()
                && readScore("p3score") == target.getZ());
        check("C4 is registered in tracker", C4EntityTracker.getAllC4().contains(c4));
    }

    // ======================= 爆炸 =======================

    private static void testExplosion(ServerLevel level) {
        BlockPos pos = new BlockPos(45, 100, 40);
        C4Entity c4 = new C4Entity(level, pos, true);
        level.addFreshEntity(c4);
        c4.tick();

        BEEPS.clear();
        PACKETS.clear();

        int guard = 0;
        while (!c4.isRemoved() && guard++ < C4Constants.FUSE_TICKS + 40) {
            c4.tick();
        }

        check("C4 explodes after the fuse", c4.isRemoved());
        check("scoreboard state is EXPLODED", ModInitializer.getCurrentC4State(server) == 2);
        check("explosion clears stored position", readScore("p1score") == 0
                && readScore("p2score") == 0 && readScore("p3score") == 0);
        check("C4 已爆炸 notice is broadcast", hasNotice("已爆炸"));
        check("beeps were emitted", !BEEPS.isEmpty());
        check("beep count matches the documented rhythm", BEEPS.size() == expectedBeepCount());

        int earlyGap = BEEPS.size() > 1 ? BEEPS.get(0) - BEEPS.get(1) : Integer.MAX_VALUE;
        int lateGap = BEEPS.size() > 2 ? BEEPS.get(BEEPS.size() - 2) - BEEPS.get(BEEPS.size() - 1) : 0;
        check("beeps accelerate over time", lateGap < earlyGap);
    }

    // ======================= 拆除 =======================

    private static void testDefuse(ServerLevel level, FakePlayer defuser, FakePlayer intruder) {
        BlockPos pos = new BlockPos(40, 100, 44);
        C4Entity c4 = new C4Entity(level, pos, true);
        level.addFreshEntity(c4);
        c4.tick();

        PACKETS.clear();
        defuser.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));
        Vec3 anchor = new Vec3(pos.getX() + 1.5, pos.getY(), pos.getZ() + 0.5);
        defuser.setPos(anchor.x, anchor.y, anchor.z);

        check("defuse starts with shears", c4.tryStartDefuse(defuser));
        check("defuse total is 5s", c4.getDefuseTotal() == C4Constants.DEFUSE_SHEARS_TICKS);
        check("defuser name is synced for every client", defuser.getName().getString().equals(c4.getDefuserName()));
        check("defuse start notice is broadcast", hasNotice("正在用"));

        for (int i = 0; i < 40; i++) {
            c4.tick();
        }
        check("defuse progress advances", c4.getDefuseProgress() > 0.3F && c4.getDefuseProgress() < 0.5F);
        check("defuse ticks are synced (UI source)", c4.getDefuseTicks() == 40);
        check("defuse progress panel is broadcast to every player", hasDefusePanel(0.3F));

        // 被队友/实体碰撞推动 0.6 格：不应中断
        defuser.setPos(anchor.x + 0.6, anchor.y, anchor.z + 0.2);
        for (int i = 0; i < 5; i++) {
            c4.tick();
        }
        check("small push does not cancel defusing", c4.isBeingDefused());
        check("progress keeps growing after push", c4.getDefuseTicks() == 45);

        intruder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));
        intruder.setPos(pos.getX() + 1.5, pos.getY(), pos.getZ() + 1.5);
        check("second player cannot steal the defuse", !c4.tryStartDefuse(intruder));
        check("steal attempt is told to wait", hasSelfMessage("正在拆除"));

        defuser.setPos(anchor.x + 2.5, anchor.y, anchor.z);
        c4.tick();
        check("walking away cancels defusing", !c4.isBeingDefused());
        check("cancel notice is broadcast", hasNotice("拆除中断"));

        defuser.setPos(anchor.x, anchor.y, anchor.z);
        check("defuse can be restarted", c4.tryStartDefuse(defuser));
        PACKETS.clear();
        int guard = 0;
        while (!c4.isRemoved() && guard++ < 200) {
            c4.tick();
        }
        check("C4 is removed after defuse", c4.isRemoved());
        check("scoreboard state is DEFUSED", ModInitializer.getCurrentC4State(server) == 3);
        check("defuse notice is broadcast to everyone", hasNotice("已被"));
    }

    // ======================= 清除（/kill 与 /c4clear） =======================

    /** 数据包 /kill 与本模组 /c4clear 都必须真的删掉 C4，包括未加载区块里的。 */
    private static void testRemoval(ServerLevel level) {
        // 1) /kill 底层调用 Entity.kill()
        BlockPos killPos = new BlockPos(40, 100, 60);
        Platform.prepareSingle(level, killPos);
        C4Entity killC4 = new C4Entity(level, killPos, true);
        level.addFreshEntity(killC4);
        killC4.tick();
        check("kill target is registered in tracker", C4EntityTracker.getAllC4().contains(killC4));
        check("kill target is recorded in saved data", C4SavedData.get(level).getPositions().contains(killPos));

        killC4.kill();
        check("/kill really removes the entity", killC4.isRemoved());
        check("/kill drops it from the tracker", !C4EntityTracker.getAllC4().contains(killC4));
        check("/kill clears the saved position", !C4SavedData.get(level).getPositions().contains(killPos));
        check("/kill resets the scoreboard", ModInitializer.getCurrentC4State(server) == ModInitializer.STATE_IDLE);

        // 2) 未加载区块：只剩存档坐标的“幽灵 C4”
        BlockPos orphanPos = new BlockPos(1200, 100, 1200);
        C4SavedData.get(level).add(orphanPos);
        check("orphan position is persisted", C4SavedData.get(level).getPositions().contains(orphanPos));
        // 世界里可能存在上一轮测试/上一版留下的 C4，所以这里只断言“清完之后一定干净”
        boolean chunkWasLoaded = level.hasChunkAt(orphanPos);
        C4ClearCommand.Result orphanResult = C4ClearCommand.clearAll(server);
        check("/c4clear tombstones orphan positions", orphanResult.tombstoned >= 1);
        check("/c4clear does not load the orphan chunk", !chunkWasLoaded && !level.hasChunkAt(orphanPos));
        check("/c4clear drops orphan positions", C4SavedData.get(level).getPositions().isEmpty());
        check("/c4clear remembers the orphan as a tombstone",
                C4SavedData.get(level).isTombstoned(orphanPos));
        check("/c4clear resets the scoreboard when nothing was loaded",
                ModInitializer.getCurrentC4State(server) == ModInitializer.STATE_IDLE);
        check("/c4clear leaves no live C4 anywhere", level.getEntitiesOfClass(C4Entity.class,
                new AABB(orphanPos).inflate(4.0)).isEmpty());
        check("/c4clear leaves no C4 in the tracker", C4EntityTracker.getAllC4().isEmpty());

        // 3) 区块已加载、正在倒计时的 C4
        BlockPos livePos = new BlockPos(40, 100, 64);
        Platform.prepareSingle(level, livePos);
        C4Entity live = new C4Entity(level, livePos, true);
        level.addFreshEntity(live);
        live.tick();
        C4ClearCommand.Result liveResult = C4ClearCommand.clearAll(server);
        check("/c4clear reports the ticking C4", liveResult.removed >= 1);
        check("/c4clear removes the ticking C4", live.isRemoved());
        check("/c4clear keeps the tracker empty", C4EntityTracker.getAllC4().isEmpty());
        check("/c4clear clears saved data completely", C4SavedData.get(level).getPositions().isEmpty());

        // 4) 死亡名单必须能让 C4 自己消失（区块加载时不再倒计时）
        BlockPos deadPos = new BlockPos(40, 100, 68);
        Platform.prepareSingle(level, deadPos);
        C4SavedData.get(level).tombstone(deadPos);
        check("tombstone is recorded", C4SavedData.get(level).isTombstoned(deadPos));
        C4Entity doomed = new C4Entity(level, deadPos, true);
        level.addFreshEntity(doomed);
        if (!doomed.isRemoved()) {
            doomed.tick();
        }
        check("tombstoned C4 destroys itself", doomed.isRemoved());
        check("tombstone is consumed after use", !C4SavedData.get(level).isTombstoned(deadPos));
        check("tombstoned C4 never joins the tracker", !C4EntityTracker.getAllC4().contains(doomed));

        // 5) 新装的 C4 不会被历史死亡名单误杀
        BlockPos freshPos = new BlockPos(40, 100, 72);
        Platform.prepareSingle(level, freshPos);
        C4SavedData.get(level).tombstone(freshPos);
        C4SavedData.get(level).consumeTombstone(freshPos);
        C4Entity fresh = new C4Entity(level, freshPos, true);
        level.addFreshEntity(fresh);
        fresh.tick();
        check("a freshly planted C4 survives a cleared position", !fresh.isRemoved());
        fresh.forceRemove("selftest-cleanup");

        // 6) 从存档读回来的实体（initialized 已经是 true）也必须被死亡名单收拾
        BlockPos reloadPos = new BlockPos(40, 100, 76);
        Platform.prepareSingle(level, reloadPos);
        C4Entity origin = new C4Entity(level, reloadPos, true);
        origin.tick();
        CompoundTag savedTag = new CompoundTag();
        origin.saveWithoutId(savedTag);
        origin.forceRemove("selftest-cleanup");
        C4SavedData.get(level).tombstone(reloadPos);
        C4Entity reloaded = new C4Entity(level, reloadPos, true);
        reloaded.load(savedTag);
        level.addFreshEntity(reloaded);
        reloaded.tickLogicIfNeeded();
        check("a reloaded C4 obeys the death list", reloaded.isRemoved());

        C4SavedData.get(level).clearTombstones();
        C4SavedData.get(level).clear();
    }

    // ======================= 掉落 C4 悬浮标记 =======================

    /**
     * 标记的“逻辑一半”全部在服务端可测：掉落物识别、半径筛选、捡起后消失、屏幕方向投影。
     * 客户端渲染部分（RenderLevelStageEvent）无法在无头服务器上跑，这里只验证它依赖的数据。
     */
    private static void testMarker(ServerLevel level) {
        Platform.prepare(level, new BlockPos(40, 100, 40));

        // 1) 只认"C4 物品"的掉落物
        BlockPos dropPos = new BlockPos(40, 100, 44);
        ItemEntity c4Drop = new ItemEntity(level, dropPos.getX() + 0.5, dropPos.getY() + 0.25,
                dropPos.getZ() + 0.5, new ItemStack(qis4c4.QISC4_ITEM.get()));
        c4Drop.setPickUpDelay(0);
        level.addFreshEntity(c4Drop);

        ItemEntity dirtDrop = new ItemEntity(level, dropPos.getX() + 1.5, dropPos.getY() + 0.25,
                dropPos.getZ() + 0.5, new ItemStack(Items.DIRT));
        dirtDrop.setPickUpDelay(0);
        level.addFreshEntity(dirtDrop);

        Vec3 eye = new Vec3(dropPos.getX() + 0.5, dropPos.getY() + 1.0, dropPos.getZ() + 0.5);
        List<ItemEntity> near = DroppedC4Locator.find(level, eye, 16.0);
        check("dropped C4 is found by the locator", near.contains(c4Drop));
        check("other dropped items are ignored", !near.contains(dirtDrop));
        check("locator only returns C4 item entities",
                near.stream().allMatch(DroppedC4Locator::isDroppedC4));

        // 2) 半径筛选：远处的不显示，走远了才显示
        check("locator respects the radius", !DroppedC4Locator.find(level, new Vec3(
                dropPos.getX() + 0.5, dropPos.getY() + 0.25, dropPos.getZ() + 8.5), 2.0).contains(c4Drop));
        check("locator finds it again inside the radius", DroppedC4Locator.find(level, new Vec3(
                dropPos.getX() + 0.5, dropPos.getY() + 0.25, dropPos.getZ() + 8.5), 16.0).contains(c4Drop));

        // 3) 数量上限，避免有人往地上扔一箱子 C4 把客户端拖垮
        List<ItemEntity> many = new ArrayList<>();
        for (int i = 0; i < DroppedC4Locator.MAX_MARKERS + 4; i++) {
            ItemEntity extra = new ItemEntity(level, dropPos.getX() + 0.5 + i * 0.15,
                    dropPos.getY() + 0.25, dropPos.getZ() + 0.5, new ItemStack(qis4c4.QISC4_ITEM.get()));
            extra.setPickUpDelay(1000);
            level.addFreshEntity(extra);
            many.add(extra);
        }
        check("locator caps the number of markers",
                DroppedC4Locator.find(level, eye, 16.0).size()
                        == Math.min(DroppedC4Locator.MAX_MARKERS, many.size() + 1));

        // 4) 被玩家捡起后，标记必须消失（走真实的 playerTouch 拾取路径）
        FakePlayer picker = fakePlayer(level, "C4Picker", new Vec3(dropPos.getX() + 0.5, dropPos.getY(), dropPos.getZ() + 0.5));
        c4Drop.playerTouch(picker);
        check("picked-up C4 leaves the world", c4Drop.isRemoved());
        check("picked-up C4 no longer shows a marker", !DroppedC4Locator.find(level, eye, 16.0).contains(c4Drop));
        check("picked-up C4 goes into the inventory",
                picker.getInventory().contains(new ItemStack(qis4c4.QISC4_ITEM.get())));

        // 5) 重新扔回地上，标记又出现（"掉落后再显示"）
        ItemEntity again = new ItemEntity(level, dropPos.getX() + 0.5, dropPos.getY() + 0.25,
                dropPos.getZ() + 0.5, new ItemStack(qis4c4.QISC4_ITEM.get()));
        level.addFreshEntity(again);
        check("a re-dropped C4 shows the marker again",
                DroppedC4Locator.find(level, eye, 16.0).contains(again));

        dirtDrop.discard();
        again.discard();
        for (ItemEntity extra : many) {
            extra.discard();
        }

        // 6) 纯数学：大小、淡出、呼吸、屏幕方向
        check("marker scale is clamped at the low end", Math.abs(MarkerMath.scaleFor(0.0) - MarkerMath.MIN_SCALE) < 1.0E-9);
        check("marker scale is clamped at the high end", Math.abs(MarkerMath.scaleFor(10_000.0) - MarkerMath.MAX_SCALE) < 1.0E-9);
        check("marker scale grows with distance", MarkerMath.scaleFor(20.0) > MarkerMath.scaleFor(8.0));
        check("marker is invisible beyond the range", MarkerMath.fadeFor(64.0, 64.0) == 0.0f);
        check("marker is fully visible inside 72% of the range", MarkerMath.fadeFor(40.0, 64.0) == 1.0f);
        check("marker fades out gradually", MarkerMath.fadeFor(58.0, 64.0) > 0.0f
                && MarkerMath.fadeFor(58.0, 64.0) < 1.0f);
        check("marker fades monotonically", MarkerMath.fadeFor(50.0, 64.0) > MarkerMath.fadeFor(62.0, 64.0));
        boolean bobOk = true;
        boolean pulseOk = true;
        for (int i = 0; i < 400; i++) {
            double t = i * 0.05;
            if (Math.abs(MarkerMath.bob(t)) > 0.056) {
                bobOk = false;
            }
            float p = MarkerMath.pulse(t);
            if (p < 0.49f || p > 1.01f) {
                pulseOk = false;
            }
        }
        check("marker bobbing stays subtle", bobOk);
        check("marker pulse stays inside 0.5~1.0", pulseOk);

        // 7) 屏幕方向投影：正前方在屏幕内；右侧/上方/背后给出正确的"往哪看"
        Matrix4f identityView = new Matrix4f();
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70.0), 16.0f / 9.0f, 0.05f, 1000.0f);

        MarkerMath.ScreenDirection front = MarkerMath.projectToScreen(0.0, 0.0, -6.0, identityView, projection);
        check("a marker straight ahead is on screen", front.onScreen);
        MarkerMath.ScreenDirection upFront = MarkerMath.projectToScreen(0.0, 3.0, -6.0, identityView, projection);
        check("a marker a bit above the crosshair is still on screen", upFront.onScreen);

        MarkerMath.ScreenDirection right = MarkerMath.projectToScreen(40.0, 0.0, -6.0, identityView, projection);
        check("a marker far to the right is off screen", !right.onScreen);
        check("off-screen right points right", Math.abs(right.degrees) < 5.0);

        MarkerMath.ScreenDirection above = MarkerMath.projectToScreen(0.0, 40.0, -6.0, identityView, projection);
        check("off-screen above points up", !above.onScreen && Math.abs(above.degrees - 90.0) < 5.0);

        MarkerMath.ScreenDirection belowLeft = MarkerMath.projectToScreen(-40.0, -40.0, -6.0, identityView, projection);
        check("off-screen down-left points down-left", !belowLeft.onScreen
                && belowLeft.degrees < -90.0 && belowLeft.degrees > -180.0);

        MarkerMath.ScreenDirection behind = MarkerMath.projectToScreen(0.0, 0.0, 6.0, identityView, projection);
        check("a marker behind the player is off screen", !behind.onScreen);
        Matrix4f flipped = new Matrix4f().rotateY((float) Math.PI);
        MarkerMath.ScreenDirection flippedFront = MarkerMath.projectToScreen(0.0, 0.0, -6.0, flipped, projection);
        check("turning around puts the marker behind", !flippedFront.onScreen);

        // 8) 真实掉落路径（C4DropHandler）：不该拿 C4 的队伍拿着它 → 自动掉地上 → 立刻有标记
        List<String> originalTeams = new ArrayList<>(com.qisumei.c4.Config.allowedInstallTeams);
        com.qisumei.c4.Config.allowedInstallTeams = List.of("C4Installers");
        try {
            BlockPos holderPos = new BlockPos(40, 100, 88);
            Platform.prepareSingle(level, holderPos);
            FakePlayer holder = fakePlayer(level, "C4Holder",
                    new Vec3(holderPos.getX() + 0.5, holderPos.getY(), holderPos.getZ() + 0.5));
            holder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(qis4c4.QISC4_ITEM.get()));
            com.qisumei.c4.handler.C4DropHandler.onPlayerTick(
                    new TickEvent.PlayerTickEvent(TickEvent.Phase.END, holder));
            check("a team that may not plant gets the C4 taken away",
                    !holder.getInventory().contains(new ItemStack(qis4c4.QISC4_ITEM.get())));

            Vec3 holderEye = new Vec3(holderPos.getX() + 0.5, holderPos.getY() + 1.0, holderPos.getZ() + 0.5);
            List<ItemEntity> dropped = DroppedC4Locator.find(level, holderEye, 4.0);
            check("the taken C4 really lands on the ground as an item", !dropped.isEmpty());
            check("the dropped item is flagged as a C4 drop",
                    !dropped.isEmpty() && dropped.get(0).getPersistentData().getBoolean("c4_drop"));
        } finally {
            com.qisumei.c4.Config.allowedInstallTeams = originalTeams;
        }

        check("marker config range is inside the allowed window",
                com.qisumei.c4.Config.droppedC4MarkerRange >= 16.0
                        && com.qisumei.c4.Config.droppedC4MarkerRange <= 512.0);
    }

    // ======================= 队伍身份（匪 / 警） =======================

    /**
     * 复刻 CS2 的分工，也是原始模组的设定：
     * <ul>
     *     <li>{@code allowed_install_teams}（匪）只能下包，不能拆包；</li>
     *     <li>{@code allowed_defuse_teams}（警）只能拆包，不能下包。</li>
     * </ul>
     * 任何一侧越界都必须被拒绝，并且给出提示。
     */
    private static void testTeamRoles(ServerLevel level) {
        Scoreboard board = level.getScoreboard();
        PlayerTeam installers = board.addPlayerTeam("C4SelftestInstallers");
        PlayerTeam defusers = board.addPlayerTeam("C4SelftestDefusers");

        List<? extends String> oldInstall = Config.allowedInstallTeams;
        List<? extends String> oldDefuse = Config.allowedDefuseTeams;
        List<? extends String> oldBlocks = Config.allowedBlocks;
        Config.allowedInstallTeams = List.of("C4SelftestInstallers");
        Config.allowedDefuseTeams = List.of("C4SelftestDefusers");
        Config.allowedBlocks = List.of("minecraft:end_stone_bricks");

        FakePlayer terrorist = fakePlayer(level, "C4Terrorist", new Vec3(76.5, 100.0, 76.5));
        FakePlayer cop = fakePlayer(level, "C4Cop", new Vec3(76.5, 100.0, 78.5));
        try {
            board.addPlayerToTeam(terrorist.getScoreboardName(), installers);
            board.addPlayerToTeam(cop.getScoreboardName(), defusers);

            check("planting player resolves to the install team", "C4SelftestInstallers".equals(teamNameOf(terrorist)));
            check("defusing player resolves to the defuse team", "C4SelftestDefusers".equals(teamNameOf(cop)));

            // ---- 拆包：只有拆除队能做 ----
            BlockPos bombPos = new BlockPos(76, 100, 77);
            Platform.prepareSingle(level, bombPos);
            C4Entity c4 = new C4Entity(level, bombPos, true);
            level.addFreshEntity(c4);
            c4.tick();

            terrorist.setPos(76.5, 100.0, 76.5);
            terrorist.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));
            PACKETS.clear();
            check("install team must NOT be able to defuse", !c4.tryStartDefuse(terrorist));
            check("install team gets a refusal message", hasSelfMessage("不允许拆除"));
            check("no defuse is running after that refusal", !c4.isBeingDefused());

            cop.setPos(76.5, 100.0, 78.5);
            cop.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));
            check("defuse team CAN defuse", c4.tryStartDefuse(cop));
            c4.cancelDefuse("自检收尾");
            c4.discard();

            // ---- 下包：只有安装队能做 ----
            BlockPos copSpot = new BlockPos(78, 100, 76);
            Platform.prepareSingle(level, copSpot);
            cop.setPos(copSpot.getX() + 0.5, copSpot.getY(), copSpot.getZ() + 0.5);
            ItemStack copStack = new ItemStack(qis4c4.QISC4_ITEM.get());
            cop.setItemInHand(InteractionHand.MAIN_HAND, copStack);
            PACKETS.clear();
            InteractionResultHolder<ItemStack> denied = copStack.getItem().use(level, cop, InteractionHand.MAIN_HAND);
            check("defuse team must NOT be able to plant", !denied.getResult().consumesAction());
            check("defuse team gets a refusal message", hasSelfMessage("不允许安装"));
            check("nothing was planted by the defuse team", findC4(level, copSpot) == null);

            BlockPos terroristSpot = new BlockPos(80, 100, 76);
            Platform.prepareSingle(level, terroristSpot);
            terrorist.setPos(terroristSpot.getX() + 0.5, terroristSpot.getY(), terroristSpot.getZ() + 0.5);
            ItemStack tStack = new ItemStack(qis4c4.QISC4_ITEM.get());
            terrorist.setItemInHand(InteractionHand.MAIN_HAND, tStack);
            InteractionResultHolder<ItemStack> allowed = tStack.getItem().use(level, terrorist, InteractionHand.MAIN_HAND);
            check("install team CAN start planting", allowed.getResult().consumesAction());
            int duration = tStack.getItem().getUseDuration(tStack);
            for (int remaining = duration - 1; remaining >= 1; remaining--) {
                tStack.getItem().onUseTick(level, terrorist, tStack, remaining);
            }
            C4Entity planted = findC4(level, terroristSpot);
            check("install team really planted a C4", planted != null);
            if (planted != null) {
                planted.discard();
            }

            // ---- 拆除队留空（原始语义：所有人都能拆）时，安装队（匪）依然不能拆 ----
            Config.allowedDefuseTeams = List.of();
            BlockPos soloPos = new BlockPos(82, 100, 76);
            Platform.prepareSingle(level, soloPos);
            C4Entity solo = new C4Entity(level, soloPos, true);
            level.addFreshEntity(solo);
            solo.tick();
            terrorist.setPos(soloPos.getX() + 0.5, soloPos.getY(), soloPos.getZ() + 0.5);
            terrorist.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));
            PACKETS.clear();
            check("install team still cannot defuse when the defuse list is empty",
                    !solo.tryStartDefuse(terrorist));
            check("it is told it belongs to the planting team", hasSelfMessage("安装队"));

            FakePlayer neutral = fakePlayer(level, "C4Neutral",
                    new Vec3(soloPos.getX() + 1.5, soloPos.getY(), soloPos.getZ() + 0.5));
            neutral.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));
            check("a teamless player may still defuse when the list is empty",
                    solo.tryStartDefuse(neutral));
            solo.cancelDefuse("自检收尾");
            solo.discard();

            // ---- 配置读取本身：trim / 去重 / 显式空串 ----
            List<? extends String> normalized = Config.normalizeTeamList("selftest",
                    List.of("  C4SelftestInstallers  ", "C4SelftestInstallers", "", "   "));
            check("config team names are trimmed", normalized.contains("C4SelftestInstallers"));
            check("config team names are deduplicated", normalized.size() == 2);
            check("an explicit empty entry is kept as the teamless marker", normalized.contains(""));

            Config.allowedInstallTeams = normalized;
            BlockPos trimmedSpot = new BlockPos(84, 100, 76);
            Platform.prepareSingle(level, trimmedSpot);
            terrorist.setPos(trimmedSpot.getX() + 0.5, trimmedSpot.getY(), trimmedSpot.getZ() + 0.5);
            ItemStack paddedStack = new ItemStack(qis4c4.QISC4_ITEM.get());
            terrorist.setItemInHand(InteractionHand.MAIN_HAND, paddedStack);
            check("a padded config entry still matches the real team name",
                    paddedStack.getItem().use(level, terrorist, InteractionHand.MAIN_HAND)
                            .getResult().consumesAction());
        } finally {
            Config.allowedInstallTeams = oldInstall;
            Config.allowedDefuseTeams = oldDefuse;
            Config.allowedBlocks = oldBlocks;
            board.removePlayerTeam(installers);
            board.removePlayerTeam(defusers);
        }
    }

    @Nullable
    private static String teamNameOf(Player player) {
        Team team = player.getTeam();
        return team == null ? "" : team.getName();
    }

    // ======================= 音效就是原始那一套 =======================

    /**
     * 校验播放的音效事件、音量、音调与原始模组完全一致：
     * 安装 → {@code c4plant}（语音）+ {@code c4countdown}（40 秒整段，音量 3.0）；
     * 滴答 → 原版 {@code block.note_block.hat}（1.5）；
     * 拆除成功 → {@code ctw}（1.5）；爆炸 → {@code tw}（10.0 / 0.8）。
     * 同时确认没有自己造的单次滴滴声（{@code c4beep}）。
     */
    private static void testSoundIdentity(ServerLevel level) {
        SOUNDS.clear();

        // 1) 安装
        BlockPos pos = new BlockPos(40, 100, 90);
        Platform.prepareSingle(level, pos);
        C4Entity c4 = new C4Entity(level, pos, true);
        level.addFreshEntity(c4);
        c4.tick();

        check("plant plays the original c4plant voice at 1.0", hasSound("c4plant", 1.0F, 1.0F));
        check("plant plays the original c4countdown bed at volume 3.0", hasSound("c4countdown", 3.0F, 1.0F));
        check("no self-made beep sound (c4beep) is played", !hasSoundPath("c4beep"));

        // 2) 滴答声
        SOUNDS.clear();
        c4.setTicksLeft(C4Constants.BEEP_FIRST_TICK + 1);
        for (int i = 0; i < 8; i++) {
            c4.tick();
        }
        check("beeps use the vanilla block.note_block.hat at 1.5", hasSound("block.note_block.hat", 1.5F, 1.0F));

        // 3) 拆除成功（先把引信拉回满值，别让它在拆完之前炸了）
        SOUNDS.clear();
        c4.setTicksLeft(C4Constants.FUSE_TICKS);
        FakePlayer defuser = fakePlayer(level, "C4SoundDefuser",
                new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
        defuser.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));
        check("defuse can start for the sound test", c4.tryStartDefuse(defuser));
        for (int i = 0; i < C4Constants.DEFUSE_SHEARS_TICKS + 5 && !c4.isRemoved(); i++) {
            c4.tick();
        }
        check("defuse completion plays the original ctw at 1.5", hasSound("ctw", 1.5F, 1.0F));

        // 4) 爆炸
        SOUNDS.clear();
        BlockPos boomPos = new BlockPos(40, 100, 94);
        Platform.prepareSingle(level, boomPos);
        C4Entity bomb = new C4Entity(level, boomPos, true);
        level.addFreshEntity(bomb);
        bomb.tick();
        for (int i = 0; i < C4Constants.FUSE_TICKS + 10 && !bomb.isRemoved(); i++) {
            bomb.tick();
        }
        check("explosion plays the original tw at 10.0 / 0.8", hasSound("tw", 10.0F, 0.8F));
        bomb.discard();
    }

    private static boolean hasSound(String path, float volume, float pitch) {
        synchronized (SOUNDS) {
            for (SoundRecord record : SOUNDS) {
                if (record.matches(path, volume, pitch)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasSoundPath(String path) {
        synchronized (SOUNDS) {
            for (SoundRecord record : SOUNDS) {
                if (record.isPath(path)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ======================= NBT =======================

    private static void testPersistence(ServerLevel level) {
        BlockPos pos = new BlockPos(40, 100, 48);
        C4Entity original = new C4Entity(level, pos, true);
        original.tick();
        CompoundTag tag = new CompoundTag();
        original.saveWithoutId(tag);

        C4Entity restored = new C4Entity(level, pos, true);
        restored.load(tag);
        check("NBT keeps remaining fuse", restored.getTicksLeft() == original.getTicksLeft());
        check("NBT keeps position", restored.getC4Pos().equals(pos));
        check("NBT keeps defuse state cleared", restored.getDefuseTotal() == 0);

        original.discard();
        restored.discard();
    }

    /** /summon 会用一个空 NBT 构造实体，必须保证它不会立刻爆炸。 */
    private static void testSummon(ServerLevel level) {
        // 模拟 /summon：工厂构造 + 空 CompoundTag（而不是带 TicksLeft 的存档）
        PACKETS.clear();
        C4Entity fresh = new C4Entity(qis4c4.C4_ENTITY.get(), level);
        fresh.setPos(40.5, 100.0, 56.5);
        CompoundTag empty = new CompoundTag();
        fresh.load(empty);
        check("summoned C4 starts with a full fuse", fresh.getTicksLeft() == C4Constants.FUSE_TICKS);
        level.addFreshEntity(fresh);
        fresh.tick();
        check("summoned C4 does not explode immediately", !fresh.isRemoved()
                && fresh.getTicksLeft() == C4Constants.FUSE_TICKS - 1);
        check("summoned C4 initializes the scoreboard", ModInitializer.getCurrentC4State(server) == 1);
        check("summoned C4 broadcasts the planted notice", hasNotice("已被安装"));
        fresh.discard();
        ModInitializer.updateC4State(server, ModInitializer.STATE_IDLE);
    }

    // ======================= 工具 =======================

    private static FakePlayer fakePlayer(ServerLevel level, String name, Vec3 position) {
        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(UUID.nameUUIDFromBytes(("c4-selftest-" + name).getBytes(StandardCharsets.UTF_8)), name));
        player.setPos(position.x, position.y, position.z);
        player.getAbilities().instabuild = false;
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        // 只加入玩家列表，不注册成世界实体（FakePlayer 没有真实连接）
        if (!level.players().contains(player)) {
            level.players().add(player);
        }
        return player;
    }

    @Nullable
    private static C4Entity findC4(ServerLevel level, BlockPos pos) {
        List<C4Entity> found = level.getEntitiesOfClass(C4Entity.class, new AABB(pos).inflate(2.0));
        return found.isEmpty() ? null : found.get(0);
    }

    private static boolean hasPacket(byte kind) {
        synchronized (PACKETS) {
            for (C4HudPacket packet : PACKETS) {
                if (packet.getKind() == kind) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasNotice(String fragment) {
        synchronized (PACKETS) {
            for (C4HudPacket packet : PACKETS) {
                if (packet.getKind() == C4HudPacket.KIND_NOTICE && packet.getText().contains(fragment)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasDefusePanel(float minProgress) {
        synchronized (PACKETS) {
            for (C4HudPacket packet : PACKETS) {
                if (packet.getKind() == C4HudPacket.KIND_DEFUSE_PANEL && packet.getValue() >= minProgress) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasSelfMessage(String fragment) {
        synchronized (PACKETS) {
            for (C4HudPacket packet : PACKETS) {
                if (packet.getKind() == C4HudPacket.KIND_SELF_MESSAGE && packet.getText().contains(fragment)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int readScore(String objectiveName) {
        var objective = server.getScoreboard().getObjective(objectiveName);
        if (objective == null) {
            return Integer.MIN_VALUE;
        }
        return server.getScoreboard().getOrCreatePlayerScore("#C4", objective).getScore();
    }

    /**
     * 用文档里的节奏独立推演一遍，用来校验实体实际发出的滴滴声数量。
     * <p>
     * 与 {@link C4Entity#tick()} 的差别：实体在“初始化 tick”里已经把 800 减到 799，
     * 之后每个 tick 先减一再判断是否发声，所以这里从 799 开始推演。
     */
    private static int expectedBeepCount() {
        int ticksLeft = C4Constants.FUSE_TICKS - 1;
        int nextBeep = C4Constants.BEEP_FIRST_TICK;
        int count = 0;
        while (ticksLeft > 0) {
            ticksLeft--;
            if (ticksLeft <= nextBeep) {
                count++;
                nextBeep = ticksLeft - C4Constants.beepIntervalTicks(ticksLeft);
            }
        }
        return count;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            PASSED.add(name);
            qis4c4.LOGGER.info("[C4][SelfTest] PASS  {}", name);
        } else {
            FAILED.add(name);
            qis4c4.LOGGER.error("[C4][SelfTest] FAIL  {}", name);
        }
    }

    private static void fail(String reason) {
        FAILED.add(reason);
        qis4c4.LOGGER.error("[C4][SelfTest] FAIL  {}", reason);
    }

    private static void reportAndExit() {
        qis4c4.LOGGER.info("[C4][SelfTest] ===== summary: {} passed, {} failed =====",
                PASSED.size(), FAILED.size());
        for (String failed : FAILED) {
            qis4c4.LOGGER.error("[C4][SelfTest] failed: {}", failed);
        }
        boolean pass = FAILED.isEmpty();
        qis4c4.LOGGER.info("[C4][SelfTest] RESULT: {}", pass ? "PASS" : "FAIL");
        writeReport(pass);
        C4Debug.setListener(null);
        // 注意：不能在这里用 System.exit —— Minecraft 的 shutdown hook 会等待服务器线程，
        // 而服务器线程正卡在 exit 里，最终死锁（表现为“打印了 RESULT 但进程不退出”）。
        Runtime.getRuntime().halt(pass ? 0 : 1);
    }

    private static void writeReport(boolean pass) {
        try (PrintWriter writer = new PrintWriter(new FileWriter("c4-selftest-report.txt"))) {
            writer.println("result=" + (pass ? "PASS" : "FAIL"));
            writer.println("passed=" + PASSED.size());
            writer.println("failed=" + FAILED.size());
            for (String name : PASSED) {
                writer.println("PASS " + name);
            }
            for (String name : FAILED) {
                writer.println("FAIL " + name);
            }
        } catch (IOException e) {
            qis4c4.LOGGER.error("[C4][SelfTest] cannot write report", e);
        }
    }

    /** 搭测试平台，保证 C4 可以安装。 */
    private static final class Platform {
        static void prepare(ServerLevel level, BlockPos origin) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 8; dz++) {
                    level.setBlockAndUpdate(origin.offset(dx, -1, dz), Blocks.END_STONE_BRICKS.defaultBlockState());
                    for (int dy = 0; dy <= 2; dy++) {
                        level.setBlockAndUpdate(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }

        static void prepareSingle(ServerLevel level, BlockPos pos) {
            level.setBlockAndUpdate(pos.below(), Blocks.END_STONE_BRICKS.defaultBlockState());
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
        }
    }
}
