#!/usr/bin/env python3
"""Wallpaper pipeline.

  walls.py <dir>   converts <dir>/wall_<key>_<n>.jpg into app/assets/www/wallpapers/<key>/<n>.jpg (1920x1080)
  walls.py --index rewrites app/assets/www/wallpapers/index.json from whatever files are there
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'app', 'assets', 'www', 'wallpapers')
IMG_EXT = ('.jpg', '.jpeg', '.png', '.webp')

# Regions to cover with nearby clean background, in 1280x720 coordinates: (box, source top-left).
FIXES = {
    'wall_ps2_1': [((66, 222, 134, 498), (140, 222))],
}
# 16:9 crops as fractions of the image (left, top, right, bottom), to move bright details away from list text.
CROPS = {
    'wall_switch_2': (0.0, 0.125, 0.75, 0.875),
}


def process(src, key, n):
    from PIL import Image, ImageFilter
    im = Image.open(src).convert('RGB')
    base = os.path.splitext(os.path.basename(src))[0]
    sc = im.width / 1280
    for (x0, y0, x1, y1), (sx, sy) in FIXES.get(base, []):
        box = [round(v * sc) for v in (x0, y0, x1, y1)]
        sx, sy = round(sx * sc), round(sy * sc)
        patch = im.crop((sx, sy, sx + box[2] - box[0], sy + box[3] - box[1]))
        im.paste(patch, (box[0], box[1]))
    if base in CROPS:
        l, t, r, b = CROPS[base]
        im = im.crop((round(l * im.width), round(t * im.height), round(r * im.width), round(b * im.height)))
    if im.size != (1920, 1080):
        im = im.resize((1920, 1080), Image.LANCZOS)
        im = im.filter(ImageFilter.UnsharpMask(radius=1.4, percent=35, threshold=3))
    d = os.path.join(OUT, key)
    os.makedirs(d, exist_ok=True)
    im.save(os.path.join(d, f'{n}.jpg'), 'JPEG', quality=86, optimize=True, progressive=True)


def index():
    idx = {}
    if os.path.isdir(OUT):
        for key in sorted(os.listdir(OUT)):
            d = os.path.join(OUT, key)
            if not os.path.isdir(d):
                continue
            files = sorted((f for f in os.listdir(d) if f.lower().endswith(IMG_EXT)), key=lambda f: (len(f), f))
            if files:
                idx[key] = [f'/app/wallpapers/{key}/{f}' for f in files]
        with open(os.path.join(OUT, 'index.json'), 'w') as f:
            json.dump(idx, f, separators=(',', ':'))
    return idx


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] != '--index':
        src_dir = sys.argv[1]
        for name in sorted(os.listdir(src_dir)):
            m = re.match(r'wall_([a-z0-9]+)_(\d+)\.(jpe?g|png|webp)$', name)
            if m:
                process(os.path.join(src_dir, name), m.group(1), int(m.group(2)))
    print(json.dumps({k: len(v) for k, v in index().items()}))
