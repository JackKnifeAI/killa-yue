#!/usr/bin/env python3
"""Killa Gold's leather as one full-screen picture instead of a repeated tile.

  python3 leather.py bg.png wall.jpg [width=1080] [height=2400]

1. even out the tile's lighting (divide by a heavily blurred copy), so no copy is brighter at one edge;
2. make it wrap seamlessly (cross-fade with a half-shifted copy, only near the edges);
3. lay it across the screen at the grain size the app showed (627 px across a 1080 px screen);
4. one soft light over the whole screen; full brightness, as the tile was (the app darkens the page, dialogs and videos their own way).
"""
import sys
import numpy as np
from PIL import Image, ImageFilter

src, out = sys.argv[1], sys.argv[2]
W = int(sys.argv[3]) if len(sys.argv) > 3 else 1080
H = int(sys.argv[4]) if len(sys.argv) > 4 else 2400

tile = Image.open(src).convert("RGB")
N = tile.width
t = np.asarray(tile, dtype=np.float32)

# 1. flat lighting: the grain divided by its own slow light
# brightness only (one gain per pixel for all three channels), so the colour of the pores is left alone
luma = t @ np.array([0.299, 0.587, 0.114], dtype=np.float32)
light = np.asarray(Image.fromarray(np.clip(luma, 0, 255).astype(np.uint8), "L").filter(ImageFilter.GaussianBlur(N / 8)), dtype=np.float32) + 1.0
flat = t * (light.mean() / light)[..., None]

# 2. seamless wrap: near the edges, fade to the copy shifted by half a tile (whose content wraps there)
shifted = np.roll(flat, (N // 2, N // 2), axis=(0, 1))
feather = N * 0.14
d = np.minimum.outer(np.minimum(np.arange(N), N - 1 - np.arange(N)), np.minimum(np.arange(N), N - 1 - np.arange(N)))
m = np.clip(d / feather, 0, 1)
m = (m * m * (3 - 2 * m))[..., None]  # smoothstep
seamless = flat * m + shifted * (1 - m)

# 3. the grain size the app showed: the tile at 627 px across a 1080 px wide screen
size = round(627 * W / 1080)
st = np.asarray(Image.fromarray(np.clip(seamless, 0, 255).astype(np.uint8)).resize((size, size), Image.LANCZOS), dtype=np.float32)
reps = (-(-H // size), -(-W // size), 1)
canvas = np.tile(st, reps)[:H, :W]

# 4. one soft light, brightest a little above the middle
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
r = np.sqrt(((xx - W / 2) / (W * 0.9)) ** 2 + ((yy - H * 0.36) / (H * 0.75)) ** 2)
glow = 1.0 - 0.22 * np.clip(r, 0, 1) ** 1.6
final = canvas * glow[..., None]  # full brightness, like the tile: the app darkens the page itself (Skin.background)
noise = np.random.default_rng(7).normal(0, 0.6, final.shape[:2]).astype(np.float32)[..., None]  # same dither on all channels: no colour speckle
img = Image.fromarray(np.clip(final + noise, 0, 255).astype(np.uint8))
img.save(out, "JPEG", quality=93, optimize=True, progressive=True)

# report: how even the lighting is across the old tile edges
rows = final.mean(axis=(1, 2))
print(f"{out}: {W}x{H}, tile {size} px; mean {final.mean():.1f}; row brightness spread {rows.max() - rows.min():.2f}")
