"""Renders the AniDeck icon: two stacked screens (the Thor's top and bottom) with a play mark."""
import os

from PIL import Image, ImageDraw, ImageFilter

SIZE = 512
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def render(size=SIZE):
    s = size / 512
    bg = Image.new("RGBA", (size, size))
    px = bg.load()
    for y in range(size):
        t = y / (size - 1)
        for x in range(size):
            u = x / (size - 1)
            px[x, y] = (int(40 + 70 * u * (1 - t)), int(16 + 20 * t), int(70 + 90 * (1 - t) + 40 * u), 255)

    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size - 1, size - 1), radius=int(112 * s), fill=255)
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    img.paste(bg, (0, 0), mask)

    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    g = ImageDraw.Draw(glow)
    g.rounded_rectangle((86 * s, 92 * s, 426 * s, 290 * s), radius=int(26 * s), fill=(122, 167, 255, 160))
    g.rounded_rectangle((136 * s, 318 * s, 376 * s, 430 * s), radius=int(22 * s), fill=(255, 122, 200, 140))
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(26 * s)))

    d = ImageDraw.Draw(img)
    d.rounded_rectangle((86 * s, 92 * s, 426 * s, 290 * s), radius=int(26 * s), fill=(14, 16, 34, 255),
                        outline=(160, 196, 255, 255), width=max(2, int(10 * s)))
    d.polygon([(226 * s, 140 * s), (226 * s, 242 * s), (316 * s, 191 * s)], fill=(255, 255, 255, 255))
    d.rounded_rectangle((136 * s, 318 * s, 376 * s, 430 * s), radius=int(22 * s), fill=(14, 16, 34, 255),
                        outline=(255, 160, 214, 255), width=max(2, int(9 * s)))
    for i in range(3):
        y = (346 + i * 28) * s
        d.rounded_rectangle((164 * s, y, (300 - i * 40) * s, y + 14 * s), radius=int(7 * s),
                            fill=(255, 200, 232, 230 - i * 50))
    return img


if __name__ == "__main__":
    root = os.path.join(os.path.dirname(__file__), "..", "app", "res")
    big = render()
    for name, px in DENSITIES.items():
        out = os.path.join(root, "mipmap-" + name)
        os.makedirs(out, exist_ok=True)
        big.resize((px, px), Image.LANCZOS).save(os.path.join(out, "ic_launcher.png"))
    big.save("/tmp/anideck_icon.png")
    print("icons written")
