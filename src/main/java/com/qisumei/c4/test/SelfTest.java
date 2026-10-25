package com.qisumei.c4.test;

import com.mojang.authlib.GameProfile;
import com.qisumei.c4.C4Constants;
import com.qisumei.c4.C4Debug;
import com.qisumei.c4.ModInitializer;
import com.qisumei.c4.entity.C4Entity;
import com.qisumei.c4.entity.C4EntityTracker;
import com.qisumei.c4.entity.C4SavedData;
import com.qisumei.c4.command.C4ClearCommand;
import com.qisumei.c4.network.C4HudPacket;
import com.qisumei.c4.qis4c4;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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

        // 为第二阶段准备两个 C4：一个在远景无人区，一个在平台附近
        BlockPos far = new BlockPos(600, 100, 600);
        Platform.prepareSingle(level, far);
        farC4 = new C4Entity(level, far, true);
        level.addFreshEntity(farC4);
        farC4.tick();

        BlockPos near = new BlockPos(40, 100, 52);
        timedC4 = new C4Entity(level, near, true);
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
        check("beep interval at full fuse is 1s", C4Constants.beepIntervalTicks(C4Constants.FUSE_TICKS) == 20);
        check("beep interval at half fuse is 0.5s", C4Constants.beepIntervalTicks(C4Constants.FUSE_TICKS / 2) == 10);
        check("beep interval clamps at min", C4Constants.beepIntervalTicks(1) == C4Constants.BEEP_MIN_INTERVAL_TICKS);
        check("beep interval clamps at max", C4Constants.beepIntervalTicks(Integer.MAX_VALUE) == C4Constants.BEEP_MAX_INTERVAL_TICKS);

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
        int nextBeep = C4Constants.FUSE_TICKS - C4Constants.BEEP_FIRST_DELAY_TICKS;
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
