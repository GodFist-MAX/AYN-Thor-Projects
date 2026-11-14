"""Renders the CrossBar launcher icon (deep blue tile with a glowing light wave)."""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

SIZE = 512


def render(size=SIZE):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    bg = Image.new("RGBA", (size, size))
    px = bg.load()
    for y in range(size):
        t = y / (size - 1)
        for x in range(size):
            u = x / (size - 1)
            r = int(8 + 20 * u + 10 * t)
            g = int(18 + 40 * (1 - t) + 20 * u)
            b = int(70 + 120 * (1 - t) + 40 * u)
            px[x, y] = (r, g, b, 255)

    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(glow)
    for k in range(9):
        pts = []
        amp = size * (0.07 + 0.012 * k)
        phase = k * 0.35
        for i in range(0, size + 1, 4):
            x = i
            y = size * 0.6 + amp * math.sin(i / size * 2 * math.pi * 0.9 + phase) - k * size * 0.012
            pts.append((x, y))
        alpha = 255 if k == 4 else 120
        d.line(pts, fill=(200, 230, 255, alpha), width=max(2, size // 128))
    blurred = glow.filter(ImageFilter.GaussianBlur(size / 60))
    bg = Image.alpha_composite(bg, blurred)
    bg = Image.alpha_composite(bg, glow)

    # four XMB-style category dots across the top third
    d2 = ImageDraw.Draw(bg)
    cy = size * 0.3
    for i, r in enumerate([0.035, 0.035, 0.06, 0.035]):
        cx = size * (0.24 + i * 0.17)
        rr = size * r
        d2.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=(255, 255, 255, 235 if i == 2 else 150))

    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=size * 0.22, fill=255)
    img.paste(bg, (0, 0), mask)
    return img


def main(out_dir):
    icon = render()
    for folder, px in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                       ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)]:
        os.makedirs(os.path.join(out_dir, folder), exist_ok=True)
        icon.resize((px, px), Image.LANCZOS).save(os.path.join(out_dir, folder, "ic_launcher.png"))
    icon.save(os.path.join(out_dir, "..", "assets", "www", "img", "crossbar.png"))


if __name__ == "__main__":
    main(sys.argv[1])
