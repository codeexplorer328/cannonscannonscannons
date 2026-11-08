# crate art to a font glyph that lines up with a 3 row chest
import json
import os
import sys

from PIL import Image

src = Image.open(sys.argv[1]).convert('RGBA')
pack = sys.argv[2]
solid = src.getchannel('A').point(lambda v: 255 if v > 200 else 0)
art = src.crop(solid.getbbox())
ns = os.path.join(pack, 'assets', 'cannons')
os.makedirs(os.path.join(ns, 'textures', 'font'), exist_ok=True)
out_path = os.path.join(ns, 'textures', 'font', 'cannon_rack.png')
A = art.load()
AW, AH = art.size

OX, OY = 13, 11
W, H = 202, 220
img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
P = img.load()


def piecewise(anchors, t):
    if t <= anchors[0][0]:
        return anchors[0][1] + (t - anchors[0][0])
    if t >= anchors[-1][0]:
        return anchors[-1][1] + (t - anchors[-1][0])
    for (t0, s0), (t1, s1) in zip(anchors, anchors[1:]):
        if t0 <= t <= t1:
            return s0 + (s1 - s0) * (t - t0) / (t1 - t0)


LID_X = [(6, 19), (169, 179)]
ROW1_X = [(6.5, 18.5), (24.5, 35), (42.5, 53.5), (60.5, 72.5), (78.5, 90.5), (96.5, 108.5),
          (114.5, 126.5), (132.5, 145.5), (150.5, 164.5), (168.5, 180.5)]
ROW2_X = [(6.5, 19.5), (24.5, 36.5), (42.5, 55), (60.5, 73.5), (78.5, 90.5), (96.5, 108.5),
          (114.5, 125.5), (132.5, 143), (150.5, 162.5), (168.5, 179.5)]


def src_row(gy):
    if gy < 16:
        return gy + 11, LID_X
    if gy < 72:
        return 27 + (gy - 16), LID_X
    if gy < 77:
        return 83 + (gy - 72), LID_X
    if gy < 84:
        return 88 + (gy - 77), ROW1_X
    if gy < 136:
        k, r = divmod(gy - 84, 18)
        if r < 16:
            return 132 + r, ROW1_X
        return 130 + (r - 16), ROW1_X
    if gy < 142:
        return 148 + (gy - 136), ROW1_X
    if gy < 158:
        return 154 + (gy - 142), ROW2_X
    return 170 + (gy - 158), ROW1_X


SIDE_L, SIDE_R = 2, 173

for cy in range(H):
    gy = cy - OY
    sy0, xa = src_row(gy)
    for cx in range(W):
        gx = cx - OX
        sy = sy0
        if 77 <= gy < 158 and (gx <= SIDE_L or gx >= SIDE_R):
            sy = 88 + int((gy - 77) * (170 - 88) / (158 - 77))
        if sy < 0 or sy >= AH:
            continue
        sx = int(round(piecewise(xa, gx + 0.5) - 0.5))
        if 0 <= sx < AW:
            P[cx, cy] = A[sx, sy]

K = (95, 0, 0, 255)
L = (77, 0, 0, 255)
E = (97, 96, 96, 255)
I = (111, 110, 110, 255)
Hh = (132, 132, 132, 255)
F = (79, 78, 78, 255)
J = (154, 154, 154, 255)


def put(gx, gy, c):
    P[gx + OX, gy + OY] = c


def fill(x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            put(x, y, c)


def box(x0, y0, w, h):
    for x in range(x0, x0 + w):
        put(x, y0, K)
        put(x, y0 + h - 1, L)
    for y in range(y0, y0 + h):
        put(x0, y, K)
        put(x0 + w - 1, y, L)
    for y in range(y0 + 1, y0 + h - 1):
        c = E if (y - y0) < h // 2 else I
        for x in range(x0 + 1, x0 + w - 1):
            put(x, y, c)


def paste_icon(src_box, gx, gy):
    x0, y0, x1, y1 = src_box
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            c = A[x, y]
            if c[:3] in [(32, 32, 40), (39, 40, 47), (64, 66, 83), (83, 85, 108)]:
                put(gx + x - x0, gy + y - y0, c)


fill(6, 16, 169, 71, I)
for x in range(6, 170):
    put(x, 16, E)
    put(x, 71, E)
for y in range(16, 72):
    put(6, y, E)
    put(169, y, E)
for c in range(10):
    x = 7 + 18 * c
    for y in range(17, 71):
        if x - 1 >= 7:
            put(x - 1, y, F)
        if x <= 168:
            put(x, y, Hh)
for r in range(4):
    y = 17 + 18 * r
    for x in range(7, 169):
        if y - 1 >= 17:
            put(x, y - 1, F)
        if y <= 70:
            put(x, y, Hh)

GUNPOWDER = (42, 33, 51, 42)
GEAR = (77, 33, 86, 42)
BALL = (146, 33, 155, 42)
BIG_GEAR = (94, 50, 103, 59)

for c in range(9):
    for r in range(3):
        x, y = 8 + 18 * c, 18 + 18 * r
        if c in (0, 8):
            continue
        if c == 4 and r != 1:
            continue
        box(x, y, 16, 16)
        icon = {1: GUNPOWDER, 3: GUNPOWDER, 5: BALL, 7: BALL}.get(c)
        if c == 4:
            icon = BIG_GEAR
        if icon:
            paste_icon(icon, x + 3, y + 3)

for c in (0, 8):
    box(8 + 18 * c, 18, 16, 52)

for r in (0, 2):
    x0, y0 = 8 + 18 * 4 - 1, 17 + 18 * r
    sy0 = 28 if r == 0 else 62
    for y in range(18):
        for x in range(18):
            sx = 89 + int(x * 17 / 18)
            sy = sy0 + int(y * 17 / 18)
            put(x0 + x, y0 + y, A[sx, sy])

img.save(out_path)

img = Image.open(out_path).convert('RGBA')
P = img.load()
for gy in range(166):
    for gx in range(176):
        cx, cy = gx + OX, gy + OY
        if P[cx, cy][3] == 255:
            continue
        step = 1 if gx < 88 else -1
        x = cx
        while P[x, cy][3] < 255:
            x += step
        P[cx, cy] = P[x, cy]
img.save(out_path)

os.makedirs(os.path.join(ns, 'font'), exist_ok=True)
with open(os.path.join(ns, 'font', 'gui.json'), 'w') as f:
    json.dump({'providers': [
        {'type': 'space', 'advances': {'\uf000': -(OX + 8)}},
        {'type': 'bitmap', 'file': 'cannons:font/cannon_rack.png',
         'height': H, 'ascent': OY + 13, 'chars': ['\ue000']},
    ]}, f, indent=2)
lang = os.path.join(pack, 'assets', 'minecraft', 'lang')
os.makedirs(lang, exist_ok=True)
with open(os.path.join(lang, 'en_us.json'), 'w') as f:
    json.dump({'container.inventory': ''}, f, indent=2)
print('wrote', out_path)

