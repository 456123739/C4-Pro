#!/usr/bin/env python3
"""把 stage/ 目录作为一次完整快照推送到 C4-Pro-Archive（Git Data API，绕过 git proxy 限流）。"""
import base64, json, os, subprocess, sys

REPO = "456123739/C4-Pro-Archive"
ROOT = "/home/admin/c4/.archive/stage"
MESSAGE = """archive: env-2026.11 — C4 Pro 1.7.5 构建/测试环境与会话产物

- tools/: 分段生成、资源校验、离线渲染、API 推送脚本
- logs/: 自检与实机测试日志（gzip）+ 1.7.5 自检报告
- preview/: 掉落 C4 标记离线渲染预览
- jars/: 1.7.0 ~ 1.7.5 六个版本
- docs/: 玩法说明书、发布说明、changelog
- source/: 原始模组源码包
- manifest.json / SHA256SUMS.txt：机器可读清单与全量校验
- 大的运行时放在 Release env-2026.11
"""

def gh(args, payload=None):
    proc = subprocess.run(["gh", "api"] + args,
                          input=json.dumps(payload).encode() if payload is not None else None,
                          capture_output=True)
    if proc.returncode != 0:
        print("API ERROR:", proc.stderr.decode()[:2000]); sys.exit(1)
    out = proc.stdout.decode().strip()
    return json.loads(out) if out else {}

entries = []
for dirpath, dirnames, filenames in os.walk(ROOT):
    dirnames[:] = sorted(d for d in dirnames if d not in {".git"})
    for name in sorted(filenames):
        full = os.path.join(dirpath, name)
        rel = os.path.relpath(full, ROOT).replace(os.sep, "/")
        with open(full, "rb") as fh:
            data = fh.read()
        blob = gh(["--method", "POST", f"repos/{REPO}/git/blobs", "--input", "-"],
                  {"content": base64.b64encode(data).decode("ascii"), "encoding": "base64"})
        mode = "100755" if rel.endswith(".sh") else "100644"
        entries.append({"path": rel, "mode": mode, "type": "blob", "sha": blob["sha"]})
        print(f"blob {rel} ({len(data)} B)")

tree = gh(["--method", "POST", f"repos/{REPO}/git/trees", "--input", "-"], {"tree": entries})
commit = gh(["--method", "POST", f"repos/{REPO}/git/commits", "--input", "-"],
            {"message": MESSAGE, "tree": tree["sha"], "parents": []})
print("tree", tree["sha"])

# 首次提交用 PATCH 建 ref 不可行，需要 POST refs
ref = gh(["--method", "POST", f"repos/{REPO}/git/refs", "--input", "-"],
         {"ref": "refs/heads/main", "sha": commit["sha"]})
print("ref main ->", ref.get("ref"), commit["sha"])
