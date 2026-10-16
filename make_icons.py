"""Draws the app's home-screen banner and icon (original design)."""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.abspath(__file__))
FONT = "/usr/share/fonts/opentype/inter/Inter-Bold.otf"
BG = (18, 22, 28)
ACCENT = (32, 201, 151)


def mark(draw, cx, cy, size):
    """A rounded teal TV with a white play triangle."""
    w, h = size, size * 0.72
    draw.rounded_rectangle([cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2],
                           radius=size * 0.16, fill=ACCENT)
    t = size * 0.22
    draw.polygon([(cx - t * 0.8, cy - t), (cx - t * 0.8, cy + t), (cx + t * 1.1, cy)],
                 fill=(255, 255, 255))
    draw.rounded_rectangle([cx - size * 0.18, cy + h / 2 + size * 0.05,
                            cx + size * 0.18, cy + h / 2 + size * 0.11],
                           radius=size * 0.03, fill=ACCENT)


def banner():
    W, H = 640, 360
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT, 66)
    mark_size, gap = 120, 28
    w_clean = d.textlength("Clean", font=font)
    w_tube = d.textlength("Tube", font=font)
    total = mark_size + gap + w_clean + w_tube
    x0 = (W - total) / 2
    mark(d, x0 + mark_size / 2, H / 2 - 8, mark_size)
    tx = x0 + mark_size + gap
    d.text((tx, H / 2), "Clean", font=font, fill=(255, 255, 255), anchor="lm")
    d.text((tx + w_clean, H / 2), "Tube", font=font, fill=ACCENT, anchor="lm")
    out = os.path.join(ROOT, "app/res/drawable-xhdpi/banner.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.save(out, optimize=True)


def icon():
    S = 192
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, S - 1, S - 1], radius=40, fill=BG)
    mark(d, S / 2, S / 2 - 8, 120)
    out = os.path.join(ROOT, "app/res/drawable-xxxhdpi/icon.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.save(out, optimize=True)


if __name__ == "__main__":
    banner()
    icon()
