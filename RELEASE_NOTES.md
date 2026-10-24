# C4 Pro 1.7.0

Minecraft **1.20.1** · Forge **47.x** · 客户端与服务端都需要安装

## 下载

- `qis4c4-1.7.0.jar`
- sha256：见下方「校验值」
- 安装：放进 `mods/`

## 这个版本修了什么

### 1. 滴滴声：警匪双方在任何位置都能听见

旧实现有三个问题叠加，导致「下包后某一方听不到滴滴声」：

1. `level.playSound(..., volume=3.0)` 是一次性播放，声音包只发给 C4 周围约 48 格内的玩家；
   地图另一头的队伍收不到，中途进入的玩家也永远听不到。
2. 实体卸载/移除时会执行 `stopsound @a * qis4c4:c4countdown`，把**所有人**的倒计时音效一起掐断，
   而 `COUNTDOWN_SOUND_PLAYED` 又保证它不会重播。
3. C4 所在区块在没有玩家时会被卸载，实体停止 tick —— 引信和滴滴声一起「停摆」。

现在的做法：

- 所有 C4 音效由服务端**逐玩家广播**（`ClientboundSoundPacket`），坐标取玩家自身位置，
  客户端不会因为距离过远把音量衰减成 0（远处保留 0.65 音量下限）。
- 新增单次滴滴声 `qis4c4:c4beep`（从原 40 秒倒计时音轨中截取），
  由服务端按引信进度加速播放（约 1 秒一次 → 0.2 秒一次），任何时刻进服都能对上节奏。
- 删除全部 `stopsound` 逻辑。
- 安装后给所在区块加 **FORCED 票据**，并新增 `C4Manager` 作为兜底 tick 推进器：
  实测「区块已加载但 `inEntityTickingRange` 仍为 false」时，引信依旧每游戏刻准确推进。

### 2. 下包后全员提示

安装瞬间，同维度所有玩家屏幕上方显示小字 **「C4 已被安装」**（4 秒，半透明底衬）。
拆除 / 爆炸同样有全员小字提示。

### 3. 拆除进度全员可见

拆除进度改为实体同步字段 + 每 4 tick 一次的进度广播：
**所有人**都能看到「XXX 正在拆除 C4」+ 绿色进度条 + 百分比，不再只有拆除者自己可见。

### 4. 拆除时允许被小幅度推动

旧逻辑用 `player.blockPosition()` 比较，方块坐标一变就中断（被队友撞一下就可能失败）。
现在记录拆除起点，按 **水平 1.0 格 / 竖直 1.5 格** 容差判定：
实体碰撞、走台阶、被顶起都不会中断；真正走开或超过 4.75 格才中断。
拆除计时也从 `System.currentTimeMillis()` 改为服务端 tick。

### 其它修复

- 队伍权限判定的 500ms 缓存会跨玩家串号（A 的队伍被当成 B 的），已移除。
- `/summon qis4c4:c4` 之前会立刻爆炸（空 NBT 把引信覆盖成 0），已修复。
- C4 实体不再可被推动、不参与碰撞。
- 同一位置重复安装会被拒绝。
- 协议版本升到 v2（两端必须同时更新）。

## 测试（真实专用服务器）

在 **Forge 1.20.1-47.4.16 专用服务器 + 正式发布 jar** 上执行：

```
java -Dqis4c4.selftest=true -Xmx2G @user_jvm_args.txt @libraries/.../unix_args.txt nogui
```

```
[C4][SelfTest] ===== summary: 66 passed, 0 failed =====
[C4][SelfTest] RESULT: PASS
[C4][SelfTest] real-tick check: elapsed=80 fuseConsumed=80 beeps=12
[C4][SelfTest] far C4: consumed=80 chunkLoaded=true entityTicking=false
```

覆盖：安装（按住 3.5 秒）→ 记分板状态/坐标 → 区块票据 → 滴滴声节奏与加速 →
40 秒爆炸 → 剪刀拆除 → 推动容差 → 双人抢拆 → 中断 → NBT 存读档 → `/summon` →
真实 tick 循环（无人值守时引信仍在走）。

另外做了 40 秒真实时间实测（不手动 tick）：`/summon` 后 `c4_condition` 立刻为 1，
20 秒后仍为 1，40 秒整爆炸并变为 2。

资源完整性：`tools/verify_jar.py` 52 项全部通过（sounds.json / 全部 ogg / 字幕 key /
模型贴图 / 类文件 / 旧类与 `stopsound` 残留检查）。

## 校验值

```
SHA-256  b71d7b65b018d958469a5f3e541f6a24cc627303b8dd119ef6d55ff62deb2810  qis4c4-1.7.0.jar
```

## 配置与指令

- `config/qis4c4-common.toml`：`allowed_blocks` / `allowed_install_teams` / `allowed_defuse_teams` / `enable_c4_drop`
- 记分板：`#C4` 的 `c4_condition`（0 空闲 / 1 已安装 / 2 已爆炸 / 3 已拆除）、`p1score`/`p2score`/`p3score`（坐标）
- `/c4clear`：清除场上所有 C4（权限 2）

## 许可证

MIT。原始 C4 模组：qisumei；移植与重构：nimshade。
