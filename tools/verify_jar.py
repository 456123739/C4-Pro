#!/usr/bin/env python3
"""C4 Pro 资源/打包完整性检查（直接对着最终 jar 检查，模拟客户端的资源加载路径）。

用法: python3 tools/verify_jar.py build/libs/qis4c4-1.7.1.jar
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile

OK = []
BAD = []


def check(name, cond, detail=""):
    if cond:
        OK.append(name)
        print(f"PASS  {name}")
    else:
        BAD.append(name + (f" :: {detail}" if detail else ""))
        print(f"FAIL  {name} {detail}")


def main(jar_path):
    if not os.path.isfile(jar_path):
        print(f"jar not found: {jar_path}")
        return 1
    zf = zipfile.ZipFile(jar_path)
    names = set(zf.namelist())

    def read_json(entry):
        return json.loads(zf.read(entry).decode("utf-8"))

    # ---------- 基本条目 ----------
    check("jar contains mods.toml", "META-INF/mods.toml" in names)
    check("jar contains pack.mcmeta", "pack.mcmeta" in names)
    check("jar contains sounds.json", "assets/qis4c4/sounds.json" in names)
    check("jar contains lang/zh_cn.json", "assets/qis4c4/lang/zh_cn.json" in names)
    check("jar contains lang/en_us.json", "assets/qis4c4/lang/en_us.json" in names)

    toml = zf.read("META-INF/mods.toml").decode("utf-8")
    check("mods.toml placeholders expanded", "${" not in toml, toml[:200])
    check("mods.toml modId=qis4c4", 'modId="qis4c4"' in toml)
    check("mods.toml version=1.7.1", 'version="1.7.1"' in toml)
    check("mods.toml display name is C4 Pro", 'displayName="C4 Pro"' in toml)

    mcmeta = read_json("pack.mcmeta")
    check("pack.mcmeta pack_format=15", mcmeta.get("pack", {}).get("pack_format") == 15, str(mcmeta))

    # ---------- 声音 ----------
    sounds = read_json("assets/qis4c4/sounds.json")
    lang_zh = read_json("assets/qis4c4/lang/zh_cn.json")
    lang_en = read_json("assets/qis4c4/lang/en_us.json")

    for event, data in sounds.items():
        check(f"sounds.json event '{event}' has sounds", bool(data.get("sounds")))
        for entry in data["sounds"]:
            name = entry["name"] if isinstance(entry, dict) else entry
            ns, path = name.split(":", 1)
            ogg = f"assets/{ns}/sounds/{path}.ogg"
            check(f"sound file exists: {ogg}", ogg in names)
        subtitle = data.get("subtitle")
        if subtitle:
            check(f"subtitle zh: {subtitle}", subtitle in lang_zh)
            check(f"subtitle en: {subtitle}", subtitle in lang_en)

    # 关键：滴滴声必须真的在包里
    check("beep sound registered", "c4beep" in sounds)
    check("beep ogg present", "assets/qis4c4/sounds/c4beep.ogg" in names)
    if "assets/qis4c4/sounds/c4beep.ogg" in names:
        with tempfile.NamedTemporaryFile(suffix=".ogg", delete=False) as tmp:
            tmp.write(zf.read("assets/qis4c4/sounds/c4beep.ogg"))
            tmp_path = tmp.name
        try:
            probe = subprocess.run(
                ["ffprobe", "-v", "error", "-show_entries", "stream=codec_name,channels,sample_rate,duration",
                 "-of", "json", tmp_path],
                capture_output=True, text=True)
            info = json.loads(probe.stdout or "{}")
            streams = info.get("streams", [])
            check("beep ogg is vorbis", bool(streams) and streams[0].get("codec_name") == "vorbis", probe.stderr[:200])
            dur = float(streams[0]["duration"]) if streams else 0
            check("beep ogg duration 0.05~1.0s", 0.05 <= dur <= 1.0, f"{dur}s")
        finally:
            os.unlink(tmp_path)

    # ---------- 语言文件 ----------
    missing_zh = [k for k in lang_en if k not in lang_zh]
    missing_en = [k for k in lang_zh if k not in lang_en]
    check("lang keys parity", not missing_zh and not missing_en, f"zh missing={missing_zh} en missing={missing_en}")
    check("item name present", "item.qis4c4.c4" in lang_zh and "item.qis4c4.c4" in lang_en)

    # ---------- 模型 / 贴图 ----------
    def check_model_textures(model_entry):
        if model_entry not in names:
            check(f"model exists: {model_entry}", False)
            return
        model = read_json(model_entry)
        for key, tex in model.get("textures", {}).items():
            if tex.startswith("#"):
                continue
            ns, path = tex.split(":", 1) if ":" in tex else ("minecraft", tex)
            png = f"assets/{ns}/textures/{path}.png"
            check(f"texture exists for {model_entry}#{key}", png in names, png)

    for entry in ["assets/qis4c4/models/block/c4.json", "assets/qis4c4/models/item/c4.json"]:
        check_model_textures(entry)

    if "assets/qis4c4/blockstates/c4.json" in names:
        states = read_json("assets/qis4c4/blockstates/c4.json")
        models = set()
        for variant in states.get("variants", {}).values():
            if isinstance(variant, dict):
                models.add(variant.get("model"))
            elif isinstance(variant, list):
                models.update(v.get("model") for v in variant)
        for model in models:
            if not model:
                continue
            ns, path = model.split(":", 1) if ":" in model else ("minecraft", model)
            check(f"blockstate model exists: {model}", f"assets/{ns}/models/{path}.json" in names)

    # ---------- 类文件 ----------
    required_classes = [
        "com/qisumei/c4/qis4c4.class",
        "com/qisumei/c4/C4Constants.class",
        "com/qisumei/c4/C4Debug.class",
        "com/qisumei/c4/entity/C4Entity.class",
        "com/qisumei/c4/sound/C4Sounds.class",
        "com/qisumei/c4/network/C4HudPacket.class",
        "com/qisumei/c4/client/ClientMessageRenderer.class",
        "com/qisumei/c4/entity/C4SavedData.class",
        "com/qisumei/c4/handler/C4InteractionHandler.class",
        "com/qisumei/c4/test/SelfTest.class",
    ]
    for cls in required_classes:
        check(f"class present: {cls}", cls in names)

    removed = [
        "com/qisumei/c4/client/C4Hud.class",
        "com/qisumei/c4/network/UIMessagePacket.class",
        "com/qisumei/c4/handler/C4CountdownHandler.class",
    ]
    for cls in removed:
        check(f"obsolete class removed: {cls}", cls not in names)

    # 旧代码里的 stopsound 逻辑必须彻底消失
    found_stopsound = False
    for entry in names:
        if entry.endswith(".class"):
            if b"stopsound" in zf.read(entry):
                found_stopsound = True
                print(f"      (stopsound found in {entry})")
    check("no stopsound hack left in bytecode", not found_stopsound)

    # ---------- 汇总 ----------
    print()
    print(f"passed={len(OK)} failed={len(BAD)}")
    for item in BAD:
        print("  FAILED:", item)
    return 0 if not BAD else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "build/libs/qis4c4-1.7.1.jar"))
