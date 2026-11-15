# 快照 env-2026.11（C4 Pro 1.7.5）

配套仓库：[C4-Pro-Archive](https://github.com/456123739/C4-Pro-Archive) · 模组主仓库：[C4-Pro](https://github.com/456123739/C4-Pro)

## 资产

| 文件 | 大小 | SHA-256 |
|------|------|---------|
| `server-runtime.tar.zst` | 132 MiB | `03274ad0deeb3824aa3a1143b9d96eeb446d0fd01831ebdd01eb01afb3640fdc` |
| `c4pro-test-world-1.7.5.tar.zst` | 9.1 MiB | 见仓库 `SHA256SUMS.txt` |

## server-runtime 是什么

一个**已经装好 Forge 1.20.1-47.4.16 的专用服务器目录**（`libraries/`、`config/`、`mods/`、`eula.txt`、`server.properties`、`ops.json`、`user_jvm_args.txt`），
解压后直接用 JDK 17 启动即可复跑自检：

```bash
cd server-runtime
java -Dqis4c4.selftest=true -Xmx2G @user_jvm_args.txt \
     @libraries/net/minecraftforge/forge/1.20.1-47.4.16/unix_args.txt nogui
```

1.7.5 基线：**162 项自检全 PASS**，`verify_jar.py` **229 项 PASS**。

## c4pro-test-world 是什么

自检跑过的存档（含 40×100 的 END_STONE 平台与 600,100,600 的远景 C4 区块），配合 runtime 可完整复现测试场景；
解压到 `server-runtime/world` 即可。
