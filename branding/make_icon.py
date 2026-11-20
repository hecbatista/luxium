"""Generates the Luxium icon: a 32x32 pixel-art iridescent ingot, scaled up.

Pure Python (zlib only), so it runs without Pillow. Writes icon.png (128x128),
icon-512.png and icon.svg next to this script, and copies icon.png into the mod
(mod/src/main/resources/assets/metalshaders/icon.png).

    python3 branding/make_icon.py
"""
import math
import os
import struct
import zlib

N = 32
OUT = os.path.dirname(os.path.abspath(__file__))


def hexrgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def inside(poly, x, y):
    """Point-in-polygon (even-odd) for the pixel centre."""
    px, py = x + 0.5, y + 0.5
    hit = False
    for i in range(len(poly)):
        x1, y1 = poly[i]
        x2, y2 = poly[(i + 1) % len(poly)]
        if (y1 > py) != (y2 > py):
            if px < x1 + (py - y1) * (x2 - x1) / (y2 - y1):
                hit = not hit
    return hit


def hsv(h, s, v):
    i = int(h * 6) % 6
    f = h * 6 - int(h * 6)
    p, q, t = v * (1 - s), v * (1 - f * s), v * (1 - (1 - f) * s)
    r, g, b = [(v, t, p), (q, v, p), (p, v, t), (p, q, v), (t, p, v), (v, p, q)][i]
    return (round(r * 255), round(g * 255), round(b * 255))


# ---- background: dusk gradient in hard pixel bands, plus god rays ----------
BG_STOPS = [hexrgb("#120c2e"), hexrgb("#3a1660"), hexrgb("#8a2a6e"), hexrgb("#e0604a"), hexrgb("#ffb35c")]


def bg(y):
    t = min(y // 2 * 2 / (N - 2), 1.0)  # 2-pixel bands, like Metallum's icon
    seg = t * (len(BG_STOPS) - 1)
    i = min(int(seg), len(BG_STOPS) - 2)
    return mix(BG_STOPS[i], BG_STOPS[i + 1], seg - i)


img = [[bg(y) for x in range(N)] for y in range(N)]

# God rays fanning from a light source above the top-right corner.
SUN = (27.0, -6.0)
for y in range(N):
    for x in range(N):
        ang = math.atan2(y + 0.5 - SUN[1], x + 0.5 - SUN[0])
        dist = math.hypot(x + 0.5 - SUN[0], y + 0.5 - SUN[1])
        beam = math.sin(ang * 9.0)
        if beam > 0.6:
            strength = 0.30 * max(0.0, 1 - dist / 38)
            strength = round(strength * 10) / 10  # quantise so it stays pixel-art
            img[y][x] = mix(img[y][x], hexrgb("#ff9fd8"), strength)

# ---- the ingot ---------------------------------------------------------------
# Isometric box drawn with clean 2:1 pixel-art lines.
L, T, R, B = (4, 12), (12, 8), (27, 15.5), (19, 19.5)
H = 5  # ingot thickness in pixels
TOP = [L, T, R, B]
FRONT = [L, B, (B[0], B[1] + H), (L[0], L[1] + H)]
SIDE = [B, R, (R[0], R[1] + H), (B[0], B[1] + H)]

shape = [[None] * N for _ in range(N)]
for y in range(N):
    for x in range(N):
        if inside(TOP, x, y):
            shape[y][x] = "top"
        elif inside(FRONT, x, y):
            shape[y][x] = "front"
        elif inside(SIDE, x, y):
            shape[y][x] = "side"

OUTLINE = hexrgb("#140d26")
for y in range(N):
    for x in range(N):
        s = shape[y][x]
        if s == "top":
            # Iridescent "oil-slick" bands across the top face (bismuth look).
            u = (x * 0.9 + y * 1.6) / 9.0
            hue = (0.78 + u * 0.55) % 1.0
            img[y][x] = mix(hsv(hue, 0.55, 1.0), (235, 240, 255), 0.25)
        elif s == "front":
            t = (y - 12) / 12
            img[y][x] = mix(hexrgb("#d8dff0"), hexrgb("#7d86a8"), t)
        elif s == "side":
            t = (x - 19) / 8
            img[y][x] = mix(hexrgb("#6e6a9a"), hexrgb("#3d3466"), t)

# Specular rim along the front-top edge.
for y in range(N):
    for x in range(N):
        if shape[y][x] == "front" and y > 0 and shape[y - 1][x] in (None, "top"):
            img[y][x] = (255, 255, 255)
        if shape[y][x] == "side" and y > 0 and shape[y - 1][x] in (None, "top"):
            img[y][x] = hexrgb("#b9b3e6")

# Dark outline around the whole ingot.
outline = []
for y in range(N):
    for x in range(N):
        if shape[y][x] is None:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < N and 0 <= ny < N and shape[ny][nx]:
                    outline.append((x, y))
                    break
for x, y in outline:
    img[y][x] = OUTLINE

# ---- sparkle glint -----------------------------------------------------------
def sparkle(cx, cy, arm):
    img[cy][cx] = (255, 255, 255)
    for k in range(1, arm + 1):
        c = mix((255, 255, 255), (255, 214, 150), (k - 1) / max(arm - 1, 1))
        for dx, dy in ((k, 0), (-k, 0), (0, k), (0, -k)):
            img[cy + dy][cx + dx] = c


sparkle(23, 6, 3)
sparkle(7, 6, 1)


# ---- writers -----------------------------------------------------------------
def write_png(path, scale):
    size = N * scale
    raw = bytearray()
    for y in range(size):
        raw.append(0)
        for x in range(size):
            raw.extend(img[y // scale][x // scale])

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def write_svg(path):
    rects = []
    for y in range(N):
        x = 0
        while x < N:  # merge horizontal runs of the same colour
            c = img[y][x]
            run = 1
            while x + run < N and img[y][x + run] == c:
                run += 1
            rects.append(f'<rect x="{x}" y="{y}" width="{run}" height="1" fill="#{c[0]:02x}{c[1]:02x}{c[2]:02x}"/>')
            x += run
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {N} {N}" width="512" height="512" '
           f'shape-rendering="crispEdges">\n' + "\n".join(rects) + "\n</svg>\n")
    with open(path, "w") as f:
        f.write(svg)


write_png(os.path.join(OUT, "icon.png"), 4)
write_png(os.path.join(OUT, "icon-512.png"), 16)
write_svg(os.path.join(OUT, "icon.svg"))
write_png(os.path.join(OUT, "..", "mod", "src", "main", "resources", "assets", "metalshaders", "icon.png"), 4)
print("wrote icon.png, icon-512.png, icon.svg and the mod icon")
