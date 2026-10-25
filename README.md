# C4 Pro

> Minecraft **1.20.1 Forge** 的 C4 炸弹小游戏模组 —— 长按安装、剪刀/空手拆除、40 秒引信、全员音效与 HUD 提示。
>
> 当前版本：**1.7.2**（基于 qisumei 原版 C4 深度重构）

---

## 📥 下载

| 内容 | 地址 |
|------|------|
| 模组 jar（正式下载） | [Releases](https://github.com/456123739/C4-Pro/releases/latest) |
| 国内加速下载 | [gh-proxy 镜像](https://gh-proxy.com/https://github.com/456123739/C4-Pro/releases/download/v1.7.2/qis4c4-1.7.2.jar) |
| 下载站（网页版） | https://456123739.github.io/C4-Pro/ |
| 源码 | 本仓库 |

安装：把 `qis4c4-1.7.2.jar` 放进 `mods/` 文件夹。
SHA-256：`001c72d82bf1664d8bb2f767c8c453145f73c8dc76937e64bf410ecf52cb0b3a`
**客户端与服务端都需要安装**（本模组带 HUD 与自定义音效）。

- Minecraft：1.20.1
- Forge：47.x（开发/测试使用 47.4.16）
- 依赖：无

---

## 🎯 玩法

| 操作 | 说明 |
|------|------|
| **安装** | 手持 C4，站在允许的方块上（默认 `minecraft:end_stone_bricks`），**按住右键 3.5 秒** |
| **拆除** | 对准 C4，手持 **剪刀（5 秒）** 或 **空手（10 秒）**，右键开始 |
| **引信** | 安装后 **40 秒** 爆炸；最后 20/10/4 秒有文字提醒 |
| **拆除中断** | 走开、切换手持物、被击杀会中断；**被其他实体小幅度推动不会中断** |
| **清场** | `/c4clear` 一次性清掉所有维度的 C4，未加载区块里的也算（不加载区块、不卡服） |
| **掉落的 C4** | 掉在地上会被**悬浮标志 + 光柱 + 距离**标出来，隔墙可见；捡起即消失 |

安装位置为玩家脚下（脚下被占用时自动抬高一层）。安装完成的瞬间，**所有人**屏幕上会出现小字提示“C4 已被安装”。

---

## 🆕 1.7.2 新增：掉在地上的 C4 会被标出来

C4 掉在地上（被击杀掉包、掉出背包、或者按 Q 扔出去）以后，原版只剩一个 32×32 的小贴图，
隔两格草就找不到了。现在只要场上有“掉落的 C4”，就会有一个**悬浮标志**跟着它：

- **标志**：一块暗色小牌 + 红框 + `C4` 字样，悬在掉落物正上方，下面一道细光柱指出确切落点；
- **隔墙可见**：被方块挡住不会消失，只是变暗（62% 不透明度）——“那边有 C4”仍然一眼可见；
- **距离自适应**：近处约 1.3 格高，远处放大到 2.3 倍并淡出（默认 64 格内可见），
  下方一行 `C4 24m` 直接读距离，跑图不用估；
- **出屏指引**：标志在屏幕外时，屏幕边缘出现红色小菱形 + 距离，告诉你往哪走；
- **捡起即消失**：客户端直接盯“掉落的 C4 物品实体”，一被捡起实体消失、标志同步消失，再掉一次又出现；
- **不添乱**：标记在物品上方（不挡准心，除非你正好盯着它），也不给炸弹本体开透视——
  只对“掉在地上的 C4 物品”生效；纯客户端渲染，**不发数据包、不占服务端性能**。

配置（`config/qis4c4-common.toml`）：

| 键 | 默认 | 说明 |
|----|------|------|
| `dropped_c4_marker` | `true` | 是否显示掉落 C4 的悬浮标记 |
| `dropped_c4_marker_range` | `64.0` | 多远以内显示（16–512 格） |

设计取舍：掉包后找不到是“缺信息”，不是“难度”。所以做成**粗粒度、半透明、带距离**的指引——
你能知道“那边有个 C4”，但看不出朝向，也没法隔着半张地图遥控战局；64 格刚好覆盖一场遭遇战。

验证方式（都跑过）：

- 纯逻辑自检（专用服务器就能跑）：定位器、距离过滤、数量上限、捡起/再掉落、缩放/淡出曲线、屏幕投影方向；
- 真实掉落链路自检：让一个“没有安装权限”的假玩家拿着 C4 tick 一次 → C4 真的掉在地上、带 `c4_drop` 标记；
- 离线预览渲染器 `tools/preview_marker.py`：用同一套参数 + 真实贴图渲染了 8m / 11m / 12m（隔墙）/ 24m / 48m 的成像预览。

---

## 🆕 1.7.1 修复与改进

### 1. `/c4clear` 与数据包 `kill` 清不掉 C4 —— 已修复

旧版有两个洞：

1. `/kill`（数据包常用）走的是 `Entity.kill()`，而 C4 为了防误伤把 `hurt()` 设成不可伤害、
   `isInvulnerable()` 设成 true —— 于是 `/kill` 对它是**空操作**，炸弹继续倒计时。
2. `/c4clear` 只看“已加载的实体”，藏在**未加载区块**里的 C4 根本不在场上，
   清场时扫不到；等玩家走近、区块被加载，它又接着倒计时。

现在的做法（**不加载区块、不轮询，代价与地图大小无关**）：

- C4 第一次 tick 时把自己的坐标登记进维度存档 `C4SavedData`（`world/data/qis4c4_c4.dat`）；
- `/c4clear`：已加载的直接 `forceRemove`；只剩坐标、实体不在场上的，
  往存档里写一条**“死亡名单”（tombstone）**；
- 那个 C4 哪天被区块加载出来，实体在进世界 / 第一次 tick 时查一次名单（一次哈希查表），
  命中就当场自删，**一 tick 都不给它跑**；
- 顺带修掉一个幽灵坐标来源：`/summon` 时 `readAdditionalSaveData` 早于指令摆位置执行，
  旧代码会把 `(0,0,0)` 写进存档，导致清场对着空气清；现在坐标在第一次 tick 才登记。
- `/kill` 现在真正生效（`C4Entity.kill()` 已被重写），`/c4clear` 会同时重置记分板状态与坐标。
- 玩家在清过的位置重新安装 C4 不会误杀：安装前会先销掉该位置的历史名单。

实测（真实专用服务器）：C4 放在 600,100,600（区块未加载）→ 重启服务器 → `/c4clear` →
`已清除 1 个C4！（它们藏在未加载的区块里，区块一加载就会立刻销毁，不会再倒计时）` →
`forceload` 让区块加载 → 日志 `[C4] removed by an earlier /c4clear at 600, 100, 600`，
实体消失、记分板保持 `0`，全程没有恢复倒计时。

### 2. 提示 UI 改回模组原有的独立 UI

1.7.0 里我另写了一套 HUD，其实没必要 —— 模组原本就有一套已经适配好的独立 UI
（不挡准心、不挡视野，就在准心下方）。1.7.1 把它还原并复用：

- “C4 已被安装 / 已被拆除 / 已爆炸” 等提示走原来的 `ClientMessageRenderer`；
- 安装进度、拆除进度（`拆除中 63%` + 细进度条）都在**准心下方**一行显示，无背景框；
- 拆除面板优先级高于普通提示，600ms 无更新自动消失。

### 3. 其它

- 协议版本仍为 `v2`；客户端与服务端需同为 **1.7.1**。
- 自检扩到 **90 项**（新增 `/kill`、`/c4clear`、死亡名单、存档重载等用例）。

---

## 🆕 1.7.0 修复与改进

### 1. 滴滴声“警匪一方听不到”——已修复

根因（三个问题叠加）：

1. **一次性、有距离限制的播放**：旧版用 `level.playSound(..., volume=3.0)` 播放倒计时音轨，
   声音包只会发给 C4 半径约 48 格内的玩家；地图另一头的队伍收不到，中途走进来的玩家也永远听不到。
2. **卸载区块就静音**：实体被卸载/移除时会执行 `stopsound @a * qis4c4:c4countdown`，
   把**所有人**的倒计时音效掐断，而 `COUNTDOWN_SOUND_PLAYED` 又保证它不再重播。
3. **引信会停摆**：实体所在区块没有玩家时会被卸载，实体不再 tick —— 计时和滴滴声一起停。

修复方式：

- 音效改为**服务端逐次广播给同维度的每一位玩家**，且声音包坐标取**玩家自身位置**，
  客户端不会因为距离过远而把音量衰减到 0（远处仍有 `0.65` 的音量下限）。
- 新增单次滴滴声音效 `qis4c4:c4beep`（从原版 40 秒倒计时音轨中截取），
  由服务端按引信进度**加速播放**（1 秒一次 → 0.2 秒一次），任何时刻进服的玩家听到的节奏都与引信同步。
- 彻底删除 `stopsound` 逻辑。
- 安装后给所在区块加 **FORCED 区块票据**，并新增 `C4Manager` 兜底 tick 推进器：
  实测「区块已加载但 `inEntityTickingRange` 仍为 false」时，引信依旧每游戏刻准确推进
  （专用服务器实测：无人值守 4 秒内引信消耗 80 tick，与真实时间一致）。

### 2. 安装后的全员提示

新增 `C4HudPacket` + 客户端提示层：安装完成后所有玩家屏幕显示小字 **“C4 已被安装”**（4 秒）。
拆除/爆炸同样有“C4 已被 XXX 拆除！”“C4 已爆炸！”的小字提示。

### 3. 拆除进度全员可见

拆除进度改为实体同步字段（`DEFUSE_TICKS / DEFUSE_TOTAL / DEFUSER`），客户端直接读取并渲染：

- 所有人都会看到 **“XXX 正在拆除 C4” + 绿色进度条 + 百分比**；
- 拆除开始/中断也会向全维度广播；
- 不再依赖只有拆除者可见的本地计时器。

### 4. 拆除时允许小幅度被推动

旧逻辑用 `player.blockPosition()` 比较，**方块坐标一变就中断**（被队友撞 0.01 格都可能中断）。
现在改为记录拆除起点，按 **1.0 格水平容差 + 1.5 格竖直容差** 判定：

- 被实体碰撞、台阶、轻微顶起推动 → 继续拆除；
- 真正走开（> 1 格）或超过 4.75 格交互距离 → 中断；
- 拆除进度不再用 `System.currentTimeMillis()`，改为 **服务端 tick 推进**，不受卡顿/暂停影响。

### 其它

- 队伍权限判定去掉了会跨玩家串号的 500ms 缓存。
- C4 实体不可被推动、不参与碰撞，避免把炸弹顶走。
- 协议版本升到 `v2`；客户端与服务端需同为 1.7.1。
- 新增服务端自检：`-Dqis4c4.selftest=true`（见下文）。

---

## ⚙️ 配置文件

`config/qis4c4-common.toml`（首次启动生成）：

```toml
# 允许安装 C4 的方块
allowed_blocks = ["minecraft:end_stone_bricks"]
# 允许安装的队伍（空 = 所有队伍）
allowed_install_teams = []
# 允许拆除的队伍（空 = 所有队伍）
allowed_defuse_teams = []
# 死亡掉落 C4（需要 keepInventory 开启）
enable_c4_drop = true
```

---

## 📊 记分板联动

| 计分板 | 含义 |
|--------|------|
| `#C4` 的 `c4_condition` | `0` 空闲 / `1` 已安装 / `2` 已爆炸 / `3` 已拆除 |
| `#C4` 的 `p1score`、`p2score`、`p3score` | C4 的 X / Y / Z 坐标 |

每局开始前请把 `c4_condition` 重置为 `0`。

指令：`/c4clear`（权限等级 2）清除场上所有 C4。

---

## 🧪 构建与自检

```bash
./gradlew build            # 产物：build/libs/qis4c4-1.7.1.jar

# 真实专用服务器（已实测通过：90 项检查全部 PASS）
java -Dqis4c4.selftest=true -Xmx2G @user_jvm_args.txt \
     @libraries/net/minecraftforge/forge/1.20.1-47.4.16/unix_args.txt nogui
# 开发环境
./gradlew runServer -Dqis4c4.selftest=true
```

自检会用真实世界 + FakePlayer 跑完整流程（安装 → 记分板 → 区块票据 → 滴滴声节奏 → 爆炸 →
拆除 → 推动容差 → 双人抢拆 → NBT → 真实 tick 推进），
结果写入 `run/c4-selftest-report.txt`，日志中出现 `[C4][SelfTest] RESULT: PASS` 即通过。

---

## 兼容性

- 只依赖 Forge，不修改原版方块/物品，理论上与绝大多数模组兼容。
- 服务端装、客户端不装 → 会因为缺少自定义声音与 HUD 而体验异常，**请两端都装**。

---

## English (short)

**C4 Pro 1.7.1** for Minecraft 1.20.1 (Forge 47.x). Plant by holding right-click for 3.5s, defuse with
shears (5s) or bare hands (10s). The fuse runs for 40s.

Fixes in 1.7.1:

- `/kill` and `/c4clear` now really remove every C4 — including bombs sitting in unloaded chunks —
  without loading a single chunk: the C4 keeps a persisted coordinate index, and `/c4clear` writes a
  tombstone that the entity consumes on load, so it can never resume the countdown.
- The HUD went back to the mod's own overlay renderer (below the crosshair, never covering it).

Also in 1.7.0:

- **Everyone now hears the countdown beeps**: sounds are broadcast per-player to the whole dimension
  (positioned at the listener, so distance attenuation can never mute it), rhythm is driven by the
  server fuse, and the old `stopsound` call that silenced all players is gone.
- The planted chunk is force-loaded, so the fuse and the beeps keep running even with nobody nearby.
- A small on-screen notice **“C4 已被安装”** is shown to every player after planting.
- The defuse progress bar is now **visible to everyone**, not only to the defuser.
- Small pushes (≤1 block, e.g. entity collisions) no longer interrupt defusing.

Both client and server must install the mod.

---

## 致谢

- 原始 C4 模组作者：**qisumei**
- 移植 / 重构 / 维护：**nimshade**
- 许可证：MIT（见 [LICENSE](LICENSE)）
