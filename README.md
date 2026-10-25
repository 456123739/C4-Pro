# C4 Pro

> Minecraft **1.20.1 Forge** 的 C4 炸弹小游戏模组 —— 长按安装、剪刀/空手拆除、40 秒引信、全员音效与 HUD 提示。
>
> 当前版本：**1.7.5**（基于 qisumei 原版 C4 深度重构）

---

## 📥 下载

| 内容 | 地址 |
|------|------|
| 模组 jar（正式下载） | [Releases](https://github.com/456123739/C4-Pro/releases/latest) |
| 国内加速下载 | [gh-proxy 镜像](https://gh-proxy.com/https://github.com/456123739/C4-Pro/releases/download/v1.7.5/qis4c4-1.7.5.jar) |
| 下载站（网页版） | https://456123739.github.io/C4-Pro/ |
| 源码 | 本仓库 |

安装：把 `qis4c4-1.7.5.jar` 放进 `mods/` 文件夹。
SHA-256：`071dde37eb446eb6d647bab9af2f92ae02af497f425a9b7650fa873f4c25849e`
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
| **队伍** | `allowed_install_teams`（匪）只能装、`allowed_defuse_teams`（警）只能拆；查权限用 `/c4roles` |

安装位置为玩家脚下（脚下被占用时自动抬高一层）。安装完成的瞬间，**所有人**屏幕上会出现小字提示“C4 已被安装”。

> 📖 完整玩法与逻辑（含所有数值、判定顺序、UI 位置、与原版的差异清单）见 **[docs/GAMEPLAY.md](docs/GAMEPLAY.md)**。

---

## 🆕 1.7.5：倒计时音效“从中途接上” + 拆除距离/抢拆规则

### 1. 倒计时音效同步（解决“后来的玩家听不到 / 只能从头听”）

40 秒倒计时是一整段音频，Minecraft 的声音包**无法从中间开始播**，所以以前：
下包时不在附近的人，后来走进来只能听到静音；1.7.3 之前甚至会把音轨从头再放一遍。

现在把音轨按**静音点**切成了 39 段（`tools/gen_countdown_segments.py` 生成），服务端只告诉客户端
“已经过去多少毫秒、用多大音量”，客户端从对应那一段开始，一段接一段播下去：

- 下包时在半径内的玩家：从 0 秒开始听；
- **下包 20 秒后才走进来的玩家：从第 20 秒那一段开始听**（听起来和一开始就在场完全一样）；
- 走出半径（+16 格缓冲）后再进来：重新按当前进度接上；
- C4 被拆/爆炸/清除：立刻停掉所有人的倒计时音轨；
- 听觉半径默认 **96 格**（原来是 48 格左右的“能听见”范围），可在配置里改：`countdown_radius`（16–512）。
- 性能：每个玩家每次**进出半径只发一个包**，稳态零开销（每 0.5 秒做一次距离比较，O(玩家数)）。

### 2. 拆除距离：3 格 ± 0.75

- 开始拆除必须在 **3 格内**（超过 3.75 格会提示“距离太远，靠近到 3 格内再拆”）；
- 拆除过程中超出 **3.75 格**判定中断（原因“距离过远”）；
- 被推动 1 格以内仍然不中断（竖直 1.5 格）。

### 3. 抢拆：后来者被拒绝，正在拆的人完全不受影响

- 第二个人来拆：立刻被拒，提示 `§c已经有人在拆除C4！§7（拆除者: <名字>）`；
- 正在拆的人进度、计时、拆除者名字都不变（自检里逐项断言）；
- 同一个人再点一次：什么都不会重置，进度继续。

### 4. 其它

- 自检 **162 项**（新增倒计时同步 8 项、距离/抢拆 5 项）；jar 校验 **229 项**（含 39 段音频逐一存在性检查）。

---

## 🆕 1.7.4：队伍名读取的健壮性（config → 认人，全链路验证）

### 1. 数组里的队伍名会被规范化

`allowed_install_teams` / `allowed_defuse_teams` 读进来之后统一处理：

- **去首尾空格**：`[" terrorists "]` 与 `["terrorists"]` 等价（TOML 里带空格很常见，而
  `player.getTeam().getName()` 是精确比较，少一个空格就永远匹配不上）；
- **去重**、忽略非字符串元素（会打一条 warning）；
- 显式写的空串 `""` 保留，语义是“允许没有队伍的玩家”（和原始模组一致）；
- 空数组仍表示“所有人都可以”。

### 2. 开服时校验队伍名是否真的存在

启动时逐条检查配置里的队伍在记分板上是否存在：

```
[C4] 配置 allowed_install_teams（安装队/匪） 里的队伍 "terrorists" 已存在，权限生效中
[C4] 配置 allowed_defuse_teams（拆除队/警） 里的队伍 "CT-Team" 目前不存在。现有队伍: terrorists, counter-terrorists
[C4] ... 里的队伍 "terrorists" 目前不存在（有一支只差大小写的队伍 "Terrorists"，请把配置改成完全一致）
```

### 3. `/c4roles` 一眼看全

```
安装队（匪）: "terrorists" ✔
拆除队（警）: "CT-Team" §c✘不存在
你的队伍: 控制台 / 无队伍 / 具体队伍名
你的权限: 安装 ✘ · 拆除 ✘
```

### 4. 其它

- 修复 `/c4roles` 由控制台执行时的空指针（控制台没有队伍，现在显示“控制台”）；
- 自检 **147 项**（新增 trim / 去重 / 空串语义 / 带空格配置仍能正确认人 4 项），jar 校验 **69 项**。

---

## 🆕 1.7.3 修复：音效回归原版 + 队伍身份不再串

### 1. 音效全部改回原版资源（不再自己造声音）

1.7.0~1.7.2 里我为了“保证两边都听得见”，从原版 40 秒音轨里截了一小段做成单次滴滴声
（`qis4c4:c4beep`）。这个做法是错的：音色难听，而且把原版那套音效换掉了。1.7.3 已全部删掉，改回原版资源：

| 时机 | 音效 | 音量 / 音调 | 来源 |
|------|------|-------------|------|
| 安装成功 | `qis4c4:c4countdown`（40 秒整段倒计时） | 3.0 / 1.0 | 原版 |
| 安装成功 | `qis4c4:c4plant`（“Bomb has been planted”语音） | 1.0 / 1.0 | 原版资源 |
| 最后两秒滴答 | `minecraft:block.note_block.hat` | 1.5 / 1.0 | 原版 |
| 拆除成功 | `qis4c4:ctw` | 1.5 / 1.0 | 原版 |
| 爆炸 | `qis4c4:tw` | 10.0 / 0.8 | 原版 |

滴答节奏也逐字恢复原版公式：`max(8, (int)(40 * (1 - progress * 0.8)))`
（满引信 2 秒一次，归零前 0.4 秒一次），不再是之前自己推的“1 秒→0.2 秒”。
**唯一保留的改动是投递方式**：仍然逐玩家广播，所以警匪双方、地图两头都能听见同一份原版音效。

自检里新增了音效身份断言（事件 id + 音量 + 音调逐项比对），并以“`c4beep` 不存在”作为通过条件。

### 2. 队伍身份：匪只能装、警只能拆

`config/qis4c4-common.toml` 里两份名单各管一件事，语义和原始模组一致：

- `allowed_install_teams`（匪）：**只能安装**；名单有内容时，不在名单里的人 `use()` 直接失败，物品也不会被消耗。
- `allowed_defuse_teams`（警）：**只能拆除**；名单有内容时，名单外的人右键会被拒绝并收到提示。

1.7.3 补上了原始模组靠“默认全允许”掩盖的一个漏洞：
**当 `allowed_defuse_teams` 留空（= 所有人都能拆）时，已经被列为安装队（匪）的队伍依然不能拆**，
否则就是“匪自己拆自己的包”。想让同一支队伍也能拆，把它同时写进 `allowed_defuse_teams` 即可。
两份名单都留空时，行为和原版完全一样（任何人都能装、也能拆）。

### 3. 排错工具：启动日志 + `/c4roles`

- 启动（以及配置重载）时，日志会打印真正生效的名单：
  `[C4] 队伍配置 — 安装队(匪): terrorists · 拆除队(警): counter-terrorists`
- 游戏内 `/c4roles` 打印：两份名单、你自己所在的队伍、以及你能否安装 / 拆除。
  “匪也能拆包”这类问题，跑一下这条指令就能确认是配置写错还是代码问题。

### 4. 其它

- 自检扩到 **143 项**（新增队伍身份 12 项、音效身份 5 项，并覆盖“拆除队留空时匪仍不能拆”）。
- jar 校验脚本 `tools/verify_jar.py` 扩到 **69 项**（含“不得再出现 c4beep”与四个原版音效必须齐全）。

---

## 🆕 1.7.2 新增：掉在地上的 C4 会被标出来

C4 掉在地上（被击杀掉包、掉出背包、或者按 Q 扔出去）以后，原版只剩一个 32×32 的小贴图，
隔两格草就找不到了。现在只要场上有“掉落的 C4”，就会有一个**悬浮标志**跟着它：

- **标志**：一块暗色小牌 + 红框 + `C4` 字样，悬在掉落物正上方，下面一道细光柱指出确切落点；
- **隔墙可见**：被方块挡住不会消失，只是变暗（62% 不透明度）——“那边有 C4”仍然一眼可见；
- **距离自适应**：近处约 1.3 格高，远处放大到 2.3 倍并淡出（默认 64 格内可见），
  24 格内在小牌上方还有一行 `C4 24m` 直接读距离，跑图不用估；
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
- ~~新增单次滴滴声音效 `qis4c4:c4beep`~~ —— **该改动已在 1.7.3 撤销**，音效恢复为原版的
  `c4countdown` + `c4plant` + `block.note_block.hat`，只是仍然逐玩家广播。
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
./gradlew build            # 产物：build/libs/qis4c4-1.7.5.jar

# 真实专用服务器（已实测通过：162 项检查全部 PASS）
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

**C4 Pro 1.7.5** for Minecraft 1.20.1 (Forge 47.x). Plant by holding right-click for 3.5s, defuse with
shears (5s) or bare hands (10s). The fuse runs for 40s.

Fixes in 1.7.5:

- The 40s countdown track is now cut into 39 silent-seam segments, so a player who walks into the
  hearing radius 20s after the plant hears the countdown **from 20s**, not from 0 (and not silence).
  Radius is configurable (`countdown_radius`, default 96 blocks); leaving the radius stops the track;
  the bomb exploding/being defused stops it for everyone. One packet per player per enter/leave.
- Defusing now needs you within **3 blocks** (start) and cancels beyond **3.75**.
- If someone else is already defusing, the second player is refused
  ("someone is already defusing") and the first defuser's progress is untouched.

Fixes in 1.7.4:

- Team names from the config are trimmed / de-duplicated (and an explicit `""` still means
  "players without a team"), so `[" terrorists "]` behaves exactly like `["terrorists"]`.
- On startup the mod checks every configured team against the scoreboard and logs whether it
  exists (with a "differs only by case" hint), so a silent typo can no longer disable the roles.
- `/c4roles` prints both lists with ✔/✘ existence marks, your own team and your permissions;
  it now also works from the server console.

Fixes in 1.7.3:

- All sounds are the mod's own originals again (`c4countdown` at 3.0, `c4plant` voice,
  vanilla `block.note_block.hat` ticks at the original cadence, `ctw`, `tw`) — the home-made
  `c4beep` sample is gone. Only the delivery changed: per-player broadcast, so both teams hear it.
- Team roles are enforced: the install team can never defuse. If `allowed_defuse_teams` is empty
  ("anyone may defuse"), members of the install team are still refused, so a T cannot defuse its own bomb.
- Startup logs the effective team lists, and `/c4roles` prints them plus your own team and permissions.

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
