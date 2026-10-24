# C4 Pro

> Minecraft **1.20.1 Forge** 的 C4 炸弹小游戏模组 —— 长按安装、剪刀/空手拆除、40 秒引信、全员音效与 HUD 提示。
>
> 当前版本：**1.7.0**（基于 qisumei 原版 C4 深度重构）

---

## 📥 下载

| 内容 | 地址 |
|------|------|
| 模组 jar（正式下载） | [Releases](https://github.com/456123739/C4-Pro/releases/latest) |
| 下载站（网页版） | https://456123739.github.io/C4-Pro/ |
| 源码 | 本仓库 |

安装：把 `qis4c4-1.7.0.jar` 放进 `mods/` 文件夹。
SHA-256：`b71d7b65b018d958469a5f3e541f6a24cc627303b8dd119ef6d55ff62deb2810`
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

安装位置为玩家脚下（脚下被占用时自动抬高一层）。安装完成的瞬间，**所有人**屏幕上会出现小字提示“C4 已被安装”。

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

新增 `C4HudPacket` + 客户端 `C4Hud`：安装完成后所有玩家屏幕上方显示小字 **“C4 已被安装”**（4 秒）。
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
- 协议版本升到 `v2`；客户端与服务端需同为 1.7.0。
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
./gradlew build            # 产物：build/libs/qis4c4-1.7.0.jar

# 真实专用服务器（已实测通过：66 项检查全部 PASS）
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

**C4 Pro 1.7.0** for Minecraft 1.20.1 (Forge 47.x). Plant by holding right-click for 3.5s, defuse with
shears (5s) or bare hands (10s). The fuse runs for 40s.

Fixes in 1.7.0:

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
