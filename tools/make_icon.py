#!/usr/bin/env python3
"""Build the adaptive launcher icon from a transparent source mark.

Foreground layers are transparent PNGs on a 108dp canvas with the mark
scaled into the 66dp safe zone, because launchers mask the outer ring to
a circle, squircle or teardrop and anything outside it gets cropped.
The background is a flat colour, not an image, so the mask never cuts
through a gradient or a bevel.
"""
from pathlib import Path
from PIL import Image

SRC = Path("tools/src/icon_mark.png")
RES = Path("app/src/main/res")

# 108dp canvas at each density
DENSITIES = {
    "mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432,
}

SAFE = 0.62   # fraction of the canvas the mark may occupy
TILE = (79, 70, 229)   # matches ic_launcher_background


def load_mark():
    if not SRC.exists():
        raise SystemExit(f"missing {SRC} - put the transparent mark there")
    im = Image.open(SRC).convert("RGBA")
    box = im.getbbox()
    if box is None:
        raise SystemExit("source image is fully transparent")
    return im.crop(box)


def place(mark, px, background=None):
    canvas = Image.new("RGBA", (px, px), background or (0, 0, 0, 0))
    target = int(px * SAFE)
    w, h = mark.size
    scale = target / max(w, h)
    m = mark.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.LANCZOS)
    canvas.alpha_composite(m, ((px - m.size[0]) // 2, (px - m.size[1]) // 2))
    return canvas


def main():
    mark = load_mark()
    print(f"source mark {mark.size[0]}x{mark.size[1]}")

    for name, px in DENSITIES.items():
        out = RES / f"mipmap-{name}"
        out.mkdir(parents=True, exist_ok=True)
        place(mark, px).save(out / "ic_launcher_foreground.png")
        print(f"  {name:8} {px}px")

    # Play listing icon: 512x512, opaque, no transparency allowed
    play = Path("tools/play_icon_512.png")
    place(mark, 512, TILE + (255,)).convert("RGB").save(play)
    print(f"  play listing -> {play}")


if __name__ == "__main__":
    main()
