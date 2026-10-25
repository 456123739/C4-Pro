#!/usr/bin/env python3
"""把 c4countdown.ogg 切成“可以从任意一秒开始播放”的分段。

为什么需要：Minecraft 的声音包无法从音频中途开始播放（C4 下包 20 秒后才走进来的玩家
只能从 0 秒听起，或者什么都听不到）。唯一可行办法是把整段音轨预先切成 1 秒左右的小段，
服务端告诉客户端“现在应该从第 N 段开始”，客户端再一段一段接着播。

切点尽量落在静音区间（音轨是逐渐加快的滴滴声，静音间隙从 0.78s 缩到 0.02s），
这样分段接缝处不会出现“咔哒”声；静音不够的位置加 2ms 淡入淡出兜底。

用法：python3 tools/gen_countdown_segments.py
产物：
  src/main/resources/assets/qis4c4/sounds/cd/cd_NN.ogg
  src/main/java/com/qisumei/c4/sound/CountdownSegments.java
"""
import json
import os
import subprocess
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src/main/resources/assets/qis4c4/sounds/c4countdown.ogg")
OUT_DIR = os.path.join(ROOT, "src/main/resources/assets/qis4c4/sounds/cd")
JAVA = os.path.join(ROOT, "src/main/java/com/qisumei/c4/sound/CountdownSegments.java")
SOUNDS_JSON = os.path.join(ROOT, "src/main/resources/assets/qis4c4/sounds.json")
SR = 44100
FADE_MS = 2
TARGET_STEP_MS = 1000


def decode():
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", SRC, "-ac", "1", "-ar", str(SR), "-f", "f32le", "-"],
        check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)


def silence_centers(x, threshold=0.01, min_ms=40, win_ms=20):
    n = int(SR * win_ms / 1000)
    frames = len(x) // n
    rms = np.array([np.sqrt((x[i * n:(i + 1) * n] ** 2).mean()) for i in range(frames)])
    quiet = rms < threshold
    centers = []
    i = 0
    while i < frames:
        if quiet[i]:
            j = i
            while j < frames and quiet[j]:
                j += 1
            if (j - i) * win_ms >= min_ms:
                centers.append(((i + j) / 2.0) * win_ms / 1000.0)
            i = j
        else:
            i += 1
    return centers


def pick_boundaries(duration, centers):
    """每秒一个目标切点；能吸附到 0.25s 内最近的静音中心就吸附。"""
    bounds = [0.0]
    for k in range(1, int(duration)):
        target = float(k)
        near = [c for c in centers if abs(c - target) <= 0.25]
        if near:
            target = min(near, key=lambda c: abs(c - target))
        if target - bounds[-1] < 0.2:
            target = bounds[-1] + TARGET_STEP_MS / 1000.0
        bounds.append(round(target, 4))
    bounds.append(round(duration, 4))
    return bounds


def cut(x, start, end, fade=True):
    a = int(round(start * SR))
    b = min(int(round(end * SR)), len(x))
    seg = x[a:b].copy()
    if fade and len(seg) > 4:
        n = max(2, int(SR * FADE_MS / 1000))
        n = min(n, len(seg) // 2)
        ramp = np.linspace(0.0, 1.0, n)
        seg[:n] *= ramp
        seg[-n:] *= ramp[::-1]
    return seg


def encode(seg, path):
    tmp = path + ".raw"
    seg.astype(np.float32).tofile(tmp)
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(SR), "-ac", "1", "-i", tmp,
         "-c:a", "libvorbis", "-qscale:a", "5", path],
        check=True, capture_output=True)
    os.unlink(tmp)


def patch_sounds_json(names):
    """把每段注册进 sounds.json（保留其它条目，重新生成 cd_* 部分）。"""
    with open(SOUNDS_JSON, encoding="utf-8") as fh:
        data = json.load(fh)
    for key in [k for k in data if k.startswith("cd_")]:
        del data[key]
    for name in names:
        data[name] = {
            "sounds": [{"name": "qis4c4:cd/" + name, "volume": 1.0, "pitch": 1.0}],
            "subtitle": "subtitles.qis4c4.c4countdown",
        }
    with open(SOUNDS_JSON, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2, ensure_ascii=False)
        fh.write("\n")


def main():
    x = decode()
    duration = len(x) / SR
    centers = silence_centers(x)
    bounds = pick_boundaries(duration, centers)
    os.makedirs(OUT_DIR, exist_ok=True)
    for old in os.listdir(OUT_DIR):
        if old.endswith(".ogg"):
            os.unlink(os.path.join(OUT_DIR, old))

    names, starts, lens = [], [], []
    for i in range(len(bounds) - 1):
        seg = cut(x, bounds[i], bounds[i + 1])
        name = "cd_%02d" % i
        encode(seg, os.path.join(OUT_DIR, name + ".ogg"))
        names.append(name)
        starts.append(int(round(bounds[i] * 1000)))
        lens.append(int(round(bounds[i + 1] * 1000)) - starts[-1])

    lines = [
        "package com.qisumei.c4.sound;",
        "",
        "/**",
        " * {@code c4countdown.ogg} 的分段表（由 {@code tools/gen_countdown_segments.py} 生成，不要手改）。",
        " * <p>",
        " * 每段的起点/长度都是相对整段音轨的真实毫秒数；切点落在静音处，",
        " * 所以客户端从任意一段开始播放都不会有接缝声。",
        " */",
        "public final class CountdownSegments {",
        "",
        "    private CountdownSegments() {",
        "    }",
        "",
        "    public static final int COUNT = %d;" % len(names),
        "",
        "    /** 每段的资源名（{@code qis4c4:cd_NN}）。 */",
        "    public static final String[] NAMES = {",
        "            " + ", ".join('"%s"' % n for n in names),
        "    };",
        "",
        "    /** 每段在整段音轨里的起始毫秒。 */",
        "    public static final int[] START_MS = {",
        "            " + ", ".join(str(s) for s in starts),
        "    };",
        "",
        "    /** 每段时长（毫秒）。 */",
        "    public static final int[] LENGTH_MS = {",
        "            " + ", ".join(str(v) for v in lens),
        "    };",
        "",
        "    /** 整段音轨总长（毫秒）。 */",
        "    public static final int TOTAL_MS = %d;" % (starts[-1] + lens[-1]),
        "",
        "    /** 给定“已经过去多少毫秒”，返回应当从哪一段开始播放。 */",
        "    public static int segmentIndexFor(int elapsedMs) {",
        "        int clamped = Math.max(0, Math.min(TOTAL_MS, elapsedMs));",
        "        for (int i = START_MS.length - 1; i >= 0; i--) {",
        "            if (clamped >= START_MS[i]) {",
        "                return i;",
        "            }",
        "        }",
        "        return 0;",
        "    }",
        "}",
        "",
    ]
    os.makedirs(os.path.dirname(JAVA), exist_ok=True)
    with open(JAVA, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))

    patch_sounds_json(names)

    print("segments: %d, total %.3fs" % (len(names), duration))
    for name, start, length in zip(names, starts, lens):
        print("  %s  %5d ms .. %5d ms  (%4d ms)" % (name, start, start + length, length))


if __name__ == "__main__":
    main()
