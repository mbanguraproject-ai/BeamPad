#!/usr/bin/env python3
"""Fetch Phosphor icons and emit Android VectorDrawable XML.

Only handles the shape Phosphor actually ships: a 256x256 viewBox with one or
more fill paths, no strokes and no transforms. Anything else is refused rather
than approximated, so a bad conversion never reaches the app silently.
"""
import re
import sys
import urllib.request
from pathlib import Path

BASE = "https://raw.githubusercontent.com/phosphor-icons/core/main/assets"
OUT = Path("app/src/main/res/drawable")
WEIGHTS = {"thin", "light", "regular", "bold", "fill", "duotone"}

TEMPLATE = '''<?xml version="1.0" encoding="utf-8"?>
<!-- Phosphor Icons ({weight}) - MIT License - phosphoricons.com -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="256"
    android:viewportHeight="256"
    android:tint="?attr/colorControlNormal">
{paths}
</vector>
'''

PATH_EL = '''    <path
        android:fillColor="@android:color/white"
        android:pathData="{d}" />'''


def convert(name, weight):
    url = f"{BASE}/{weight}/{name}.svg"
    try:
        svg = urllib.request.urlopen(url, timeout=20).read().decode("utf-8")
    except Exception as e:
        return None, f"fetch failed: {e}"

    if "<svg" not in svg:
        return None, "not an SVG (wrong name or weight?)"

    viewbox = re.search(r'viewBox="([^"]+)"', svg)
    if not viewbox or viewbox.group(1).strip() != "0 0 256 256":
        return None, f"unexpected viewBox: {viewbox.group(1) if viewbox else 'none'}"

    for bad in ("<circle", "<rect", "<polygon", "<line", "<ellipse", "transform="):
        if bad in svg:
            return None, f"contains {bad} - not a pure path icon"

    if "stroke=" in svg and 'stroke="none"' not in svg:
        return None, "stroke-based, cannot convert faithfully"

    ds = re.findall(r'\sd="([^"]+)"', svg)
    if not ds:
        return None, "no path data found"

    paths = "\n".join(PATH_EL.format(d=d) for d in ds)
    return TEMPLATE.format(weight=weight, paths=paths), None


def main():
    if len(sys.argv) < 2:
        print("usage: phosphor.py <weight> <icon-name> [icon-name ...]")
        return 1

    weight = sys.argv[1]
    if weight not in WEIGHTS:
        print(f"weight must be one of: {', '.join(sorted(WEIGHTS))}")
        return 1

    OUT.mkdir(parents=True, exist_ok=True)
    failed = []

    for name in sys.argv[2:]:
        xml, err = convert(name, weight)
        if err:
            print(f"  FAIL  {name}: {err}")
            failed.append(name)
            continue
        dest = OUT / f"ic_{name.replace('-', '_')}.xml"
        dest.write_text(xml)
        print(f"  ok    {name} -> {dest.name}")

    if failed:
        print(f"\n{len(failed)} failed: {', '.join(failed)}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
