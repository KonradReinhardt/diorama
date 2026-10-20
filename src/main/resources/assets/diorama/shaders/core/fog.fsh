#version 330
//? if >=26.3 {
#extension GL_ARB_separate_shader_objects : require
//?}

// The fog over the parts of the map with no data, in one of two pixel-art styles: clouds, or an
// antique map with the seed's predicted biomes. Also draws antique-style terrain on backends
// without the lighting pipeline (the tile mode in main).

//? if >=26.3 {
layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    float GlintAlpha;
    vec3 CameraOffset;
    float GameTime;
    vec2 ScreenSize;
    int MenuBlurRadius;
    int UseRgss;
};

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
//?} else if >=1.21.11 {
/*layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    vec3 CameraOffset;
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
    int UseRgss;
};

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
*///?} else if <1.21.6 {
/*// Before 1.21.6 uniforms were set one by one, and only the ones the shader names.
uniform float GameTime;
uniform vec4 ColorModulator;
*///?} else {
/*// Before 1.21.11 the globals started at the screen size, and the transforms ended with a line width.
layout(std140) uniform Globals {
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
};

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
    float LineWidth;
};
*///?}

// Share of known ground per mask texel, in the red channel. Green is 1 where every texel this
// shader can read from a pixel in that texel is fully known, blue is 1 where every one is fully
// unknown.
uniform sampler2D Sampler0;
// Tileable noise covering NOISE_PERIOD map units, a different scale per channel.
uniform sampler2D Sampler1;

//? if >=26.3 {
layout(location = 0) in vec2 maskUv;
//?} else {
/*in vec2 maskUv;
*///?}
// rg high 3 bits: noise offset; r low 5 and g low 3 bits: daylight, 0..255.
// b: cave flag (128), fog scale steps
// (bits 4-6) and sixteenths of a step (bits 0-3); a: mask texel size code (bits 4-7) and the fast
// quality flag (4).
//? if >=26.3 {
layout(location = 1) in vec4 params;

layout(location = 0) out vec4 fragColor;
//?} else {
/*in vec4 params;

out vec4 fragColor;
*///?}

const float NOISE_PERIOD = 512.0;

// Both styles are pixel art. Every colour is worked out once per art pixel: one GUI pixel square
// on a grid aligned to the framebuffer, or, zoomed in past that, one block on the map's own grid,
// so the fog's pixels match the terrain's blocks. MapViewport passes the GUI pixel's size and the
// grid's phase (where the world's origin lands), so the screen grid moves with the map in whole
// pixels and never slides under it.

// Surface fog banks are tall and soft. Cave fog hugs the ground with a tight edge, so a
// three-block tunnel you walked down stays readable instead of blurring into the fog.
float fogHeight;
float blurUnits;
float unitsPerUv;
vec2 noiseOrigin;
// The noise pattern grows by this when zoomed far out, so it keeps its size on screen.
float fogScale;
float scaleSteps;
float scaleFraction;
float seconds;
float revealProgress;
float daylight;
// Texels across the mask: 256 for clouds, 512 for the antique map's finer frontier.
float maskTexels;
// The mask coordinate this pixel reads; see UNDERLAY_SHIFT.
vec2 uv0;
// The opaque pass drawn under the tiles, rather than the translucent one over them.
bool underlay;
// Map units repeat the noise origin every this many units, so anything world-anchored must tile with it.
float anchorPeriod;
// The antique map in colour rather than sepia.
bool colourful;

// MapViewport shifts the u coordinates of the pass under the tiles by this much. The mask is
// clamped, so the shifted pass decodes it back here without another vertex attribute.
const float UNDERLAY_SHIFT = 16.0;

// ---------------------------------------------------------------------------------------------
// The art pixel grid

float artPixels;
vec2 artPhase;
// Map units per framebuffer pixel, and per art pixel.
float unitsPerPixel;
float art;
// Zoomed in far enough that a block is bigger than a GUI pixel: the grid is the map's blocks.
bool blockGrid;
// The fragment in framebuffer pixels with y turned to grow down the map, like v, and its map units.
vec2 fragMap;
vec2 fragUnits;

vec2 unitsAt(vec2 uv) {
    return noiseOrigin * anchorPeriod + uv * unitsPerUv;
}

vec2 uvAt(vec2 units) {
    return (units - noiseOrigin * anchorPeriod) / unitsPerUv;
}

// The art pixel holding a point given in map units.
vec2 artIndex(vec2 units) {
    if (blockGrid) return floor(units);
    return floor((fragMap + (units - fragUnits) / unitsPerPixel + artPhase) / artPixels);
}

// The centre of an art pixel, in map units.
vec2 artCentre(vec2 index) {
    if (blockGrid) return index + 0.5;
    return fragUnits + ((index + 0.5) * artPixels - artPhase - fragMap) * unitsPerPixel;
}

// Sets up the grid for this fragment. Uses derivatives: call while every pixel of the quad runs.
vec2 setUpGrid() {
    fragUnits = unitsAt(uv0);
    vec2 dx = dFdx(fragUnits), dy = dFdy(fragUnits);
    unitsPerPixel = abs(dx.x);
    fragMap = vec2(gl_FragCoord.x, (dy.y >= 0.0 ? 1.0 : -1.0) * gl_FragCoord.y);
    blockGrid = artPixels * unitsPerPixel <= 1.0;
    art = blockGrid ? 1.0 : artPixels * unitsPerPixel;
    return artIndex(fragUnits);
}

// A hash of a world grid index that repeats with the anchor, so the pattern never jumps while panning.
float gridHash(vec2 p, float cell, uint salt) {
    uvec2 q = uvec2(mod(p, vec2(max(1.0, anchorPeriod / cell))));
    uint h = q.x * 1597334677u ^ q.y * 3812015801u ^ salt * 2654435761u;
    h ^= h >> 15u;
    h *= 2246822519u;
    h ^= h >> 13u;
    return float(h & 0xFFFFu) / 65535.0;
}

// The mask's green byte at the texel holding uv.
int maskCode(vec2 uv) {
    ivec2 t = clamp(ivec2(floor(uv * maskTexels)), ivec2(0), ivec2(int(maskTexels) - 1));
    return int(texelFetch(Sampler0, t, 0).g * 255.0 + 0.5);
}

float sampleKnown(vec2 uv) {
    vec4 mask = textureLod(Sampler0, uv, 0.0);
    return mix(mask.a, mask.r, revealProgress);
}

// Two blurred taps of known share, so chunk-shaped edges melt into rounded banks.
float knownBlur(vec2 uv, float units) {
    float r = max(units / unitsPerUv, 1.0 / maskTexels);
    float k = sampleKnown(uv) * 0.2;
    k += sampleKnown(uv + vec2(r, 0.0)) * 0.1;
    k += sampleKnown(uv - vec2(r, 0.0)) * 0.1;
    k += sampleKnown(uv + vec2(0.0, r)) * 0.1;
    k += sampleKnown(uv - vec2(0.0, r)) * 0.1;
    float d = r * 1.9;
    k += sampleKnown(uv + vec2(d, d)) * 0.1;
    k += sampleKnown(uv + vec2(-d, d)) * 0.1;
    k += sampleKnown(uv + vec2(d, -d)) * 0.1;
    k += sampleKnown(uv + vec2(-d, -d)) * 0.1;
    return k;
}

float noiseLayer(vec2 n) {
    // Speeds are whole periods per 1200 s, so the loop of GameTime wraps without a jump.
    float a = textureLod(Sampler1, n + vec2(5.0, 2.0) * seconds / 1200.0, 0.0).r;
    float b = textureLod(Sampler1, n + vec2(-8.0, 3.0) * seconds / 1200.0, 0.0).g;
    float c = textureLod(Sampler1, n + vec2(13.0, -9.0) * seconds / 1200.0, 0.0).b;
    return a * 0.55 + b * 0.3 + c * 0.15;
}

// Noise at the two whole scale steps around fogScale, blended, so zooming never pops.
float noiseAt(vec2 uv) {
    float coarsePeriod = NOISE_PERIOD * exp2(scaleSteps + 1.0);
    vec2 coarse = noiseOrigin + uv * unitsPerUv / coarsePeriod;
    float fine = noiseLayer(coarse * 2.0);
    return scaleFraction == 0.0 ? fine : mix(fine, noiseLayer(coarse), scaleFraction);
}

float thicknessFrom(float known, float n) {
    float unknown = 1.0 - known;
    // Noise only shapes the edge; where everything is known there is no fog at all.
    float edge = smoothstep(0.0, 0.35, unknown);
    float amount = smoothstep(0.12, 0.75, unknown + (n - 0.5) * 1.5 * edge);
    return amount * (0.45 + 0.75 * n);
}

// ---------------------------------------------------------------------------------------------
// Clouds, in pixel art
//
// Sampler0: known share (r, a), green 255 where every texel within reach is known, blue 255
// where none is. Sampler1: tileable noise. The bank's height at each art pixel comes from the
// noise and the known share; it is drawn as terraced puffs in flat tones, with shaded undersides
// and lit tops, and the clouds cast a dithered shadow on the map below them.

const float CLOUD = 0.2;

// Cloud height at a point, about 0..1.2; 0 where there is none.
float cloudAt(vec2 c) {
    vec2 uv = uvAt(c);
    ivec2 t = clamp(ivec2(floor(uv * maskTexels)), ivec2(0), ivec2(int(maskTexels) - 1));
    vec4 flags = texelFetch(Sampler0, t, 0);
    if (flags.g > 0.5) return 0.0;
    float n = noiseAt(uv);
    bool deep = flags.b > 0.5;
    float known = deep ? 0.0 : knownBlur(uv, blurUnits);
    float height = known >= 0.95 ? 0.0 : thicknessFrom(known, n);
    // Where there's no map at all, nothing shows through: the bank closes over the data's edge.
    // Deep in it, the noise is stretched so the puffs climb through all three steps.
    if (deep || sampleKnown(uv) < 0.45) height = max(height, 0.3 + 0.75 * smoothstep(0.32, 0.68, n));
    return height;
}

// The height step of a puff: -1 outside the clouds, then 0 to 2.
int puff(float height) {
    return height <= CLOUD ? -1 : height < 0.55 ? 0 : height < 0.8 ? 1 : 2;
}

void pixelClouds(vec2 index, bool cave) {
    vec2 c = artCentre(index);
    vec3 lit = cave ? vec3(0.27, 0.29, 0.36) : mix(vec3(0.15, 0.17, 0.25), vec3(0.98, 0.98, 1.0), daylight);
    vec3 shade = cave ? vec3(0.05, 0.055, 0.075) : mix(vec3(0.04, 0.05, 0.09), vec3(0.50, 0.56, 0.68), daylight);
    float here = cloudAt(c);
    if (underlay) {
        // Under the tiles: holes in the terrain show the bank's middle tone.
        if (here <= 0.0 && cloudAt(c + vec2(art, art)) <= 0.0) discard;
        fragColor = vec4(mix(shade, lit, 0.7), 1.0) * ColorModulator;
        return;
    }
    if (here <= CLOUD) {
        // The banks' shadow, a little south-east of them, every other pixel like a dithered sprite.
        if (mod(index.x + index.y, 2.0) == 0.0 && (cloudAt(c + art * vec2(-1.0, -2.0)) > CLOUD || cloudAt(c + art * vec2(-2.0, -3.0)) > CLOUD)) {
            fragColor = vec4(shade * 0.6, 0.38) * ColorModulator;
            return;
        }
        discard;
    }
    // Puffs in three heights, each a flat tone. A step down to the south is the shaded underside
    // of the puff above it; a step down to the north catches the light.
    float below = cloudAt(c + vec2(0.0, art));
    float above = cloudAt(c - vec2(0.0, art));
    int level = puff(here), south = puff(below), north = puff(above);
    vec3 color = level == 2 ? mix(shade, lit, 0.98) : level == 1 ? mix(shade, lit, 0.83) : mix(shade, lit, 0.66);
    if (south < 0) color = mix(shade, lit, 0.36);
    else if (south < level) color = mix(shade, lit, level == 2 ? 0.7 : 0.52);
    else if (north < level) color = mix(color, lit * 1.04, 0.6);
    fragColor = vec4(color, 1.0) * ColorModulator;
}

// ---------------------------------------------------------------------------------------------
// Antique map, in pixel art
//
// Sampler0: r/a the known endpoints, 0 or 1 per texel and only where terrain is complete;
// g bits 5-7 the predicted biome class, bit 3 set where every texel nearby is explored, bit 4
// where no terrain is; b the water share. Sampler1, the parchment, gives the paper its blotches.
// Colours are drawn in a sepia ink by default, so unknown ground stands apart from the terrain.

const vec3 PAPER = vec3(0.905, 0.815, 0.625);
const vec3 INK = vec3(0.235, 0.145, 0.075);
const vec3 BURNT = vec3(0.72, 0.565, 0.36);
const vec3 COAST = vec3(0.255, 0.27, 0.215);
const vec3 SHALLOW = vec3(0.585, 0.71, 0.70);
const vec3 DEEP = vec3(0.45, 0.59, 0.625);
const vec3 RIPPLE = vec3(0.54, 0.675, 0.69);
const vec3 FOAM = vec3(0.80, 0.875, 0.85);
// Known share at an art pixel's centre from which it counts as explored. Above one half, so the
// line stays inside explored texels and never uncovers ground the terrain doesn't cover.
const float EXPLORED = 0.75;

// A 16x16 sprite, two bits a pixel (0 none, 1 outline, 2 body, 3 highlight), a uint per row with
// the leftmost pixel in the lowest bits: two wavelets for open water. Drawn in
// docs/design/antique-sprites.py, which prints this array. It stands on its centre column at
// row 13. Land carries no sprites: its biomes read as washes.
const uint SPRITES[16] = uint[16](
    0x00000000u, 0x00000000u, 0x00000000u, 0x00000000u, 0x00000000u, 0x00000014u, 0x0000107Du, 0x00000500u,
    0x00000000u, 0x00050000u, 0x041F4000u, 0x01400000u, 0x00000000u, 0x00000000u, 0x00000000u, 0x00000000u
);

// Tileable noise, 0..1, from the parchment's alpha with features about `units` map units across.
// Anchored to the map and blended smoothly between power-of-two scales, so the frayed edge keeps
// its shape while panning and changes gradually, never in jumps, while zooming.
float frayNoise(vec2 c, float units, float salt) {
    float level = log2(units * 10.0);
    float l0 = floor(level);
    float a = textureLod(Sampler1, c / min(exp2(l0), anchorPeriod) + salt, 0.0).a;
    float b = textureLod(Sampler1, c / min(exp2(l0 + 1.0), anchorPeriod) + salt, 0.0).a;
    return smoothstep(0.3, 0.7, mix(a, b, fract(level)));
}

// Known share around a point at radius r (map units), from four diagonal taps.
float knownAround(vec2 uv, float r) {
    float d = r / unitsPerUv;
    return (sampleKnown(uv + vec2(d, d)) + sampleKnown(uv + vec2(-d, d)) + sampleKnown(uv + vec2(d, -d))
        + sampleKnown(uv - vec2(d, d))) * 0.25;
}

// Whether an art pixel shows explored terrain. The frontier frays like the edge of a hand-coloured
// map: near it, the share of known ground at two radii is weighed against noise at two scales,
// so the edge bites in by irregular amounts, wide lobes and small notches, and leaves the odd
// pocket of fog. Radii and noise are in blocks (growing only once art pixels outgrow them), so
// zooming keeps the same frayed edge. It only ever moves inward, inside ground the terrain
// covers. MapViewport's antique reach covers the widest radius.
bool explored(vec2 c) {
    vec2 uv = uvAt(c);
    if (sampleKnown(uv) < EXPLORED) return false;
    float fine = max(6.0, 2.0 * art), wide = max(22.0, 7.0 * art);
    if (knownAround(uv, fine) < 0.3 + 0.69 * frayNoise(c, fine * 1.6, 0.0)) return false;
    return knownAround(uv, wide) >= 0.35 + 0.6 * frayNoise(c, wide * 1.6, 0.37);
}

float wetAt(vec2 c) {
    return textureLod(Sampler0, uvAt(c), 0.0).b;
}

// The land class at a point: the class with the most weight among the four nearest texels, so
// borders between classes run as smooth curves rather than along texel edges. Water texels don't
// vote; 0 means no prediction.
int landClass(vec2 c) {
    vec2 t = uvAt(c) * maskTexels - 0.5;
    vec2 b = floor(t);
    vec2 f = t - b;
    int k[4] = int[4](maskCode((b + vec2(0.5, 0.5)) / maskTexels) >> 5, maskCode((b + vec2(1.5, 0.5)) / maskTexels) >> 5,
        maskCode((b + vec2(0.5, 1.5)) / maskTexels) >> 5, maskCode((b + vec2(1.5, 1.5)) / maskTexels) >> 5);
    float w[4] = float[4]((1.0 - f.x) * (1.0 - f.y), f.x * (1.0 - f.y), (1.0 - f.x) * f.y, f.x * f.y);
    int best = 0;
    float bestWeight = -1.0;
    for (int i = 0; i < 4; i++) {
        if (k[i] == 1) continue;
        float s = 0.0;
        for (int j = 0; j < 4; j++) if (k[j] == k[i]) s += w[j];
        if (s > bestWeight) { bestWeight = s; best = k[i]; }
    }
    return best;
}

vec3 classColor(int k) {
    if (k == 3) return vec3(0.56, 0.635, 0.395);   // forest
    if (k == 4) return vec3(0.645, 0.615, 0.555);  // mountains
    if (k == 5) return vec3(0.905, 0.785, 0.55);   // desert
    if (k == 6) return vec3(0.93, 0.935, 0.92);    // snow
    if (k == 7) return vec3(0.49, 0.545, 0.385);   // swamp
    return vec3(0.80, 0.78, 0.55);                 // plains, and land beside water
}

// Outline, body and highlight colours of the wave sprite.
vec3 spriteColor(uint v) {
    return v == 1u ? vec3(0.30, 0.44, 0.49) : v == 2u ? vec3(0.40, 0.55, 0.60) : vec3(0.84, 0.91, 0.90);
}

// Sepia ink: lightness on a ramp from dark umber to pale parchment. Fills keep to the light end,
// like washes on paper, so the inked strokes of coasts and sprites carry the drawing. Water is
// taken a little darker, so seas still read apart from land.
vec3 sepia(float l) {
    l = clamp(l, 0.0, 1.0);
    return l < 0.5 ? mix(vec3(0.20, 0.12, 0.06), vec3(0.58, 0.42, 0.25), l * 2.0)
        : mix(vec3(0.58, 0.42, 0.25), vec3(0.95, 0.875, 0.71), (l - 0.5) * 2.0);
}

vec3 inkFill(vec3 c, bool water) {
    if (colourful) return c;
    return sepia(0.5 + 0.5 * dot(c, vec3(0.299, 0.587, 0.114)) * (water ? 0.78 : 1.0));
}

vec3 inkLine(vec3 c) {
    return colourful ? c : sepia(dot(c, vec3(0.299, 0.587, 0.114)) * 0.85);
}

// The wave over art pixel `index`, if any, on deep water. Anchors sit on world points spaced a
// power of two of map units apart, at least 20 art pixels, so they keep their place while panning
// and their size while zooming out; zooming out an octave drops every other row and column.
vec4 waveAt(vec2 index) {
    float spacing = min(exp2(ceil(log2(20.0 * art))), anchorPeriod);
    vec2 home = floor(artCentre(index) / spacing);
    for (int y = 1; y >= 0; y--) {
        for (int x = -1; x <= 1; x++) {
            vec2 cell = home + vec2(float(x), float(y));
            if (gridHash(cell, spacing, 53u) >= 0.35) continue;
            float h1 = gridHash(cell, spacing, 11u), h2 = gridHash(cell, spacing, 23u);
            vec2 point = (cell + 0.5 + (vec2(h1, h2) - 0.5) * 0.5) * spacing;
            vec2 local = index - artIndex(point) + vec2(8.0, 13.0);
            if (any(lessThan(local, vec2(0.0))) || any(greaterThan(local, vec2(15.0)))) continue;
            uint v = (SPRITES[int(local.y)] >> (2u * uint(local.x))) & 3u;
            if (v == 0u || wetAt(point) <= 0.88) continue;
            return vec4(spriteColor(v), 1.0);
        }
    }
    return vec4(0.0);
}

// The paper: the parchment's large blotches in three flat tones, read at a scale where the
// blotches stay a few dozen art pixels across.
float paperTone(vec2 c) {
    float sheet = min(exp2(ceil(log2(art * 0.25))), max(1.0, anchorPeriod / 1024.0));
    float l = dot(textureLod(Sampler1, c / (1024.0 * sheet), 0.0).rgb, vec3(1.0 / 3.0));
    return l < 0.752 ? 0.955 : l > 0.784 ? 1.03 : 1.0;
}

// Unknown ground at an art pixel: paper, or the predicted biomes, coasts and waves.
vec3 cartography(vec2 index) {
    vec2 c = artCentre(index);
    float tone = paperTone(c);
    float wet = wetAt(c);
    bool water = wet >= 0.5;
    int k = water ? 1 : landClass(c);
    vec3 color;
    if (water) {
        color = wet < 0.62 ? SHALLOW : DEEP;
        // A dashed line offshore, as engraved charts draw shallows.
        if (wet >= 0.78 && wet < 0.815 && mod(index.x + 2.0 * index.y, 6.0) < 3.0) color = RIPPLE;
        for (int i = 0; i < 4; i++) {
            vec2 n = c + art * (i == 0 ? vec2(1.0, 0.0) : i == 1 ? vec2(-1.0, 0.0) : i == 2 ? vec2(0.0, 1.0) : vec2(0.0, -1.0));
            if (wetAt(n) < 0.5 && landClass(n) != 0) { color = FOAM; break; }
        }
    } else if (k == 0) {
        return inkFill(PAPER * tone, false);
    } else {
        color = classColor(k);
        bool coast = wetAt(c + vec2(art, 0.0)) >= 0.5 || wetAt(c - vec2(art, 0.0)) >= 0.5
            || wetAt(c + vec2(0.0, art)) >= 0.5 || wetAt(c - vec2(0.0, art)) >= 0.5;
        if (coast) {
            return inkLine(COAST);
        }
        // A one-pixel shade along borders between classes, on the east and south side of each.
        int west = landClass(c - vec2(art, 0.0));
        int north = landClass(c - vec2(0.0, art));
        if ((west != k && west != 0) || (north != k && north != 0)) color *= 0.86;
    }
    vec4 sprite = water ? waveAt(index) : vec4(0.0);
    return sprite.a > 0.0 ? inkLine(sprite.rgb) : inkFill(color * tone, water);
}

void antiqueMap(vec2 index) {
    vec2 c = artCentre(index);
    int flags = maskCode(uvAt(c));
    bool allExplored = (flags & 8) != 0, noneExplored = (flags & 16) != 0;
    if (underlay) {
        // Under the tiles: wherever terrain has holes, unknown ground shows, never a flat colour.
        if (allExplored) discard;
        fragColor = vec4(cartography(index), 1.0) * ColorModulator;
        return;
    }
    // Away from the frontier the pass underneath already shows all there is.
    if (allExplored || noneExplored) discard;
    if (explored(c)) {
        // A pixel of ink shadow just inside the frontier.
        if (explored(c + vec2(art, 0.0)) && explored(c - vec2(art, 0.0)) && explored(c + vec2(0.0, art)) && explored(c - vec2(0.0, art))) discard;
        fragColor = vec4(INK, 0.3) * ColorModulator;
        return;
    }
    // Rings around explored ground: an ink line, then two bands of scorched paper.
    int ring = 4;
    for (int i = 0; i < 8 && ring > 1; i++) {
        vec2 d = vec2(float(i % 3) - 1.0, float(i / 3) - 1.0);
        if (i >= 4) d = vec2(float((i + 1) % 3) - 1.0, float((i + 1) / 3) - 1.0);
        if (explored(c + art * d)) ring = 1;
    }
    for (int i = 0; i < 8 && ring > 2; i++) {
        vec2 d = i < 4 ? vec2(i == 0 ? 2.0 : i == 1 ? -2.0 : 0.0, i == 2 ? 2.0 : i == 3 ? -2.0 : 0.0)
            : vec2(i == 4 || i == 6 ? 2.0 : -2.0, i < 6 ? 1.0 : -1.0);
        if (explored(c + art * d)) ring = 2;
    }
    for (int i = 0; i < 4 && ring > 3; i++) {
        vec2 d = vec2(i == 0 ? 3.0 : i == 1 ? -3.0 : 0.0, i == 2 ? 3.0 : i == 3 ? -3.0 : 0.0);
        if (explored(c + art * d)) ring = 3;
    }
    vec3 color = cartography(index);
    if (ring == 1) color = INK;
    else if (ring == 2) color = mix(color, BURNT, 0.6);
    else if (ring == 3) color = mix(color, BURNT, 0.3);
    fragColor = vec4(color, 1.0) * ColorModulator;
}

// Explored terrain in the antique style: its own colours, lightly desaturated and warmed.
vec3 antiqueGrade(vec3 c) {
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    return clamp(mix(vec3(l), c, 0.66) * vec3(1.035, 0.99, 0.9) + vec3(0.018, 0.012, 0.0), 0.0, 1.0);
}

void main() {
    // Reserved tile-mode sentinel; impossible for fog at mask scale zero and noise step seven.
    if (params.a == 0.0 && params.b > .99 && params.r > .99 && params.g > .99) {
        vec4 terrain = texture(Sampler0, maskUv);
        if (terrain.a <= 0.0) discard;
        fragColor = vec4(antiqueGrade(terrain.rgb), terrain.a) * ColorModulator;
        return;
    }
    underlay = maskUv.x > UNDERLAY_SHIFT * 0.5;
    uv0 = underlay ? maskUv - vec2(UNDERLAY_SHIFT, 0.0) : maskUv;
    seconds = GameTime * 1200.0;
    // Alpha: mask scale (bits 4-7), antique (8), antique in colour (4), reveal progress high bits.
    // Red and green: noise offset (bits 5-7); green bits 1-4 reveal progress; red bits 0-4 and
    // green bit 0 the art pixel (size - 1 in bits 0-1, phase x in 2-3, phase y in 4-5).
    // Blue: cave (128), noise scale steps (bits 4-6), daylight in sixteenths (bits 0-3).
    noiseOrigin = floor((params.rg * 255.0 + 0.001) / 32.0) / 8.0;
    float alphaCode = floor(params.a * 255.0 + 0.5);
    maskTexels = float(textureSize(Sampler0, 0).x);
    unitsPerUv = maskTexels * 2.0 * exp2(floor(alphaCode / 16.0));
    bool antique = mod(floor(alphaCode / 8.0), 2.0) >= 1.0;
    colourful = mod(floor(alphaCode / 4.0), 2.0) >= 1.0;
    float greenCode = floor(params.g * 255.0 + 0.5);
    revealProgress = (mod(floor(greenCode / 2.0), 16.0) + 16.0 * mod(alphaCode, 4.0)) / 63.0;
    float pixel = mod(floor(params.r * 255.0 + 0.5), 32.0) + 32.0 * mod(greenCode, 2.0);
    artPixels = mod(pixel, 4.0) + 1.0;
    artPhase = vec2(mod(floor(pixel / 4.0), 4.0), floor(pixel / 16.0));
    float fogCode = floor(params.b * 255.0 + 0.5);
    bool cave = fogCode >= 128.0;
    fogCode = mod(fogCode, 128.0);
    scaleSteps = floor(fogCode / 16.0);
    daylight = mod(fogCode, 16.0) / 15.0;
    anchorPeriod = NOISE_PERIOD * exp2(scaleSteps + 1.0);
    fogHeight = cave ? 4.0 : 10.0;
    blurUnits = cave ? 2.5 : 7.0;
    vec2 index = setUpGrid();
    // The noise grows with the view past the detailed zoom (0.5 GUI pixels a block), blending the
    // two whole steps around it; the GUI pixel is close enough to the GUI scale for that.
    float scaleLog = log2(max(1.0, 0.5 * unitsPerPixel * artPixels));
    scaleFraction = clamp(scaleLog - scaleSteps, 0.0, 0.9375);
    fogScale = exp2(scaleSteps + scaleFraction);
    if (antique) {
        antiqueMap(index);
    } else {
        pixelClouds(index, cave);
    }
}
