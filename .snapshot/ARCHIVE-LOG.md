# 归档与清理记录（2026-11-15）

本文件记录**这次磁盘清理做了什么**，方便以后回溯。

## 结论

| 项 | 结果 |
|----|------|
| 归档仓库 | https://github.com/456123739/C4-Pro-Archive |
| 快照 | tag / release `env-2026.11`（commit `d2093e8`） |
| 归档内容 | 40 个文件（脚本 / 日志 / 预览图 / 6 个版本 jar / 原始源码包 / 测试存档 / 文档）+ 4 个 Release 大文件 |
| 本地占用 | `/home/admin/c4` **643 MB → 151 MB**（释放 ~492 MB） |
| 校验 | 39 个 blob 与本地 `git hash-object` 全部一致；4 个 Release 资产与 GitHub 服务端 sha256 摘要一致；抽样 6 个文件 sha256 与 `SHA256SUMS.txt` 一致 |

## 删除的东西（均已上传并校验）

| 路径 | 大小 | 现在在哪 |
|------|------|----------|
| `server-test/` | 177 MB | Release `server-runtime.tar.zst`（132 MB，解压即用） |
| `work/`（含 `C4-src.zip` + 解压源码） | 124 MB | 仓库 `source/C4-src.zip` |
| `tools/gradle-8.8-bin.zip` | 132 MB | Release `gradle-8.8-bin.zip` |
| `.archive/server-runtime.tar.zst`（中转） | 132 MB | 已上传后删除 |
| `C4-Pro-1.20.1/build/` | 51 MB | 可再生（`gradle build`，离线 32 秒） |
| `.archive/stage/`（打包中转） | 47 MB | 已推送进仓库 |
| `tools/forge-installer.jar` | 8.5 MB | Release `forge-installer.jar` |
| 根目录 6 个 jar + 8 个日志 + `preview/` | ~3.5 MB | 仓库 `jars/`、`logs/`、`preview/` |

## 保留的东西（按用户要求）

| 路径 | 大小 | 原因 |
|------|------|------|
| `C4-Pro-1.20.1/` | 4.3 MB | 源码主仓（可直接 build） |
| `tools/gradle-8.8/` | 147 MB | 构建工具，保留可直接 `gradle build`（zip 已归档，删了也能恢复） |
| `tools/jdk` | 符号链接 | 指向 `/home/admin/NS/tools/jdk-17.0.20.1+1`，**其他项目共用，未动** |
| `~/.gradle/` | 3.0 GB | 共享构建缓存，**未动**（其中 `forge_gradle` 1.0 GB 只服务本项目，需要时可另删） |

## 顺带修掉的问题

清理后重新构建（离线）发现：`processResources` 的 `expand` 用平台默认字符集重写 `mods.toml`，
在 `LANG=C` 环境下中文描述会变成 `???`。已在 `build.gradle` 显式 `filteringCharset = 'UTF-8'`，
重新构建的 jar 与已发布的 1.7.5 **逐文件一致**（仅 `MANIFEST.MF` 时间戳不同，804073 字节）。

## 复现步骤（以后拉取复用）

```bash
git clone https://github.com/456123739/C4-Pro-Archive.git && cd C4-Pro-Archive
./restore.sh          # 校验 + 下载 + 解压 runtime/world
cd server-runtime && java -Dqis4c4.selftest=true -Xmx2G \
  @user_jvm_args.txt @libraries/net/minecraftforge/forge/1.20.1-47.4.16/unix_args.txt nogui
```

工具链来源（README 第 3 节）：Temurin JDK 17.0.20.1+1、Gradle 8.8、Forge 1.20.1-47.4.16。
1.7.5 自检基线：162 项 PASS / jar 校验 229 项 PASS。
