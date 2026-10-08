#!/usr/bin/env python3
"""Draws the home-screen icons for the web UI (app/src/main/assets/web/icon-*.png)."""
import math
import os
from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "web")


def icon(size: int) -> Image.Image:
    s = size * 4  # supersample
    img = Image.new("RGB", (s, s), (6, 9, 20))
    d = ImageDraw.Draw(img)
    # Night-sky radial gradient.
    for r in range(s // 2, 0, -4):
        t = r / (s / 2)
        c = (int(10 + 25 * (1 - t)), int(14 + 30 * (1 - t)), int(34 + 60 * (1 - t)))
        d.ellipse([s / 2 - r, s / 2 - r, s / 2 + r, s / 2 + r], fill=c)
    # Arc = bridge / horizon.
    w = s * 0.055
    d.arc([s * 0.16, s * 0.42, s * 0.84, s * 1.10], 200, 340, fill=(255, 92, 92), width=int(w))
    # Four-point star.
    cx, cy, R, r = s * 0.5, s * 0.40, s * 0.22, s * 0.055
    pts = []
    for k in range(8):
        a = math.pi / 4 * k - math.pi / 2
        rad = R if k % 2 == 0 else r
        pts.append((cx + rad * math.cos(a), cy + rad * math.sin(a)))
    glow = Image.new("L", (s, s), 0)
    ImageDraw.Draw(glow).polygon(pts, fill=255)
    glow = glow.filter(ImageFilter.GaussianBlur(s * 0.03))
    img.paste((255, 214, 170), mask=glow.point(lambda v: int(v * 0.55)))
    d.polygon(pts, fill=(255, 244, 228))
    # Small stars.
    for (x, y, rr) in [(0.24, 0.24, 0.012), (0.78, 0.2, 0.014), (0.8, 0.52, 0.009), (0.2, 0.55, 0.008)]:
        d.ellipse([s * (x - rr), s * (y - rr), s * (x + rr), s * (y + rr)], fill=(220, 230, 255))
    return img.resize((size, size), Image.LANCZOS)


for n in (180, 192, 512):
    icon(n).save(os.path.join(OUT, f"icon-{n}.png"), optimize=True)
print("ok")
