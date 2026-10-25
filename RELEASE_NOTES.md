# C4 Pro 1.7.4

**Minecraft 1.20.1 · Forge 47.x**

## 下载

- 正式：https://github.com/456123739/C4-Pro/releases/download/v1.7.4/qis4c4-1.7.4.jar
- 国内加速：https://gh-proxy.com/https://github.com/456123739/C4-Pro/releases/download/v1.7.4/qis4c4-1.7.4.jar
- 下载站：https://456123739.github.io/C4-Pro/

```
文件：qis4c4-1.7.4.jar
SHA-256：7eeff85ba8fb96701acd537ccfd00a7f0ada7141933f0a4b4614df85f029a7c6
```

客户端与服务端都需要安装。

---

## 这个版本修了什么

这一版只做一件事：**把“config 里的队伍名”读对、认对、看得见**。

### 1. 队伍数组会被规范化
- 去首尾空格：`[" terrorists "]` 等价于 `["terrorists"]`（`player.getTeam().getName()` 是精确比较，
  多一个空格就永远匹配不上，这是最阴的一类配置错误）；
- 去重、忽略非字符串元素（打 warning）；
- 显式空串 `""` 保留 = 允许没有队伍的玩家（原版语义）；空数组 = 所有人都可以。

### 2. 开服校验队伍名是否存在
```
[C4] 配置 allowed_install_teams（安装队/匪） 里的队伍 "terrorists" 已存在，权限生效中
[C4] 配置 allowed_defuse_teams（拆除队/警） 里的队伍 "CT-Team" 目前不存在。现有队伍: terrorists, counter-terrorists
```
如果只差大小写，会直接提示：“有一支只差大小写的队伍 "Terrorists"，请把配置改成完全一致”。

### 3. `/c4roles` 一眼看全
```
安装队（匪）: "terrorists" ✔
拆除队（警）: "CT-Team" ✘不存在
你的队伍: 控制台 / 无队伍 / 具体队伍名
你的权限: 安装 ✘ · 拆除 ✘
```

### 4. 顺手修掉
- 控制台执行 `/c4roles` 的空指针。

## 测试

- 专用 Forge 服务器 + 本 jar：**147 项自检全部 PASS**（新增 trim / 去重 / 空串 / 带空格配置仍能认人 4 项）；
- `tools/verify_jar.py`：**69 项 PASS**；
- 实机：`allowed_install_teams = [" terrorists "]` 启动后日志显示为 `"terrorists"`，
  `/c4roles` 两个名单都标 ✔，`/c4clear`、召唤与引信爆炸均正常。

## 升级提示

- 直接替换 jar，配置文件不用改；这次改动只影响“怎么读这份配置”，不改变任何玩法数值
  （安装 70 tick、引信 800 tick、剪刀 100 tick / 空手 200 tick、爆炸 4.0 全部与原版一致）。
