# Source of the mod's icon: a map on parchment with a banner planted on it, drawn on a 32x32 grid
# and scaled up by whole pixels so every art pixel stays square. Run with
# `python docs/design/icon.py` from the repository root; it writes
# src/main/resources/assets/diorama/icon.png (128x128, for the loaders and launchers) and
# assets/icon.png (256x256, for the README). Pass a directory to write only previews there.

import os
import struct
import sys
import zlib

N = 32

PALETTE = {
	'.': None,
	'K': '#2e1a12',  # outline
	'P': '#efd6a6',  # parchment
	'h': '#f8e7c4',  # parchment, lit
	'p': '#d9b880',  # parchment, shaded
	'i': '#9a6a4c',  # the map's inked frame (the book's soft ink)
	'W': '#3f74b5',  # sea
	'w': '#79a8dc',  # glint on the sea
	'S': '#e6d08e',  # sand
	'G': '#78a84e',  # grass
	'g': '#5f8a3c',  # grass, darker
	'T': '#3d6b2e',  # trees
	'M': '#8c7c6a',  # mountain
	'm': '#665a4c',  # mountain, shaded
	'n': '#f4f1ea',  # snow
	'R': '#c4372f',  # banner cloth
	'r': '#8e2520',  # banner cloth, shaded
	'l': '#e05a4e',  # banner cloth, lit
	'Y': '#f2c23a',  # banner emblem
	'B': '#9a6a3a',  # pole
	'b': '#5e3d1f',  # crossbar and the pole's dark side
}

# The map inside its inked frame, x 4..27 and y 11..27.
TERRAIN = [
	'WWWWSGGGGGGGGGGGGGGGGGGG',
	'WWWSGGGGMGGGGGGGGGGGGGGG',
	'WWSGGGGMnMGGGTTGGGGGGGGG',
	'WWSGGGMMMmmGTTTGGGGGGGGG',
	'WSGGGMMMmmmGGgGGGGGGGGGG',
	'WSGGGGGGGGGGGGGGGGGGGGSS',
	'WSGTTGGGGGGGGGGGGGGGGSWW',
	'WSTTTGGGGGgGGGGGGGGGSWWW',
	'WWSgGGGGGGGGGGGGGGGSWWWW',
	'WWSGGGGTTGGGGGGGGGGSWWwW',
	'WWWSGGTTTGGGGGGGGGGSWWWW',
	'WwWSSGGgGGGGGGGGGGSSWWWW',
	'WWWWWSSGGGGGGGGGSSWWWwWW',
	'WWwWWWWSSSSSSSSSSWWWWWWW',
	'WWWWWWWWWWWWWWWWWWWWWWWW',
	'WWWWWWwWWWWWWWwWWWWWWWWW',
	'WWWWWWWWWWWWWWWWWWWWWWWW',
]

# The banner, x 17..25 from the top: a knob, the crossbar, the cloth with a compass star, the pole.
BANNER = [
	'....K....',
	'bbbbbbbbb',
	'.KlRRRrK.',
	'.KlRRRrK.',
	'.KlRYRrK.',
	'.KlYYYrK.',
	'.KYYYYYK.',
	'.KlYYYrK.',
	'.KlRYRrK.',
	'.KlRRRrK.',
	'.KlRRRrK.',
	'.KlRRRrK.',
	'.KKKKKKK.',
	'....B....',
	'....B....',
	'....B....',
	'....B....',
	'....B....',
	'....b....',
]
BANNER_X, BANNER_Y = 17, 1


def rgba(c, a=255):
	return (int(c[1:3], 16), int(c[3:5], 16), int(c[5:7], 16), a)


px = [[(0, 0, 0, 0)] * N for _ in range(N)]


def put(x, y, key):
	c = PALETTE[key]
	if c is not None:
		px[y][x] = rgba(c)


def darken(x, y, f):
	r, g, b, a = px[y][x]
	px[y][x] = (int(r * f), int(g * f), int(b * f), a)


# A soft shadow under the sheet, one pixel down and right.
for y in range(9, 32):
	px[y][31] = (0, 0, 0, 70)
for x in range(2, 32):
	px[31][x] = (0, 0, 0, 70)

# The sheet: outlined parchment, lit along its top and left, shaded along its bottom and right.
for y in range(8, 31):
	for x in range(1, 31):
		edge = x in (1, 30) or y in (8, 30)
		put(x, y, 'K' if edge else 'P')
for x in range(2, 30):
	put(x, 9, 'h')
	put(x, 29, 'p')
for y in range(9, 30):
	put(2, y, 'h')
	put(29, y, 'p')
for x in range(3, 29):
	put(x, 10, 'i')
	put(x, 28, 'i')
for y in range(10, 29):
	put(3, y, 'i')
	put(28, y, 'i')

# Cut corners, so it reads as a sheet of paper rather than a framed picture.
for cx, cy, ix, iy in ((1, 8, 2, 9), (30, 8, 29, 9), (1, 30, 2, 29), (30, 30, 29, 29)):
	px[cy][cx] = (0, 0, 0, 0)
	put(ix, iy, 'K')
px[31][31] = (0, 0, 0, 0)

assert len(TERRAIN) == 17 and all(len(r) == 24 for r in TERRAIN)
for j, row in enumerate(TERRAIN):
	for i, key in enumerate(row):
		put(4 + i, 11 + j, key)

# The fold down the middle of the sheet: a shaded crease with a lit edge beside it.
for y in range(10, 29):
	darken(16, y, 0.86)
	r, g, b, a = px[y][17]
	px[y][17] = (min(255, int(r * 1.08)), min(255, int(g * 1.08)), min(255, int(b * 1.08)), a)

# The banner's shadow falls down and right of the pole's foot, light coming from the top left.
foot_x, foot_y = BANNER_X + 4, BANNER_Y + len(BANNER) - 1
for dx in (1, 2):
	darken(foot_x + dx, foot_y, 0.72)

assert all(len(r) == 9 for r in BANNER)
for j, row in enumerate(BANNER):
	for i, key in enumerate(row):
		put(BANNER_X + i, BANNER_Y + j, key)


def write_png(path, scale, background=None):
	rows = []
	for y in range(N):
		row = []
		for x in range(N):
			p = px[y][x]
			if background is not None:
				a = p[3] / 255
				p = tuple(int(p[k] * a + background[k] * (1 - a)) for k in range(3)) + (255,)
			row.extend(p * scale)
		rows.extend([bytes(row)] * scale)
	size = N * scale
	raw = b''.join(b'\x00' + r for r in rows)

	def chunk(tag, data):
		return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
	png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0))
	png += chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
	os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
	with open(path, 'wb') as f:
		f.write(png)


if len(sys.argv) > 1:
	out = sys.argv[1]
	write_png(os.path.join(out, 'icon-32.png'), 1)
	write_png(os.path.join(out, 'icon-128.png'), 4)
	write_png(os.path.join(out, 'icon-256.png'), 8)
else:
	write_png(os.path.join('src', 'main', 'resources', 'assets', 'diorama', 'icon.png'), 4)
	write_png(os.path.join('assets', 'icon.png'), 8)
