# Source of the antique map sprites in fog.fsh (SPRITES). Run with Python 3 and paste the output
# over the array. Land is drawn as plain washes; only the sea carries a sprite.
#
# '.' empty, '#' outline ink, 'o' body, '+' highlight. 16x16, column 0 is the left. A sprite's
# anchor is its centre column and its bottom row 13, so overlapping sprites stack front to back.
SPRITES = {
# Two wavelets, drawn on open water.
"wave": [
"................",
"................",
"................",
"................",
"................",
".##.............",
"#++#..#.........",
"....##..........",
"................",
"........##......",
".......#++#..#..",
"...........##...",
"................",
"................",
"................",
"................",
],
}
ORDER = ["wave"]
CODE = {'.': 0, '#': 1, 'o': 2, '+': 3}
values = []
for name in ORDER:
    rows = SPRITES[name]
    assert len(rows) == 16, name
    for r in rows:
        assert len(r) == 16, (name, r, len(r))
        v = 0
        for c, ch in enumerate(r):
            v |= CODE[ch] << (2 * c)
        values.append(v)
lines = []
for i in range(0, len(values), 8):
    lines.append("    " + ", ".join("0x%08Xu" % v for v in values[i:i + 8]))
print("const uint SPRITES[%d] = uint[%d](\n%s\n);" % (len(values), len(values), ",\n".join(lines)))
