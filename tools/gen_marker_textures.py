#!/usr/bin/env python3
"""Generate the "dropped C4" marker textures.

Outputs (relative to the mod resources):
  assets/qis4c4/textures/gui/c4_marker.png  128x128  floating pin badge
  assets/qis4c4/textures/gui/c4_beam.png     32x128  soft vertical light shaft

Everything is drawn at 4x and downsampled, so edges stay smooth in game.
Run:  python3 tools/gen_marker_textures.py
"""
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

SS = 4                      # supersampling factor
ICON = 128                  # final icon size (px)
BEAM_W, BEAM_H = 32, 128    # final beam size (px)
ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "src/main/resources/assets/qis4c4/textures"
OUT = RES / "gui"

ACCENT = (232, 78, 62)      # C4 red
ACCENT_DARK = (150, 38, 30)
PLAQUE = (18, 20, 26)
FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def rounded_mask(size, radius):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return m


def build_icon():
    S = ICON * SS
    canvas = Image.new("RGBA", (S, S), (0, 0, 0, 0))

    # ---- soft outer glow -------------------------------------------------
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32) / SS
    cx, cy = 64.0, 54.0
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / 64.0
    glow_a = np.clip(1.0 - d, 0.0, 1.0) ** 2.2 * 120.0
    glow = np.zeros((S, S, 4), np.uint8)
    glow[..., 0], glow[..., 1], glow[..., 2] = (255, 96, 74)
    glow[..., 3] = glow_a.astype(np.uint8)
    canvas = Image.alpha_composite(canvas, Image.fromarray(glow, "RGBA"))

    draw = ImageDraw.Draw(canvas)

    # ---- pin pointer (triangle under the plaque) -------------------------
    draw.polygon([(52 * SS, 96 * SS), (76 * SS, 96 * SS), (64 * SS, 126 * SS)], fill=ACCENT + (242,))

    # ---- plaque ----------------------------------------------------------
    box = [10 * SS, 8 * SS, 118 * SS, 102 * SS]
    radius = 20 * SS
    plaque = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(plaque).rounded_rectangle(box, radius=radius, fill=PLAQUE + (236,))
    canvas = Image.alpha_composite(canvas, plaque)

    # ---- accent border: 4px final, drawn inside --------------------------
    border = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    bd = ImageDraw.Draw(border)
    for i in range(4):
        bd.rounded_rectangle(
            [box[0] + i * SS, box[1] + i * SS, box[2] - i * SS, box[3] - i * SS],
            radius=max(radius - i * SS, SS),
            outline=ACCENT + (255,),
            width=SS,
        )
    canvas = Image.alpha_composite(canvas, border)

    # ---- C4 字母：小尺寸下比贴图更好认 ------------------------------------
    # 先单独渲染、裁掉空白、再缩放到目标框里，避免 anchor/字宽在不同 PIL 版本上不一致
    glyph_canvas = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glyph_canvas)
    gfont = ImageFont.truetype(FONT_PATH, 180)
    gd.text((8, 8), "C4", font=gfont, fill=(255, 245, 240, 255),
            stroke_width=5, stroke_fill=(120, 24, 18, 255))
    bbox = glyph_canvas.getbbox()
    glyph = glyph_canvas.crop(bbox)

    target_w, target_h = 72 * SS, 58 * SS
    ratio = min(target_w / glyph.width, target_h / glyph.height)
    glyph = glyph.resize((max(int(glyph.width * ratio), 1), max(int(glyph.height * ratio), 1)),
                         Image.LANCZOS)
    canvas.alpha_composite(glyph, (int(64 * SS - glyph.width / 2), int(56 * SS - glyph.height / 2)))

    return canvas.resize((ICON, ICON), Image.LANCZOS)


def build_beam():
    S_W, S_H = BEAM_W * SS, BEAM_H * SS
    yy, xx = np.mgrid[0:S_H, 0:S_W].astype(np.float32)
    u = xx / (S_W - 1)                 # 0..1 left to right
    v = yy / (S_H - 1)                 # 0..1 top to bottom

    side = np.clip(1.0 - (np.abs(u - 0.5) * 2.0) ** 1.7, 0.0, 1.0)
    # brightest at the bottom (right above the item), fading to nothing upward
    vertical = (0.06 + 0.94 * v ** 1.35)
    vertical *= np.clip((1.0 - v) * 6.0, 0.0, 1.0) * 0.35 + 0.65   # keep a soft top
    alpha = np.clip(side * vertical * 205.0, 0, 255)

    beam = np.zeros((S_H, S_W, 4), np.uint8)
    beam[..., 0], beam[..., 1], beam[..., 2] = (255, 126, 96)
    beam[..., 3] = alpha.astype(np.uint8)
    return Image.fromarray(beam, "RGBA").resize((BEAM_W, BEAM_H), Image.LANCZOS)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    icon = build_icon()
    icon.save(OUT / "c4_marker.png")
    beam = build_beam()
    beam.save(OUT / "c4_beam.png")
    print("wrote", OUT / "c4_marker.png", icon.size)
    print("wrote", OUT / "c4_beam.png", beam.size)


if __name__ == "__main__":
    main()
