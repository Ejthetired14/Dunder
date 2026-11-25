import json
import sys
sys.path.insert(0, '.')
from gen_core import *

base, bd = build_base()
OBJECTS = []   # y-sorted sprites: (img, x, y, sortY)
BLOCK = set()  # blocked tiles
SPOTS = []     # (kind, id, access_tx, access_ty, x, y, face)
HOTS = []      # (id, label, blurb, route, x0, y0, x1, y1, alertx, alerty)

UP, DOWN, LEFT, RIGHT = 3, 0, 1, 2
BOOK = [C('#b5483f'), C('#3f7ab5'), C('#d9a441'), C('#4d9a5b'), C('#8a5aa8'), C('#e8e0cc'), C('#c9763a')]


def block(x0, y0, x1, y1):
    for ty in range(y0, y1):
        for tx in range(x0, x1):
            BLOCK.add((tx, ty))


def put(img, x, y, sort=None, base_layer=False):
    if base_layer:
        base.alpha_composite(img, (x, y))
    else:
        OBJECTS.append((img, x, y, sort if sort is not None else y + img.height))


def spot(kind, sid, tx, ty, x, y, face):
    SPOTS.append((kind, sid, tx, ty, int(x), int(y), face))


def hot(hid, label, blurb, route, x0, y0, x1, y1, ax=None, ay=None):
    HOTS.append((hid, label, blurb, route, x0, y0, x1, y1, ax if ax is not None else (x0 + x1) // 2, ay if ay is not None else y0))


def books(dr, x0, y0, x1, y1):
    x = x0
    while x < x1 - 2:
        w = rng.choice([2, 3, 3, 4])
        h = rng.randrange(max(6, (y1 - y0) - 7), y1 - y0)
        c = rng.choice(BOOK)
        rect(dr, x, y1 - h, min(x + w, x1), y1, c)
        rect(dr, x, y1 - h, x + 1, y1, shade(c, 1.2))
        rect(dr, min(x + w, x1) - 1, y1 - h, min(x + w, x1), y1, shade(c, 0.7))
        x += w
        if rng.random() < 0.15:
            x += 2


# ======================= object sprites =======================
def o_desk(top=WOOD_L, front=WOOD_M, edge=WOOD_D):
    im, d = new(64, 44)
    bevel(d, 0, 22, 64, 44, front, shade(front, 1.15), edge)
    rect(d, 21, 24, 22, 42, edge)
    rect(d, 42, 24, 43, 42, edge)
    for hx in (10, 49):
        rect(d, hx, 31, hx + 5, 33, GREY_L)
    bevel(d, 0, 0, 64, 26, top, shade(top, 1.15), shade(top, 0.82))
    rect(d, 2, 22, 62, 24, shade(top, 0.8))
    # monitor (back view) + stand
    bevel(d, 22, 3, 43, 16, GREY_D, GREY_M, BLACK_S)
    rect(d, 31, 9, 34, 11, GREY_L)
    rect(d, 30, 16, 35, 20, BLACK_S)
    rect(d, 27, 20, 38, 22, GREY_D)
    # mug, papers
    orect(d, 50, 7, 56, 14, WHITE)
    rect(d, 56, 9, 58, 12, OUT)
    orect(d, 5, 6, 17, 17, PAPER)
    rect(d, 7, 9, 15, 10, GREY_L)
    rect(d, 7, 12, 13, 13, GREY_L)
    return im


def o_boss_desk():
    im, d = new(128, 58)
    bevel(d, 0, 28, 128, 58, DARKW_M, DARKW_L, DARKW_D)
    for x in (43, 85):
        rect(d, x, 31, x + 1, 56, DARKW_D)
    for hx in (18, 60, 102):
        rect(d, hx, 40, hx + 8, 42, AMBER)
    bevel(d, 0, 0, 128, 32, DARKW_L, shade(DARKW_L, 1.15), DARKW_M)
    rect(d, 2, 28, 126, 30, DARKW_D)
    orect(d, 34, 5, 94, 25, C('#3c6b4f'))                 # green blotter
    bevel(d, 52, 3, 76, 16, GREY_D, GREY_M, BLACK_S)         # monitor back
    rect(d, 62, 16, 66, 20, BLACK_S)
    orect(d, 6, 6, 30, 24, WHITE)                           # inbox tray
    for i in range(3):
        rect(d, 8, 9 + i * 5, 28, 12 + i * 5, shade(PAPER, 0.92 - i * 0.05))
    rect(d, 24, 2, 26, 9, RED)                              # flag on the tray
    rect(d, 26, 2, 30, 5, RED)
    orect(d, 100, 6, 122, 14, AMBER)                        # nameplate
    text(d, 102, 8, 'YOU', OUT)
    orect(d, 98, 17, 108, 25, GREY_D)                       # phone
    return im


def o_plant(h=38):
    im, d = new(26, h)
    bevel(d, 5, h - 13, 21, h, C('#b9653f'), C('#d98a5e'), C('#7c3f28'))
    rect(d, 5, h - 13, 21, h - 10, C('#7c3f28'))
    for (cx, cy, r) in ((13, 9, 8), (6, 16, 6), (20, 16, 6), (13, 20, 6)):
        ellipse_o(d, cx - r, cy - r, cx + r, cy + r, GREEN)
        ellipse_o(d, cx - r + 3, cy - r + 2, cx + 1, cy + 1, GREEN_L, GREEN)
    return im


def o_table():
    im, d = new(128, 58)
    bevel(d, 0, 30, 128, 58, DARKW_M, DARKW_L, DARKW_D)
    bevel(d, 0, 0, 128, 34, C('#7b5a3a'), C('#95704a'), C('#5c3f26'))
    rect(d, 2, 31, 126, 33, DARKW_D)
    for x in (14, 54, 94):
        orect(d, x, 8, x + 18, 18, GREY_L)
        rect(d, x + 2, 10, x + 16, 16, BLUE_L)
    orect(d, 114, 12, 122, 20, WHITE)
    for x in (8, 100):
        orect(d, x, 22, x + 12, 30, PAPER)
    return im


def o_shelf():
    im, d = new(64, 64)
    bevel(d, 0, 0, 64, 64, WOOD_M, WOOD_L, WOOD_D)
    for i in range(3):
        y = 5 + i * 18
        rect(d, 3, y, 61, y + 13, DARKW_D)
        books(d, 4, y + 1, 60, y + 13)
        rect(d, 2, y + 13, 62, y + 16, WOOD_D)
    return im


def o_rack(lit=CYAN):
    im, d = new(32, 66)
    bevel(d, 0, 0, 32, 66, C('#323a48'), C('#4a5568'), C('#1f2530'))
    for i in range(7):
        y = 5 + i * 8
        orect(d, 4, y, 28, y + 6, C('#262d39'), OUT)
        for j in range(4):
            rect(d, 6 + j * 3, y + 2, 7 + j * 3, y + 3, [lit, GREEN, AMBER, lit][(i + j) % 4])
        rect(d, 20, y + 2, 26, y + 4, C('#3a4252'))
    rect(d, 4, 62, 28, 64, C('#262d39'))
    return im


def o_counter(machine=False, sink=False):
    im, d = new(64, 46)
    bevel(d, 0, 20, 64, 46, WHITE, C('#ffffff'), GREY_L)
    rect(d, 31, 22, 33, 44, GREY_L)
    for hx in (22, 40):
        rect(d, hx, 28, hx + 2, 32, GREY_D)
    bevel(d, 0, 0, 64, 24, C('#8fa0a8'), C('#b3c2c8'), C('#66767e'))
    if machine:
        bevel(d, 8, 0, 28, 20, C('#3a3f4b'), C('#59606f'), BLACK_S)
        rect(d, 14, 6, 22, 8, RED)
        orect(d, 14, 12, 20, 18, WHITE)
        orect(d, 38, 10, 46, 18, C('#5c3f26'))
    if sink:
        orect(d, 12, 6, 40, 18, C('#6d8a96'))
        rect(d, 25, 2, 27, 8, GREY_L)
    return im


def o_fridge():
    im, d = new(32, 64)
    bevel(d, 0, 0, 32, 64, WHITE, C('#ffffff'), GREY_L)
    rect(d, 1, 26, 31, 28, GREY_M)
    rect(d, 26, 8, 28, 20, GREY_D)
    rect(d, 26, 33, 28, 45, GREY_D)
    orect(d, 6, 8, 14, 14, AMBER)
    return im


def o_bench():
    im, d = new(96, 42)
    bevel(d, 0, 20, 96, 42, C('#6b6f78'), C('#8c909a'), C('#42454c'))
    for x in (30, 62):
        rect(d, x, 22, x + 1, 40, C('#42454c'))
    bevel(d, 0, 0, 96, 24, WOOD_L, shade(WOOD_L, 1.15), WOOD_M)
    orect(d, 8, 4, 24, 16, C('#d9a441'))      # tool tray
    rect(d, 12, 7, 20, 8, GREY_D)
    orect(d, 40, 6, 54, 18, GREY_M)           # vise
    rect(d, 44, 2, 50, 7, GREY_D)
    orect(d, 68, 8, 90, 14, RED)              # toolbox
    return im


def o_printer():
    im, d = new(64, 66)
    bevel(d, 0, 12, 64, 66, C('#4a5a6a'), C('#6b7f93'), C('#2c3742'))
    bevel(d, 6, 0, 58, 22, C('#374554'), C('#52657a'), BLACK_S)
    orect(d, 10, 22, 54, 52, C('#1f2a35'))      # glass chamber
    ellipse_o(d, 22, 32, 42, 48, AMBER, AMBER_D)
    rect(d, 12, 26, 52, 28, CYAN)
    rect(d, 12, 56, 40, 61, BLACK_S)
    rect(d, 14, 57, 30, 60, GREEN)             # progress bar
    return im


def o_register():
    im, d = new(96, 46)
    bevel(d, 0, 20, 96, 46, C('#e8e0cc'), WHITE, C('#b3a98c'))
    rect(d, 47, 22, 49, 44, C('#b3a98c'))
    bevel(d, 0, 0, 96, 24, C('#8a5a3c'), C('#a8744f'), C('#5a3a26'))
    bevel(d, 10, 2, 40, 18, C('#4b5a50'), C('#6e8275'), BLACK_S)
    rect(d, 14, 5, 36, 10, GREEN_L)
    rect(d, 16, 12, 34, 14, GREY_L)
    for i in range(3):
        orect(d, 60 + i * 11, 10 - i * 2, 68 + i * 11, 18, AMBER)
    return im


def o_safe():
    im, d = new(32, 46)
    bevel(d, 0, 0, 32, 46, C('#4a525e'), C('#6a7686'), C('#2c323b'))
    orect(d, 5, 5, 27, 38, C('#39414c'))
    ellipse_o(d, 10, 14, 22, 26, GREY_L, OUT)
    rect(d, 15, 17, 17, 23, GREY_D)
    rect(d, 2, 40, 6, 46, BLACK_S)
    rect(d, 26, 40, 30, 46, BLACK_S)
    return im


def o_mailcab():
    im, d = new(64, 66)
    bevel(d, 0, 0, 64, 66, C('#9a6a42'), C('#b98657'), C('#6a4529'))
    for r in range(4):
        for c in range(4):
            x, y = 5 + c * 14, 5 + r * 14
            orect(d, x, y, x + 12, y + 11, C('#3a2a20'))
            if rng.random() < 0.7:
                rect(d, x + 2, y + 2 + rng.randrange(0, 3), x + 10, y + 9, rng.choice([WHITE, PAPER, AMBER, BLUE_L]))
    return im


def o_console():
    im, d = new(64, 38)
    bevel(d, 0, 14, 64, 38, C('#3d4a5c'), C('#5a6b82'), C('#252d38'))
    bevel(d, 0, 0, 64, 18, C('#4b5b72'), C('#6c7f99'), C('#2c3644'))
    orect(d, 6, 3, 30, 13, BLACK_S)
    rect(d, 8, 5, 28, 6, CYAN)
    for i in range(10):
        rect(d, 34 + (i % 5) * 5, 4 + (i // 5) * 5, 37 + (i % 5) * 5, 6 + (i // 5) * 5, GREY_L)
    return im


def o_couch_back():
    im, d = new(96, 24)
    bevel(d, 0, 0, 96, 24, C('#8c3d4a'), C('#b0586a'), C('#5f2430'))
    for x in (30, 62):
        rect(d, x, 3, x + 1, 22, C('#5f2430'))
    return im


def o_couch_front():
    im, d = new(96, 26)
    bevel(d, 0, 0, 96, 26, C('#a14756'), C('#c46a7b'), C('#6b2a37'))
    for x in (30, 62):
        rect(d, x, 3, x + 1, 22, C('#6b2a37'))
    bevel(d, 0, -2, 10, 26, C('#8c3d4a'), C('#b0586a'), C('#5f2430'))
    bevel(d, 86, -2, 96, 26, C('#8c3d4a'), C('#b0586a'), C('#5f2430'))
    return im


def o_copier():
    im, d = new(48, 44)
    bevel(d, 0, 10, 48, 44, C('#cfd3d8'), WHITE, GREY_M)
    bevel(d, 2, 0, 46, 14, C('#8a919c'), GREY_L, GREY_D)
    orect(d, 30, 18, 42, 24, C('#3c6b4f'))
    rect(d, 6, 30, 40, 32, GREY_D)
    return im


# ======================= base-layer decor =======================
def chair(px, py, color=C('#3d4a68')):
    """Swivel chair seen from the front. Drawn in the base layer, the agent sits on top."""
    im, d = new(26, 26)
    ellipse_o(d, 3, 20, 23, 26, shade(OUT, 1.2))
    bevel(d, 4, 0, 22, 12, color, shade(color, 1.3), shade(color, 0.7))
    bevel(d, 3, 10, 23, 22, shade(color, 1.1), shade(color, 1.4), shade(color, 0.7))
    put(im, px, py, base_layer=True)


def rug(x0, y0, x1, y1, a, b):
    d = bd
    orect(d, x0, y0, x1, y1, a, shade(a, 0.55))
    rect(d, x0 + 4, y0 + 4, x1 - 4, y1 - 4, b)
    rect(d, x0 + 6, y0 + 6, x1 - 6, y1 - 6, a)
    for x in range(x0 + 8, x1 - 8, 8):
        rect(d, x, y0 + 8, x + 2, y0 + 10, b)
        rect(d, x, y1 - 10, x + 2, y1 - 8, b)


def window(x, y, w=44, h=40):
    d = bd
    orect(d, x, y, x + w, y + h, C('#6a4a2e'))
    rect(d, x + 3, y + 3, x + w - 3, y + h - 3, C('#9fd3f0'))
    rect(d, x + 3, y + 3, x + w - 3, y + 12, C('#c7e8f8'))
    rect(d, x + w // 2 - 1, y + 3, x + w // 2 + 1, y + h - 3, C('#6a4a2e'))
    rect(d, x + 3, y + h // 2, x + w - 3, y + h // 2 + 2, C('#6a4a2e'))
    rect(d, x + 4, y + 4, x + 9, y + 6, WHITE)


def plaque(x, y, label, color=AMBER):
    w = text_w(label, 2) + 10
    orect(bd, x - w // 2, y, x + w // 2, y + 16, color)
    text(bd, x - w // 2 + 5, y + 3, label, OUT, 2)


def frame(x, y, w, h, inner):
    orect(bd, x, y, x + w, y + h, C('#6a4a2e'))
    rect(bd, x + 3, y + 3, x + w - 3, y + h - 3, inner)


def whiteboard(x, y, w, h):
    orect(bd, x, y, x + w, y + h, GREY_L)
    rect(bd, x + 3, y + 3, x + w - 3, y + h - 3, WHITE)
    for i, c in enumerate((RED, BLUE, GREEN, AMBER)):
        rect(bd, x + 8 + i * 22, y + 8, x + 24 + i * 22, y + 20, c)
        rect(bd, x + 14 + i * 22, y + 22, x + 16 + i * 22, y + 28, OUT_SOFT)
    rect(bd, x + 6, y + h - 12, x + w - 40, y + h - 10, GREY_M)
    rect(bd, x + 6, y + h - 8, x + w - 60, y + h - 6, GREY_M)


def cork(x, y, w, h):
    orect(bd, x, y, x + w, y + h, C('#6a4a2e'))
    rect(bd, x + 3, y + 3, x + w - 3, y + h - 3, C('#c9965f'))
    speckle(bd, x + 3, y + 3, x + w - 3, y + h - 3, C('#a87644'), 40)
    for i in range(6):
        nx, ny = x + 8 + (i % 3) * 24, y + 8 + (i // 3) * 22
        c = [AMBER, C('#e89aa8'), BLUE_L, GREEN_L, WHITE, C('#e8b27a')][i]
        rect(bd, nx, ny, nx + 14, ny + 14, c)
        rect(bd, nx + 6, ny - 1, nx + 8, ny + 1, RED)
        rect(bd, nx + 2, ny + 5, nx + 11, ny + 6, shade(c, 0.7))


def clock(x, y):
    ellipse_o(bd, x, y, x + 20, y + 20, WHITE)
    rect(bd, x + 10, y + 4, x + 11, y + 11, OUT)
    rect(bd, x + 10, y + 10, x + 15, y + 11, OUT)


def wall_screen(x, y):
    orect(bd, x, y, x + 56, y + 36, BLACK_S)
    rect(bd, x + 3, y + 3, x + 53, y + 33, C('#16304d'))
    ellipse_o(bd, x + 18, y + 7, x + 38, y + 27, BLUE, CYAN)
    rect(bd, x + 18, y + 16, x + 38, y + 17, CYAN)
    rect(bd, x + 27, y + 7, x + 28, y + 27, CYAN)
    rect(bd, x + 5, y + 5, x + 14, y + 6, GREY_L)


# ======================= layout =======================
T2 = T
def face_y(room):
    return (ROOMS[room][1] - 2) * T


def facebox(room):
    return ROOMS[room][0] * T, ROOMS[room][2] * T, face_y(room)


# ---- BOSS OFFICE ----
fx0, fx1, fy = facebox('boss')
window(fx0 + 20, fy + 8)
frame(fx0 + 86, fy + 10, 28, 34, C('#e8dcc0'))
frame(fx0 + 124, fy + 10, 28, 34, C('#c9d9c0'))
cx = fx0 + 8 * 32 - 40
orect(bd, cx, fy + 6, cx + 22, fy + 46, C('#2a4a80'))            # flag
rect(bd, cx + 4, fy + 12, cx + 18, fy + 14, WHITE)
rect(bd, cx + 4, fy + 18, cx + 18, fy + 20, WHITE)
plaque(fx0 + 4 * 32, fy + 44, 'BOSS')
rug(3 * 32 - 8, 6 * 32 + 12, 7 * 32 + 8, 9 * 32 + 8, C('#8c3d4a'), C('#d9b45c'))
chair(4 * 32 + 53, 3 * 32 + 4, C('#7a3a2a'))
put(o_boss_desk(), 3 * 32, 4 * 32)
block(3, 4, 7, 6)
put(o_plant(), 7 * 32 + 3, 3 * 32 - 2)
block(7, 3, 8, 4)
put(o_plant(), 1 * 32 + 3, 3 * 32 - 2)
block(1, 3, 2, 4)
bd_sofa = (o_couch_back(), o_couch_front())
put(bd_sofa[0], 1 * 32, 9 * 32 - 2, sort=9 * 32 + 12)
put(bd_sofa[1], 1 * 32, 9 * 32 + 20, sort=9 * 32 + 46)
block(1, 9, 4, 11)
for i, tx in enumerate((3, 4, 5, 6)):
    spot('QUEUE', f'queue_{i + 1}', tx, 6, tx * 32 + 16, 6 * 32 + 26, UP)
spot('QUEUE', 'queue_5', 5, 7, 5 * 32 + 16, 7 * 32 + 26, UP)
# Platform Engineer's dedicated desk behind the Boss's desk
spot('DESK', 'desk_boss', 4, 3, 144, 122, DOWN)
hot('desk', 'YOUR DESK', 'Approve requests and set auto-approve', 'desk', 3 * 32, 4 * 32, 7 * 32, 6 * 32 + 4,
    5 * 32, 4 * 32 - 4)

# ---- MEETING / WAR ROOM ----
fx0, fx1, fy = facebox('meeting')
whiteboard(14 * 32, fy + 8, 6 * 32, 44)
clock(13 * 32 + 8, fy + 14)
plaque(17 * 32, fy + 54, 'WAR ROOM')
rug(14 * 32 + 4, 5 * 32, 19 * 32 - 4, 9 * 32 + 8, C('#4b5876'), C('#6d7f99'))
for tx in range(15, 19):
    chair(tx * 32 + 3, 5 * 32 + 2, C('#3d4a68'))
    chair(tx * 32 + 3, 8 * 32 + 6, C('#3d4a68'))
put(o_table(), 15 * 32, 6 * 32)
block(15, 6, 19, 8)
for i, tx in enumerate(range(15, 19)):
    spot('MEETING', f'meet_{i + 1}', tx, 5, tx * 32 + 16, 6 * 32 + 12, DOWN)
    spot('MEETING', f'meet_{i + 5}', tx, 8, tx * 32 + 16, 8 * 32 + 26, UP)
put(o_plant(), 20 * 32 - 2, 3 * 32 - 2)
block(20, 3, 21, 4)
put(o_plant(), 13 * 32 + 3, 9 * 32)
block(13, 9, 14, 10)
hot('war_room', 'WAR ROOM', 'Shared project, tasks and builds', 'project', 14 * 32, 2 * 32, 20 * 32, 8 * 32 + 24)

# ---- LIBRARY ----
fx0, fx1, fy = facebox('library')
for i in range(4):
    sx = fx0 + i * 64
    orect(bd, sx, fy + 4, sx + 64, fy + 60, WOOD_M)
    for r in range(3):
        yy = fy + 7 + r * 17
        rect(bd, sx + 3, yy, sx + 61, yy + 12, DARKW_D)
        books(bd, sx + 4, yy + 1, sx + 60, yy + 12)
        rect(bd, sx + 2, yy + 12, sx + 62, yy + 15, WOOD_D)
plaque(5 * 32, fy - 12 if False else fy + 62 - 12, 'LIBRARY', C('#9fd6a0'))
rug(3 * 32, 17 * 32 + 8, 7 * 32, 19 * 32 + 16, C('#4d7054'), C('#7cc46b'))
for tx in (2, 6):
    put(o_shelf(), tx * 32, 15 * 32 + 4)
    block(tx, 15, tx + 2, 17)
put(o_desk(WOOD_L, WOOD_M), 3 * 32, 18 * 32 + 6)
block(3, 18, 5, 20)
put(o_plant(), 1 * 32 + 3, 18 * 32)
block(1, 18, 2, 19)
for i, tx in enumerate((2, 4, 5, 7)):
    spot('LIBRARY', f'lib_{i + 1}', tx, 14, tx * 32 + 16, 14 * 32 + 24, UP)
hot('library', 'LIBRARY', 'Diary entries and what the agents learned', 'diary', 1 * 32, 12 * 32, 9 * 32, 14 * 32 + 8)

# ---- WEB & COMMS ----
fx0, fx1, fy = facebox('web')
for i in range(3):
    wall_screen(14 * 32 + i * 96 - 12 + 4, fy + 10)
plaque(17 * 32, fy + 54, 'WEB & MAIL', C('#8fc0ee'))
rug(14 * 32, 16 * 32 + 16, 19 * 32, 19 * 32 + 8, C('#3f5b78'), C('#58d3d0'))
for i, (a, b) in enumerate(((13, 15), (16, 18), (19, 21))):
    pass
for i, tx in enumerate((13, 16, 19)):
    put(o_console(), tx * 32, 14 * 32 + 2)
    block(tx, 14, tx + 2, 16)
    cxm = (tx + 1) * 32
    spot('WEB', f'web_{i + 1}', tx, 16, cxm, 16 * 32 + 24, UP)
put(o_mailcab(), 19 * 32, 17 * 32 - 2)
block(19, 17, 21, 19)
spot('MAIL', 'mail_1', 19, 19, 19 * 32 + 16, 19 * 32 + 26, UP)
spot('MAIL', 'mail_2', 20, 19, 20 * 32 + 16, 19 * 32 + 26, UP)
put(o_plant(), 13 * 32 + 3, 18 * 32 + 6)
block(13, 18, 14, 19)
hot('web', 'WEB ROOM', "Each agent's own browser", 'browsers', 13 * 32, 12 * 32, 19 * 32, 16 * 32 + 4)
hot('comms', 'MAIL', 'Email and chat connectors', 'connectors', 19 * 32, 17 * 32, 21 * 32, 19 * 32 + 8)

# ---- BULLPEN ----
fx0, fx1, fy = (1 * 32, 21 * 32, (23 - 2) * 32)
for tx in (2, 7, 14, 18):
    window(tx * 32 + 6, fy + 8)
cork(4 * 32 + 8, fy + 8, 100, 44)
clock(16 * 32 + 6, fy + 16)
plaque(10 * 32 + 16, fy + 54 if False else fy + 62 - 14, 'DESKS', C('#c9d2d2'))
rug(1 * 32 + 8, 27 * 32 + 8, 9 * 32 - 8, 28 * 32 + 24, C('#657792'), C('#8a9bb4'))
rug(13 * 32 + 8, 27 * 32 + 8, 20 * 32 + 24, 28 * 32 + 24, C('#657792'), C('#8a9bb4'))
names = ['michael', 'jim', 'pam', 'dwight', 'angela', 'kevin', 'toby', 'stanley', '9', '10', '11', '12']
slots = []
for ty in (25, 30):
    for dx in (1, 4, 7, 13, 16, 19):
        slots.append((dx, ty))
for i, (dx, ty) in enumerate(slots):
    chair(dx * 32 + 19, (ty - 1) * 32 + 8)
    put(o_desk(), dx * 32, ty * 32)
    block(dx, ty, dx + 2, ty + 2)
    spot('DESK', f'desk_{names[i]}', dx, ty - 1, (dx + 1) * 32, ty * 32 + 10, DOWN)
put(o_copier(), 9 * 32 - 8, 23 * 32 + 2)
block(9, 23, 10, 24)
put(o_plant(), 12 * 32 + 3, 23 * 32 + 2)
block(12, 23, 13, 24)
put(o_plant(), 1 * 32 + 3, 32 * 32 - 6)
block(1, 32, 2, 33)
put(o_plant(), 20 * 32 - 2, 32 * 32 - 6)
block(20, 32, 21, 33)
hot('board', 'NOTICE BOARD', 'Office overview', 'home', 4 * 32 + 8, fy + 8, 4 * 32 + 108, fy + 52)

# ---- WORKSHOP ----
fx0, fx1, fy = facebox('workshop')
for i in range(8):
    px_ = fx0 + 4 + i * 30
    orect(bd, px_, fy + 6, px_ + 26, fy + 52, C('#c4a574'))
    for j in range(3):
        rect(bd, px_ + 4, fy + 12 + j * 12, px_ + 22, fy + 14 + j * 12, GREY_D)
        rect(bd, px_ + 6 + j * 6, fy + 14 + j * 12, px_ + 8 + j * 6, fy + 22 + j * 12, [RED, BLUE, AMBER][j])
plaque(5 * 32, fy + 62 - 12, 'WORKSHOP', C('#f2b84b'))
for x in range(1 * 32 + 4, 9 * 32 - 4, 16):
    rect(bd, x, 38 * 32 + 12, x + 8, 38 * 32 + 16, AMBER)
put(o_bench(), 2 * 32, 36 * 32 + 6)
block(2, 36, 5, 38)
put(o_printer(), 6 * 32, 36 * 32 - 4)
block(6, 36, 8, 38)
put(o_plant(), 1 * 32 + 3, 40 * 32 + 2)
block(1, 40, 2, 41)
spot('WORKSHOP', 'work_1', 3, 38, 3 * 32 + 16 + 16, 38 * 32 + 26, UP)
spot('WORKSHOP', 'work_2', 6, 38, 7 * 32, 38 * 32 + 26, UP)
spot('WORKSHOP', 'work_3', 2, 38, 2 * 32 + 16, 38 * 32 + 26, UP)
hot('workshop', 'WORKSHOP', 'Builds and project files', 'project', 2 * 32, 36 * 32, 8 * 32, 38 * 32)

# ---- SERVER ROOM ----
fx0, fx1, fy = facebox('server')
for i in range(8):
    sx = fx0 + i * 32
    orect(bd, sx, fy + 6, sx + 32, fy + 54, C('#323a48'))
    for j in range(5):
        rect(bd, sx + 4, fy + 10 + j * 8, sx + 28, fy + 15 + j * 8, C('#262d39'))
        rect(bd, sx + 6, fy + 12 + j * 8, sx + 7, fy + 13 + j * 8, [CYAN, GREEN, AMBER][(i + j) % 3])
plaque(17 * 32, fy + 62 - 12, 'SERVERS', CYAN)
for tx in (13, 14, 16, 17, 19, 20):
    put(o_rack(), tx * 32, 36 * 32 - 2)
    block(tx, 36, tx + 1, 38)
for i, tx in enumerate((14, 17, 20)):
    spot('SERVER', f'srv_{i + 1}', tx, 38, tx * 32 + 16, 38 * 32 + 26, UP)
put(o_plant(), 13 * 32 + 3, 40 * 32 + 2)
block(13, 40, 14, 41)
hot('server', 'SERVER ROOM', 'Connectors and deployments', 'connectors', 13 * 32, 36 * 32, 21 * 32, 38 * 32)

# ---- FINANCE ----
fx0, fx1, fy = facebox('finance')
for i in range(3):
    gx = fx0 + 12 + i * 80
    orect(bd, gx, fy + 8, gx + 64, fy + 48, WHITE)
    for j in range(5):
        h = 6 + ((i * 7 + j * 5) % 5) * 5
        rect(bd, gx + 6 + j * 11, fy + 44 - h, gx + 14 + j * 11, fy + 44, [GREEN, GREEN_L, GREEN, AMBER, GREEN][j])
plaque(5 * 32, fy + 62 - 12, 'FINANCE', C('#7cc46b'))
rug(2 * 32, 47 * 32 + 20, 7 * 32, 50 * 32, C('#4d7054'), C('#d9b45c'))
put(o_register(), 2 * 32, 45 * 32 + 6)
block(2, 45, 5, 47)
put(o_safe(), 6 * 32, 45 * 32 + 6)
block(6, 45, 7, 47)
put(o_plant(), 7 * 32 + 8, 45 * 32 + 6)
block(7, 45, 8, 46)
spot('FINANCE', 'fin_1', 3, 47, 3 * 32 + 16, 47 * 32 + 26, UP)
spot('FINANCE', 'fin_2', 4, 47, 4 * 32 + 16, 47 * 32 + 26, UP)
spot('FINANCE', 'fin_3', 6, 47, 6 * 32 + 16, 47 * 32 + 26, UP)
hot('finance', 'FINANCE', 'Revenue, PayPal and Polar', 'settings', 2 * 32, 45 * 32, 7 * 32, 47 * 32)

# ---- BREAK ROOM ----
fx0, fx1, fy = facebox('break')
for i in range(4):
    cx0 = fx0 + 8 + i * 60
    orect(bd, cx0, fy + 6, cx0 + 54, fy + 34, C('#e0d4b8'))
    rect(bd, cx0 + 26, fy + 8, cx0 + 28, fy + 32, C('#b3a98c'))
    rect(bd, cx0 + 21, fy + 24, cx0 + 23, fy + 28, GREY_D)
    rect(bd, cx0 + 31, fy + 24, cx0 + 33, fy + 28, GREY_D)
plaque(16 * 32, fy + 62 - 12 + 0, 'BREAK ROOM', C('#e8b27a'))
rug(14 * 32, 48 * 32 + 12, 19 * 32 + 8, 51 * 32 - 4, C('#b9653f'), C('#e8b27a'))
put(o_counter(machine=True), 13 * 32, 45 * 32 + 4)
block(13, 45, 15, 47)
put(o_counter(sink=True), 16 * 32, 45 * 32 + 4)
block(16, 45, 18, 47)
put(o_fridge(), 20 * 32 - 2, 45 * 32 - 2)
block(20, 45, 21, 47)
put(o_couch_back(), 15 * 32, 49 * 32, sort=49 * 32 + 12)
put(o_couch_front(), 15 * 32, 49 * 32 + 22, sort=49 * 32 + 48)
block(15, 49, 18, 51)
put(o_plant(), 19 * 32 + 4, 49 * 32 + 8)
block(19, 49, 20, 50)
for i, cxm in enumerate((448, 544)):
    spot('KITCHEN', f'kit_{i + 1}', cxm // 32 - 1, 47, cxm, 47 * 32 + 26, UP)
spot('KITCHEN', 'kit_3', 20, 47, 20 * 32 + 16, 47 * 32 + 26, UP)
for i, tx in enumerate((15, 16, 17)):
    spot('LOUNGE', f'lounge_{i + 1}', tx, 48, tx * 32 + 16, 49 * 32 + 24, DOWN)

# ---- SPINE / lobby ----
put(o_plant(), 10 * 32 + 2, 3 * 32 - 2)
put(o_plant(), 11 * 32 + 2, 49 * 32 + 6)
block(10, 3, 11, 4)
block(11, 49, 12, 50)
plaque(11 * 32, 2 * 32 + 4, 'LOBBY', C('#d9c7a4'))
# bottom entrance mat + exit sign
orect(bd, 10 * 32 + 4, 50 * 32 + 8, 12 * 32 - 4, 51 * 32 - 2, C('#5f2430'))
text(bd, 10 * 32 + 12, 50 * 32 + 14, 'WELCOME', C('#d9b45c'), 1)
orect(bd, 11 * 32 - 14, 51 * 32 + 6, 11 * 32 + 14, 51 * 32 + 22, GREEN_D)
text(bd, 11 * 32 - 10, 51 * 32 + 10, 'EXIT', WHITE, 1)
for ty in (51,):
    for tx in range(COLS):
        BLOCK.add((tx, ty))

# ======================= output =======================
# collision grid: walls and blocked tiles
rows = []
for ty in range(ROWS):
    line = ''
    for tx in range(COLS):
        ok = is_floor(tx, ty) and (tx, ty) not in BLOCK
        line += '.' if ok else '#'
    rows.append(line)

# atlas
OBJECTS.sort(key=lambda o: (o[1], o[2]))
ax, ay, rowh, AW = 0, 0, 0, 512
placed = []
for (img, x, y, sy) in OBJECTS:
    w, h = img.size
    if ax + w > AW:
        ax, ay, rowh = 0, ay + rowh + 1, 0
    placed.append((ax, ay, w, h, x, y, sy))
    ax += w + 1
    rowh = max(rowh, h)
AH = ay + rowh + 1
atlas = Image.new('RGBA', (AW, AH), (0, 0, 0, 0))
for (img, x, y, sy), (sx0, sy0, w, h, _, _, _) in zip(OBJECTS, placed):
    atlas.alpha_composite(img, (sx0, sy0))

# draw sorted objects onto a full preview (what the app will show, without agents)
preview = base.copy()
for (img, x, y, sy) in sorted(OBJECTS, key=lambda o: o[3]):
    preview.alpha_composite(img, (x, y))

base.save('./office_pixel_base.png', optimize=True)
atlas.save('./office_pixel_objects.png', optimize=True)
preview.save('./preview_full.png')

kinds = {}
for s_ in SPOTS:
    kinds[s_[0]] = kinds.get(s_[0], 0) + 1
print('objects', len(OBJECTS), 'atlas', atlas.size, 'spots', kinds, 'hotspots', len(HOTS))

json.dump({'rows': rows, 'spots': SPOTS, 'objects': placed, 'hots': HOTS, 'atlas': [AW, AH]},
          open('./map.json', 'w'))
