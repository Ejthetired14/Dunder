"""Procedural pixel-art office for Dunder. Part 1: palette, helpers, layout, floors, walls."""
import random
from PIL import Image, ImageDraw

T = 32
COLS, ROWS = 22, 52
rng = random.Random(11)


def C(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def shade(c, f):
    return (max(0, min(255, int(c[0] * f))), max(0, min(255, int(c[1] * f))), max(0, min(255, int(c[2] * f))), 255)


# ---------- palette (muted, warm, matches the RPG-style sprites) ----------
OUT = C('#2a2036')
OUT_SOFT = C('#453a55')
WHITE = C('#f4efe6')
PAPER = C('#e9e2d0')
CAP_L, CAP_M, CAP_D = C('#8a7da3'), C('#665a80'), C('#473e5c')
FACE_L, FACE_M, FACE_D = C('#e6d3ad'), C('#d4bf93'), C('#b39e74')
WAIN_L, WAIN_M, WAIN_D = C('#5e9aa0'), C('#477f88'), C('#33606b')
BASEB = C('#7a5638')
WOOD_L, WOOD_M, WOOD_D, WOOD_X = C('#c68b57'), C('#a96f42'), C('#84522e'), C('#5f3a20')
DARKW_L, DARKW_M, DARKW_D = C('#8a5a3c'), C('#6e4529'), C('#4a2d1a')
CARP_BLUE = (C('#6d7f99'), C('#657792'))
CARP_TEAL = (C('#4f8a8c'), C('#488184'))
CARP_GREEN = (C('#557a5c'), C('#4d7054'))
CARP_NAVY = (C('#4b5876'), C('#445170'))
CARP_WINE = (C('#7a4452'), C('#703c4a'))
CARP_TECH = (C('#3f5b78'), C('#395471'))
TILE_A, TILE_B = C('#dfe4e2'), C('#c9d2d2')
HALL_A, HALL_B = C('#d9c7a4'), C('#cdb98f')
METAL_A, METAL_B, METAL_L = C('#454e5c'), C('#3d4553'), C('#59657a')
CONC_A, CONC_B = C('#9a9a96'), C('#8d8d89')
GREEN, GREEN_D, GREEN_L = C('#4f9b4a'), C('#357233'), C('#7cc46b')
RED, RED_D = C('#c24d4d'), C('#8c3030')
AMBER, AMBER_D = C('#f2b84b'), C('#c98a24')
BLUE, BLUE_L, BLUE_D = C('#4f86c6'), C('#8fc0ee'), C('#335d94')
CYAN, CYAN_D = C('#58d3d0'), C('#2f9a9a')
GREY_L, GREY_M, GREY_D = C('#b9bfc8'), C('#8a919c'), C('#585f6b')
BLACK_S = C('#1c1c26')

# ---------- tiny 3x5 pixel font ----------
FONT = {
    'A': '010101111101101', 'B': '110101110101110', 'C': '011100100100011', 'D': '110101101101110',
    'E': '111100110100111', 'F': '111100110100100', 'G': '011100101101011', 'H': '101101111101101',
    'I': '111010010010111', 'J': '001001001101010', 'K': '101101110101101', 'L': '100100100100111',
    'M': '101111111101101', 'N': '111101101101101', 'O': '010101101101010', 'P': '110101110100100',
    'Q': '010101101111011', 'R': '110101110101101', 'S': '011100010001110', 'T': '111010010010010',
    'U': '101101101101111', 'V': '101101101101010', 'W': '101101111111101', 'X': '101101010101101',
    'Y': '101101010010010', 'Z': '111001010100111', '&': '010101010101011', '$': '011110010011110',
    '!': '010010010000010', '-': '000000111000000', ' ': '000000000000000', '0': '111101101101111',
    '1': '010110010010111', '2': '110001010100111', '3': '110001010001110', '?': '110001010000010',
}


def text(dr, x, y, s, color, scale=1, shadow=None):
    cx = x
    for ch in s.upper():
        g = FONT.get(ch, FONT[' '])
        for i, bit in enumerate(g):
            if bit == '1':
                gx, gy = i % 3, i // 3
                if shadow:
                    dr.rectangle([cx + gx * scale + 1, y + gy * scale + 1, cx + gx * scale + scale, y + gy * scale + scale], fill=shadow)
                dr.rectangle([cx + gx * scale, y + gy * scale, cx + gx * scale + scale - 1, y + gy * scale + scale - 1], fill=color)
        cx += 4 * scale


def text_w(s, scale=1):
    return len(s) * 4 * scale - scale


# ---------- primitives ----------
def rect(dr, x0, y0, x1, y1, fill):
    """x1/y1 exclusive."""
    if x1 > x0 and y1 > y0:
        dr.rectangle([x0, y0, x1 - 1, y1 - 1], fill=fill)


def orect(dr, x0, y0, x1, y1, fill, outline=OUT):
    rect(dr, x0, y0, x1, y1, outline)
    rect(dr, x0 + 1, y0 + 1, x1 - 1, y1 - 1, fill)


def bevel(dr, x0, y0, x1, y1, fill, light, dark, outline=OUT):
    """Outlined rect with a lit top/left edge and a shaded bottom/right edge."""
    orect(dr, x0, y0, x1, y1, fill, outline)
    rect(dr, x0 + 1, y0 + 1, x1 - 1, y0 + 2, light)
    rect(dr, x0 + 1, y0 + 1, x0 + 2, y1 - 1, light)
    rect(dr, x0 + 1, y1 - 2, x1 - 1, y1 - 1, dark)
    rect(dr, x1 - 2, y0 + 1, x1 - 1, y1 - 1, dark)


def ellipse_o(dr, x0, y0, x1, y1, fill, outline=OUT):
    dr.ellipse([x0, y0, x1 - 1, y1 - 1], fill=fill, outline=outline)


def speckle(dr, x0, y0, x1, y1, color, n):
    for _ in range(n):
        dr.point((rng.randrange(x0, x1), rng.randrange(y0, y1)), fill=color)


def new(w, h):
    im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    return im, ImageDraw.Draw(im)


# ---------- layout ----------
ROOMS = {
    'boss': (1, 3, 9, 11),
    'meeting': (13, 3, 21, 11),
    'library': (1, 14, 9, 20),
    'web': (13, 14, 21, 20),
    'bullpen': (1, 23, 21, 33),
    'workshop': (1, 36, 9, 42),
    'server': (13, 36, 21, 42),
    'finance': (1, 45, 9, 51),
    'break': (13, 45, 21, 51),
}
SPINE = (10, 3, 12, 51)
DOORS = []
for (ra, rb) in ((8, 9), (17, 18), (39, 40), (48, 49)):
    for r in (ra, rb):
        DOORS += [(9, r), (12, r)]
FLOOR_KIND = {
    'boss': 'wood', 'meeting': ('carpet', CARP_NAVY), 'library': 'darkwood', 'web': ('carpet', CARP_TECH),
    'bullpen': ('carpet', CARP_BLUE), 'workshop': 'concrete', 'server': 'metal',
    'finance': ('carpet', CARP_GREEN), 'break': 'tile', 'spine': 'hall',
}


def room_at(x, y):
    for name, (x0, y0, x1, y1) in ROOMS.items():
        if x0 <= x < x1 and y0 <= y < y1:
            return name
    if SPINE[0] <= x < SPINE[2] and SPINE[1] <= y < SPINE[3]:
        return 'spine'
    if (x, y) in DOORS:
        return room_at(x - 1, y) if x == 9 else room_at(x + 1, y)
    return None


def is_floor(x, y):
    return room_at(x, y) is not None


def kind_of(x, y):
    """cap / face0 (upper wall row) / face1 (lower wall row) / floor"""
    if is_floor(x, y):
        return 'floor'
    if x in (9, 12):
        return 'cap'
    if is_floor(x, y + 1):
        return 'face1'
    if is_floor(x, y + 2):
        return 'face0'
    return 'cap'


def is_face(x, y):
    return kind_of(x, y) in ('face0', 'face1')


# ---------- floors ----------
def floor_tile(im, dr, x, y, kind):
    px, py = x * T, y * T
    if kind in ('wood', 'darkwood'):
        base = (WOOD_L, WOOD_M, WOOD_D) if kind == 'wood' else (DARKW_L, DARKW_M, DARKW_D)
        for i in range(4):
            gr = (py // 8) + i
            c = base[0] if gr % 2 == 0 else base[1]
            rect(dr, px, py + i * 8, px + T, py + i * 8 + 8, c)
            rect(dr, px, py + i * 8 + 7, px + T, py + i * 8 + 8, base[2])
            for gx in range(px, px + T):
                if (gx + gr * 37) % 96 == 0:
                    rect(dr, gx, py + i * 8, gx + 1, py + i * 8 + 8, base[2])
            if rng.random() < 0.35:
                gx0 = px + rng.randrange(2, T - 8)
                rect(dr, gx0, py + i * 8 + 3, gx0 + rng.randrange(3, 7), py + i * 8 + 4, shade(c, 0.9))
    elif isinstance(kind, tuple):
        a, b = kind[1]
        rect(dr, px, py, px + T, py + T, a)
        for _ in range(10):
            fx, fy = px + rng.randrange(1, T - 3), py + rng.randrange(1, T - 2)
            rect(dr, fx, fy, fx + 2, fy + 1, b if rng.random() < 0.5 else shade(a, 1.1))
        rect(dr, px, py + T - 1, px + T, py + T, shade(a, 0.9))
        rect(dr, px + T - 1, py, px + T, py + T, shade(a, 0.9))
    elif kind == 'tile':
        rect(dr, px, py, px + T, py + T, TILE_A)
        for qx in range(2):
            for qy in range(2):
                if (qx + qy + x + y) % 2 == 0:
                    rect(dr, px + qx * 16, py + qy * 16, px + qx * 16 + 16, py + qy * 16 + 16, TILE_B)
        rect(dr, px, py + 15, px + T, py + 16, shade(TILE_B, 0.93))
        rect(dr, px + 15, py, px + 16, py + T, shade(TILE_B, 0.93))
    elif kind == 'hall':
        rect(dr, px, py, px + T, py + T, HALL_A)
        rect(dr, px, py + T - 1, px + T, py + T, HALL_B)
        speckle(dr, px, py, px + T, py + T, HALL_B, 3)
        # runner down the middle of the 2-wide spine
        rx0, rx1 = (px + 8, px + T) if x == SPINE[0] else (px, px + 24)
        rect(dr, rx0, py, rx1, py + T, C('#8d4a55'))
        edge = rx0 if x == SPINE[0] else rx1 - 1
        rect(dr, edge, py, edge + 1, py + T, C('#d9b45c'))
        e2 = (rx0 + 3) if x == SPINE[0] else (rx1 - 4)
        rect(dr, e2, py, e2 + 1, py + T, C('#a8606a'))
        if y % 2 == 0:
            speckle(dr, rx0, py, rx1, py + T, C('#9c5560'), 5)
    elif kind == 'metal':
        rect(dr, px, py, px + T, py + T, METAL_A)
        rect(dr, px + 1, py + 1, px + T - 1, py + T - 1, METAL_B)
        rect(dr, px, py, px + T, py + 1, METAL_L)
        rect(dr, px, py, px + 1, py + T, METAL_L)
        for cx, cy in ((3, 3), (T - 4, 3), (3, T - 4), (T - 4, T - 4)):
            rect(dr, px + cx, py + cy, px + cx + 1, py + cy + 1, METAL_L)
    elif kind == 'concrete':
        rect(dr, px, py, px + T, py + T, CONC_A)
        speckle(dr, px, py, px + T, py + T, CONC_B, 18)
        speckle(dr, px, py, px + T, py + T, shade(CONC_A, 1.08), 8)
        rect(dr, px + T - 1, py, px + T, py + T, CONC_B)
        rect(dr, px, py + T - 1, px + T, py + T, CONC_B)


# ---------- walls ----------
def cap_tile(im, dr, x, y):
    px, py = x * T, y * T
    rect(dr, px, py, px + T, py + T, CAP_M)
    rect(dr, px, py, px + T, py + 3, CAP_L)
    rect(dr, px, py + T - 3, px + T, py + T, CAP_D)
    if is_floor(x - 1, y) or is_face(x - 1, y):
        rect(dr, px, py, px + 2, py + T, CAP_L)
        rect(dr, px + 2, py, px + 3, py + T, OUT_SOFT)
    if is_floor(x + 1, y) or is_face(x + 1, y):
        rect(dr, px + T - 3, py, px + T, py + T, CAP_D)
        rect(dr, px + T - 1, py, px + T, py + T, OUT)
    for i in range(3):
        yy = py + 6 + i * 8
        rect(dr, px + 3, yy, px + T - 3, yy + 1, shade(CAP_M, 0.93))
    if kind_of(x, y + 1) == 'face0':
        rect(dr, px, py + T - 1, px + T, py + T, OUT)


def face_tile(im, dr, x, y, row):
    """row 0 = upper half of the wall face, row 1 = lower half (wainscot + baseboard)."""
    px, py = x * T, y * T
    if row == 0:
        rect(dr, px, py, px + T, py + T, FACE_M)
        rect(dr, px, py, px + T, py + 3, FACE_D)
        rect(dr, px, py + 3, px + T, py + 4, shade(FACE_D, 1.08))
        rect(dr, px, py + 4, px + 1, py + T, FACE_L)
        rect(dr, px + T - 1, py + 4, px + T, py + T, FACE_D)
        speckle(dr, px, py + 5, px + T, py + T, FACE_L, 4)
    else:
        rect(dr, px, py, px + T, py + 12, FACE_M)
        rect(dr, px + T - 1, py, px + T, py + 12, FACE_D)
        rect(dr, px, py, px + 1, py + 12, FACE_L)
        rect(dr, px, py + 12, px + T, py + 14, WAIN_L)
        rect(dr, px, py + 14, px + T, py + 26, WAIN_M)
        rect(dr, px, py + 20, px + T, py + 21, WAIN_D)
        rect(dr, px + T - 1, py + 14, px + T, py + 26, WAIN_D)
        rect(dr, px, py + 26, px + T, py + T, BASEB)
        rect(dr, px, py + T - 1, px + T, py + T, shade(BASEB, 0.7))


def build_base():
    im = Image.new('RGBA', (COLS * T, ROWS * T), OUT)
    dr = ImageDraw.Draw(im)
    for y in range(ROWS):
        for x in range(COLS):
            k = kind_of(x, y)
            if k == 'floor':
                r = room_at(x, y)
                floor_tile(im, dr, x, y, FLOOR_KIND[r])
            elif k == 'face0':
                face_tile(im, dr, x, y, 0)
            elif k == 'face1':
                face_tile(im, dr, x, y, 1)
            else:
                cap_tile(im, dr, x, y)
    # soft shadow under north walls
    for y in range(ROWS):
        for x in range(COLS):
            if kind_of(x, y) == 'floor' and kind_of(x, y - 1) == 'face1':
                for i in range(7):
                    a = 90 - i * 12
                    ov = Image.new('RGBA', (T, 1), (20, 14, 30, max(0, a)))
                    im.alpha_composite(ov, (x * T, y * T + i))
            if kind_of(x, y) == 'floor' and kind_of(x - 1, y) == 'cap':
                for i in range(5):
                    ov = Image.new('RGBA', (1, T), (20, 14, 30, max(0, 70 - i * 14)))
                    im.alpha_composite(ov, (x * T + i, y * T))
    return im, ImageDraw.Draw(im)
