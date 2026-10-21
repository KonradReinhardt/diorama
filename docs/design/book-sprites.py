# Source of the world map book's sprite sheet, assets/diorama/textures/gui/book.png. Run with
# Python 3 (no packages needed) from the repository root; it overwrites the PNG. Resource packs
# replace that PNG; the regions are listed in dev.diorama.ui.BookSprites and must stay put.
#
# All art is drawn here from code, one texel per GUI pixel: warm parchment pages with a dotted
# inner frame, a leather cover with gold corners, rounded slots, grey ribbons that the game tints
# per section, and small thumbnails of the render, fog and region styles.
import math
import os
import struct
import zlib

W = H = 256
px = [[(0, 0, 0, 0)] * W for _ in range(H)]


def hexc(s, a=255):
    s = s.lstrip('#')
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)


INK = hexc('5A2E22')
INK_SOFT = hexc('8A5A3C')
PAGE = hexc('F5C99B')
PAGE_LIGHT = hexc('FBDDB5')
PAGE_SHADE = hexc('E8B482')
PAGE_DEEP = hexc('D69C68')
SLOT = hexc('EAB384')
SLOT_DARK = hexc('C98E5E')
LEATHER = hexc('9C5233')
LEATHER_DARK = hexc('6E3420')
LEATHER_LIGHT = hexc('B8693F')
GOLD = hexc('E8B83A')
GOLD_DARK = hexc('A8781E')
GOLD_LIGHT = hexc('FFE08A')
WHITE = hexc('FFFFFF')
GREY = hexc('D4D4D4')
GREY_DARK = hexc('9A9A9A')
RED = hexc('C8352B')
RED_DARK = hexc('7E1D17')


def put(x, y, c):
    if 0 <= x < W and 0 <= y < H:
        px[y][x] = c


def rect(x, y, w, h, c):
    for j in range(y, y + h):
        for i in range(x, x + w):
            put(i, j, c)


def hline(x, y, w, c):
    rect(x, y, w, 1, c)


def vline(x, y, h, c):
    rect(x, y, 1, h, c)


def frame(x, y, w, h, c):
    hline(x, y, w, c)
    hline(x, y + h - 1, w, c)
    vline(x, y, h, c)
    vline(x + w - 1, y, h, c)


def rounded(x, y, w, h, fill, edge):
    """A rectangle with one-texel cut corners and an outline."""
    rect(x + 1, y + 1, w - 2, h - 2, fill)
    hline(x + 1, y, w - 2, edge)
    hline(x + 1, y + h - 1, w - 2, edge)
    vline(x, y + 1, h - 2, edge)
    vline(x + w - 1, y + 1, h - 2, edge)


def art(x, y, rows, palette):
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            if ch in palette:
                put(x + i, y + j, palette[ch])


# --- Cover: 64x64 at (0, 0), nine-sliced with an 8 texel border; only its rim is drawn. -----------
def cover(x, y):
    rounded(x, y, 64, 64, LEATHER, INK)
    # Lighter leather inside the stitching, darker toward the edge.
    frame(x + 1, y + 1, 62, 62, LEATHER_DARK)
    for i in range(3, 61, 2):
        put(x + i, y + 3, LEATHER_LIGHT)
        put(x + i, y + 60, LEATHER_LIGHT)
        put(x + 3, y + i, LEATHER_LIGHT)
        put(x + 60, y + i, LEATHER_LIGHT)
    corner = [
        "#####...",
        "#gGGg#..",
        "#GGgg#..",
        "#Ggd#...",
        "#gd#....",
        ".##.....",
    ]
    pal = {'#': INK, 'g': GOLD, 'G': GOLD_LIGHT, 'd': GOLD_DARK}
    for cx, cy, fx, fy in ((0, 0, False, False), (63, 0, True, False), (0, 63, False, True), (63, 63, True, True)):
        for j, row in enumerate(corner):
            for i, ch in enumerate(row):
                if ch in pal:
                    put(x + (cx - i if fx else cx + i), y + (cy - j if fy else cy + j), pal[ch])


cover(0, 0)


# --- Page: 64x64 at (64, 0), border 8; the dotted inner frame of the reference. ------------------
def page(x, y):
    rect(x, y, 64, 64, PAGE)
    frame(x, y, 64, 64, PAGE_SHADE)
    hline(x + 1, y + 1, 62, PAGE_LIGHT)
    # Inner frame line, 3 texels in, with dots at the corners.
    frame(x + 3, y + 3, 58, 58, PAGE_SHADE)
    for cx, cy in ((4, 4), (59, 4), (4, 59), (59, 59)):
        put(x + cx, y + cy, PAGE_DEEP)


page(64, 0)

# --- Spine crease: 8x64 at (128, 0), tiled down the middle of the book. ---------------------------
for j in range(64):
    for i, c in enumerate((PAGE_SHADE, PAGE_DEEP, SLOT_DARK, INK_SOFT, INK_SOFT, SLOT_DARK, PAGE_DEEP, PAGE_SHADE)):
        put(128 + i, j, c)


# --- Slots: 16x16 at (0, 64) normal, (16, 64) hovered, (32, 64) selected. Border 4. -------------
def slot(x, y, edge, fill, inner):
    rounded(x, y, 16, 16, fill, edge)
    hline(x + 1, y + 1, 14, inner)
    vline(x + 1, y + 1, 14, inner)
    # Little nubs halfway along each side, like the stitched slots of the reference.
    for (i, j) in ((7, 0), (8, 0), (7, 15), (8, 15), (0, 7), (0, 8), (15, 7), (15, 8)):
        put(x + i, y + j, PAGE_SHADE)


slot(0, 64, SLOT_DARK, SLOT, PAGE_DEEP)
slot(16, 64, INK_SOFT, PAGE_LIGHT, SLOT)
slot(32, 64, INK, hexc('F9D7A6'), GOLD)
for (i, j) in ((1, 1), (14, 1), (1, 14), (14, 14)):
    put(32 + i, 64 + j, GOLD_DARK)


# --- Buttons: 16x16 at (48, 64) normal, (64, 64) hovered, (80, 64) disabled. Border 4. ----------
def button(x, y, fill, light, dark, edge):
    rounded(x, y, 16, 16, fill, edge)
    hline(x + 1, y + 1, 14, light)
    vline(x + 1, y + 1, 13, light)
    hline(x + 1, y + 14, 14, dark)
    vline(x + 14, y + 2, 13, dark)


button(48, 64, SLOT, PAGE_LIGHT, SLOT_DARK, INK)
button(64, 64, PAGE_LIGHT, WHITE, SLOT, INK)
button(80, 64, PAGE_SHADE, PAGE, PAGE_DEEP, SLOT_DARK)

# --- Label: 16x16 at (96, 64), the parchment cartouche for the status line. Border 4. -----------
rounded(96, 64, 16, 16, PAGE, INK)
frame(98, 66, 12, 12, PAGE_SHADE)

# --- Text field: 16x16 at (112, 64), a line to write on. Border 4. ------------------------------
rect(113, 65, 14, 14, hexc('FCE3C0'))
hline(112, 79, 16, INK_SOFT)
hline(113, 78, 14, PAGE_SHADE)


# --- Ribbon: 36x22 at (0, 80), grey so the game tints it, notched at both ends. ----------------
def ribbon(x, y):
    """Swallowtail notches at both ends, so a ribbon reads whole at the screen's edge. Over the
    book only its right part is drawn, from the cover's edge, as if the rest were under it."""
    w, h = 36, 22
    for j in range(h):
        notch = max(0, 5 - abs(j - h // 2))
        start, end = notch, w - 1 - notch
        for i in range(start, end + 1):
            c = WHITE
            if j <= 1:
                c = GREY if j == 1 else INK
            elif j >= h - 2:
                c = GREY_DARK if j == h - 2 else INK
            elif i == start or i == end:
                c = INK
            elif i == start + 1 or i == end - 1:
                c = GREY
            put(x + i, y + j, c)
    # Stitching along both edges.
    for i in range(8, w - 7, 3):
        put(x + i, y + 3, GREY)
        put(x + i, y + h - 4, GREY)


ribbon(0, 80)


# --- Top tabs: 24x20 at (40, 80) normal, (64, 80) selected. ---------------------------------------
def tab(x, y, fill, light):
    rect(x + 1, y + 1, 22, 19, fill)
    hline(x + 2, y, 20, INK)
    put(x + 1, y + 1, INK)
    put(x + 22, y + 1, INK)
    vline(x, y + 2, 18, INK)
    vline(x + 23, y + 2, 18, INK)
    hline(x + 2, y + 1, 20, light)
    vline(x + 1, y + 2, 17, light)


tab(40, 80, SLOT, PAGE_LIGHT)
tab(64, 80, PAGE, WHITE)

# --- Close X: 11x11 at (88, 80), hovered at (99, 80). -----------------------------------------
X_ROWS = [
    ".##.....##.",
    "#rr#...#rr#",
    "#rRr#.#rRr#",
    ".#rRr#rRr#.",
    "..#rRrRr#..",
    "...#rRr#...",
    "..#rRrRr#..",
    ".#rRr#rRr#.",
    "#rRr#.#rRr#",
    "#rr#...#rr#",
    ".##.....##.",
]
art(88, 80, X_ROWS, {'#': INK, 'r': RED, 'R': hexc('E8574A')})
art(99, 80, X_ROWS, {'#': INK, 'r': hexc('E8574A'), 'R': hexc('FF8A7A')})

# --- Toggles: 9x9 off (110, 80), on (119, 80), off disabled (128, 80), on disabled (137, 80). ---
TOGGLE_OFF = [
    ".#######.",
    "#ppppppp#",
    "#pLLLLLp#",
    "#pL...Lp#",
    "#pL...Lp#",
    "#pL...Lp#",
    "#pLLLLLp#",
    "#ppppppp#",
    ".#######.",
]
TOGGLE_ON = [
    ".#######.",
    "#ppppppk#",
    "#pLLLLkk#",
    "#kLLLkkp#",
    "#kkLkkLp#",
    "#pkkkLLp#",
    "#pLkLLLp#",
    "#ppppppp#",
    ".#######.",
]
art(110, 80, TOGGLE_OFF, {'#': INK, 'p': SLOT, 'L': PAGE_LIGHT, '.': hexc('FCE3C0')})
art(119, 80, TOGGLE_ON, {'#': INK, 'p': SLOT, 'L': PAGE_LIGHT, 'k': hexc('2F7A3A')})
art(128, 80, TOGGLE_OFF, {'#': SLOT_DARK, 'p': PAGE_SHADE, 'L': PAGE, '.': PAGE})
art(137, 80, TOGGLE_ON, {'#': SLOT_DARK, 'p': PAGE_SHADE, 'L': PAGE, 'k': PAGE_DEEP})

# --- Arrows: 7x9 left (146, 80), right (153, 80); hovered (160, 80), (167, 80). -----------------
LEFT = [
    "....##.",
    "...#a#.",
    "..#aa#.",
    ".#aaa#.",
    "#aaaa#.",
    ".#aaa#.",
    "..#aa#.",
    "...#a#.",
    "....##.",
]
RIGHT = [row[::-1] for row in LEFT]
art(146, 80, LEFT, {'#': INK, 'a': SLOT_DARK})
art(153, 80, RIGHT, {'#': INK, 'a': SLOT_DARK})
art(160, 80, LEFT, {'#': INK, 'a': GOLD})
art(167, 80, RIGHT, {'#': INK, 'a': GOLD})

# --- Leave mark: 7x7 at (174, 80), the arrow on the minimap ribbon. --------------------------------
art(174, 80, [
    "..#####",
    "...####",
    "..#####",
    ".###.##",
    "###...#",
    "##.....",
    "#......",
], {'#': INK})

# --- Help: 12x12 at (181, 80), a "?" seal; hovered at (193, 80). --------------------------------
HELP = [
    "...######...",
    "..#gggggg#..",
    ".#ggg##ggg#.",
    "#ggg#gg#ggg#",
    "#ggggggg#gg#",
    "#gggggg#ggg#",
    "#ggggg#gggg#",
    "#ggggg#gggg#",
    "#gggggggggg#",
    ".#gggg#ggg#.",
    "..#gggggg#..",
    "...######...",
]
art(181, 80, HELP, {'#': INK, 'g': GOLD})
art(193, 80, HELP, {'#': INK, 'g': GOLD_LIGHT})

# --- Separator: 16x3 at (0, 112) tiles across; flourish 16x5 at (16, 112) sits in its middle. -------
for i in range(16):
    put(i, 113, PAGE_DEEP if i % 2 == 0 else PAGE_SHADE)
art(16, 112, [
    "......#..#......",
    "..##.#.##.#.##..",
    "##..#..##..#..##",
    "..##.#.##.#.##..",
    "......#..#......",
], {'#': INK_SOFT})

# --- Compass needle: 9x9 at (32, 112), on the direction slots. -----------------------------------
art(32, 112, [
    "....#....",
    "...#r#...",
    "...#r#...",
    "..#rrr#..",
    "..#.o.#..",
    "..#www#..",
    "...#w#...",
    "...#w#...",
    "....#....",
], {'#': INK, 'r': RED, 'w': PAGE_LIGHT, 'o': GOLD})

# --- Thumbnails, 28x20 each. Render styles at (0, 128): plain, outlined, painted, painted with ---
# no outlines. Fog styles at (0, 148): clouds, antique, antique in colour. A little island of
# 4x4 blocks: sea, sand, grass, forest and a stony hill.
TERRAIN = [
    "wwssggg",
    "wsgggff",
    "wsgffhh",
    "wsgfhhh",
    "wwsggfh",
]
HEIGHT = {'w': 0, 's': 1, 'g': 2, 'f': 3, 'h': 4}
PLAIN = {'w': hexc('3F76E4'), 's': hexc('DBD3A0'), 'g': hexc('7FB238'), 'f': hexc('4E7A2A'), 'h': hexc('8F8F8F')}
PAINT = {'w': hexc('6E9CC8'), 's': hexc('E6D3A0'), 'g': hexc('9DB86A'), 'f': hexc('6E8A4A'), 'h': hexc('A89C88')}


def mix(a, b, t):
    return tuple(int(round(a[k] * (1 - t) + b[k] * t)) for k in range(3)) + (255,)


def terrain(x, y, colours, outline, painted, ink):
    for by, row in enumerate(TERRAIN):
        for bx, m in enumerate(row):
            for j in range(4):
                for i in range(4):
                    c = colours[m]
                    if painted:
                        # Two flat tones: lit top-left, shaded bottom-right.
                        c = mix(c, PAGE_LIGHT, 0.18) if i + j < 4 else mix(c, INK, 0.12)
                        if m == 'w' and (i + 2 * j + bx) % 5 == 0:
                            c = mix(c, WHITE, 0.35)
                    else:
                        if j == 0:
                            c = mix(c, WHITE, 0.15)
                        elif j == 3:
                            c = mix(c, (0, 0, 0), 0.12)
                    put(x + bx * 4 + i, y + by * 4 + j, c)
    # Edges between blocks of different height or ground.
    for by, row in enumerate(TERRAIN):
        for bx, m in enumerate(row):
            right = row[bx + 1] if bx + 1 < len(row) else m
            below = TERRAIN[by + 1][bx] if by + 1 < len(TERRAIN) else m
            coast = (m == 'w') != (right == 'w')
            coast_below = (m == 'w') != (below == 'w')
            if outline and HEIGHT[right] != HEIGHT[m]:
                vline(x + bx * 4 + 3, y + by * 4, 4, mix(colours[max(m, right, key=HEIGHT.get)], (0, 0, 0), 0.45))
            if outline and HEIGHT[below] != HEIGHT[m]:
                hline(x + bx * 4, y + by * 4 + 3, 4, mix(colours[max(m, below, key=HEIGHT.get)], (0, 0, 0), 0.45))
            if painted and ink and coast:
                vline(x + bx * 4 + 3, y + by * 4, 4, INK_SOFT)
            if painted and ink and coast_below:
                hline(x + bx * 4, y + by * 4 + 3, 4, INK_SOFT)
            if painted and coast:
                put(x + bx * 4 + 2, y + by * 4 + 1, WHITE)
    frame(x, y, 28, 20, INK)


terrain(0, 128, PLAIN, False, False, False)
terrain(28, 128, PLAIN, True, False, False)
terrain(56, 128, PAINT, False, True, True)
terrain(84, 128, PAINT, False, True, False)


def fog(x, y, kind):
    sepia = hexc('E9D2A6')
    for j in range(20):
        for i in range(28):
            inside = (i - 13) ** 2 / 64 + (j - 10) ** 2 / 30 < 1
            if inside:
                m = TERRAIN[min(4, j // 4)][min(6, i // 4)]
                c = PLAIN[m]
                if kind != 'clouds':
                    c = mix(c, sepia, 0.35)
            elif kind == 'clouds':
                c = hexc('E8EEF4') if (i * 7 + j * 3) % 11 > 2 else hexc('C8D2DE')
            else:
                c = sepia
                if kind == 'colour':
                    c = mix(sepia, hexc('7FB238') if i > 14 else hexc('6E9CC8'), 0.25)
                if (i + 3 * j) % 9 == 0:
                    c = mix(c, INK, 0.25)
            put(x + i, y + j, c)
    if kind == 'clouds':
        for cx, cy in ((3, 3), (24, 4), (4, 16), (23, 16), (14, 1)):
            for j in range(-2, 3):
                for i in range(-3, 4):
                    if i * i + 2 * j * j <= 10 and 0 <= cx + i < 28 and 0 <= cy + j < 20:
                        put(x + cx + i, y + cy + j, WHITE)
    else:
        # The frontier: a frayed ink line around the explored island.
        for t in range(0, 360, 6):
            a = math.radians(t)
            put(x + 13 + int(round(8.4 * math.cos(a))), y + 10 + int(round(5.6 * math.sin(a))), INK_SOFT)
        art(x + 2, y + 15, [".##..#", "#..##."], {'#': INK_SOFT})
    frame(x, y, 28, 20, INK)


fog(0, 148, 'clouds')
fog(28, 148, 'antique')
fog(56, 148, 'colour')


# The region styles (dev.diorama.region.RegionStyle), each a red region on the same patch of
# ground, drawn as regions.fsh draws it: marker, political, watercolour, wash, engraving and
# illuminated.
REGION_DYE = hexc('B02E26')
REGION_INK = hexc('4C3021')
OLD_INK = hexc('3C2513')


def region_inside(i, j):
    return (i - 13.5) ** 2 / 90 + (j - 10) ** 2 / 42 < 1


def region_thumb(x, y, style):
    old = style in ('engraving', 'illuminated')
    for j in range(20):
        for i in range(28):
            m = TERRAIN[min(4, j // 4)][min(6, i // 4)]
            c = PAINT[m] if style in ('watercolour', 'wash') else PLAIN[m]
            if old:
                c = mix(c, hexc('E9D2A6'), 0.55)
            put(x + i, y + j, c)
    for j in range(20):
        for i in range(28):
            if not region_inside(i, j):
                continue
            edge = any(not region_inside(i + di, j + dj) for di, dj in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            near = any(not region_inside(i + di, j + dj) for di, dj in ((2, 0), (-2, 0), (0, 2), (0, -2)))
            band = sum(1 for k in range(1, 5) for di, dj in ((k, 0), (-k, 0), (0, k), (0, -k)) if not region_inside(i + di, j + dj))
            c = px[y + j][x + i]
            if style == 'marker':
                c = REGION_DYE if edge else mix(c, REGION_DYE, 0.3)
            elif style == 'political':
                c = mix(REGION_DYE, (0, 0, 0), 0.65) if edge else REGION_DYE if near else mix(c, REGION_DYE, 0.5 if band else 0.3)
            elif style in ('watercolour', 'wash'):
                grain = ((i * 7 + j * 13) % 5) / 10
                pool = min(1.0, band / 4)
                c = mix(c, mix(REGION_DYE, (0, 0, 0), 0.3 * pool), 0.25 + 0.1 * grain + 0.35 * pool)
                if edge:
                    c = REGION_INK if style == 'watercolour' and (i + j) % 7 else mix(REGION_DYE, (0, 0, 0), 0.3)
            else:
                tint = mix(hexc('6E4A2A'), hexc('E9D2A6'), 0.35) if style == 'engraving' else REGION_DYE
                c = mix(c, tint, 0.55 * min(1.0, band / 3))
                if style == 'engraving' and (i - j) % 4 == 0:
                    c = mix(c, tint, 0.6)
                if edge:
                    c = OLD_INK if (i + j) % 8 < 4 or (i + j) % 8 == 5 else mix(c, tint, 0.6)
            put(x + i, y + j, c)
    frame(x, y, 28, 20, INK)


for k, style in enumerate(('marker', 'political', 'watercolour', 'wash', 'engraving', 'illuminated')):
    region_thumb(k * 28, 168, style)


def write_png(path):
    raw = b''.join(b'\x00' + bytes(v for p in row for v in p) for row in px)
    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(png)


write_png(os.path.join('src', 'main', 'resources', 'assets', 'diorama', 'textures', 'gui', 'book.png'))
