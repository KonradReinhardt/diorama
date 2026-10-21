#version 330
//? if >=26.3 {
#extension GL_ARB_separate_shader_objects : require
//?}

// Foam, ripples and ocean waves along the coasts of the explored map, in pixel art on the map's
// art pixels. Drawn over the tiles, under the clouds and the fog, never baked into them: see
// docs/design/coast.md. Foam breathes in and out along the water's edge, dashed ripples roll in
// on calm water, and on the open sea waves rise, roll in and break into a surge of foam.
// Distances from the shore are measured on the water's plane in world blocks, so the lines follow
// the map's perspective like its blocks, and every line is one art pixel thick at any zoom.

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

// The coast mask (CoastMask): how far the nearest shore is in red (see distanceAt), how much
// the water is open sea in green, and in blue (low byte) and alpha (high) which of the texel's
// four by four parts are water.
uniform sampler2D Sampler0;
// The fog's tileable noise, a coarser scale in each channel from alpha to red.
uniform sampler2D Sampler1;

//? if >=26.3 {
layout(location = 0) in vec2 maskUv;
layout(location = 1) in vec4 params;

layout(location = 0) out vec4 fragColor;
//?} else {
/*in vec2 maskUv;
in vec4 params;

out vec4 fragColor;
*///?}

// Map units down the map per block of depth, at the map's 45 degree pitch: a block's top is 1 by
// ROW (4 by 3 pixels; see Projection).
const float ROW = 0.75;
// Blocks from the shore past which nothing is drawn: where ocean waves rise.
const float REACH = 18.0;
// Ripples on calm water: blocks apart, how far out they show, and how fast they roll in.
const float SPACING = 3.5;
const float RIPPLES = 11.0;
const float RIPPLE_SPEED = 0.7;
// Ocean waves: seconds between them, how far out they rise, and the share of a wave's time spent
// rolling in before it breaks on the shore.
const float WAVE_PERIOD = 7.5;
const float WAVE_START = 16.0;
const float ROLL = 0.85;

vec2 origin;
float unitsPerUv;
float texel;
ivec2 texels;
float seconds;
float daylight;
bool sepia;

// ---------------------------------------------------------------------------------------------
// The art pixel grid, as in fog.fsh: one GUI pixel on a grid aligned to the framebuffer; or,
// once a block is bigger than that, the blocks' own faces, 1 unit across and ROW down.

float artPixels;
vec2 artPhase;
float unitsPerPixel;
float art;
bool blockGrid;
vec2 fragMap;
vec2 fragUnits;

vec2 unitsAt(vec2 uv) {
    return origin + uv * unitsPerUv;
}

vec2 uvAt(vec2 units) {
    return (units - origin) / unitsPerUv;
}

vec2 artIndex(vec2 units) {
    if (blockGrid) return floor(units / vec2(1.0, ROW));
    return floor((fragMap + (units - fragUnits) / unitsPerPixel + artPhase) / artPixels);
}

vec2 artCentre(vec2 index) {
    if (blockGrid) return (index + 0.5) * vec2(1.0, ROW);
    return fragUnits + ((index + 0.5) * artPixels - artPhase - fragMap) * unitsPerPixel;
}

vec2 setUpGrid() {
    fragUnits = unitsAt(maskUv);
    vec2 dx = dFdx(fragUnits), dy = dFdy(fragUnits);
    unitsPerPixel = abs(dx.x);
    fragMap = vec2(gl_FragCoord.x, (dy.y >= 0.0 ? 1.0 : -1.0) * gl_FragCoord.y);
    blockGrid = artPixels * unitsPerPixel <= 1.0;
    art = blockGrid ? 1.0 : artPixels * unitsPerPixel;
    return artIndex(fragUnits);
}

// ---------------------------------------------------------------------------------------------
// The coast

// Tileable noise at a point of the water's plane, (u, depth) in blocks, repeating every 4 x
// `blocks` (a power of two up to 64), so with the origin's periods: MapViewport keeps it modulo
// 256 blocks across and 768 map units down, 1024 blocks of depth, and nothing jumps while panning.
float noise(vec2 p, float blocks, int channel) {
    vec4 n = textureLod(Sampler1, p / (blocks * 4.0), 0.0);
    return channel == 0 ? n.r : channel == 1 ? n.g : channel == 2 ? n.b : n.a;
}

// Blocks to the shore from red, the square root of its share of the farthest the mask tells
// apart (CoastMask.reach and encode).
float decode(float red) {
    return red * red * 32.0 * max(1.0, texel);
}

// Blocks to the nearest shore at a point drawn at c, smoothed between texels.
float distanceAt(vec2 c) {
    return decode(textureLod(Sampler0, uvAt(c), 0.0).r);
}

// Whether the point drawn at c is water: the part of its mask texel it falls in, so nothing is
// drawn on land even when a texel spans several blocks.
bool waterAt(vec2 c) {
    vec2 at = uvAt(c) * vec2(texels);
    ivec2 t = clamp(ivec2(floor(at)), ivec2(0), texels - 1);
    vec4 cell = texelFetch(Sampler0, t, 0);
    int parts = int(cell.b * 255.0 + 0.5) | int(cell.a * 255.0 + 0.5) << 8;
    ivec2 part = clamp(ivec2(floor(fract(at) * 4.0)), ivec2(0), ivec2(3));
    return ((parts >> (part.y * 4 + part.x)) & 1) != 0;
}

// Whether a line `at` blocks from the shore runs through this art pixel, which spans `pixel`
// blocks of distance: lines are one art pixel thick at every zoom.
bool onLine(float d, float at, float pixel) {
    return abs(d - at) < 0.5 * pixel;
}

void main() {
    // Alpha: texel size code, log2 of the texel over a map pixel (bits 4-7), daylight in
    // fifteenths (0-3). Red: the mask origin's u modulo 256 in fours (0-5), sepia (6). Green: its
    // v modulo 768 in fours. Blue: art pixel size - 1 (0-1), phase x (2-3), phase y (4-5).
    float a = floor(params.a * 255.0 + 0.5);
    float r = floor(params.r * 255.0 + 0.5);
    float g = floor(params.g * 255.0 + 0.5);
    float b = floor(params.b * 255.0 + 0.5);
    texel = 0.25 * exp2(floor(a / 16.0));
    sepia = mod(floor(r / 64.0), 2.0) >= 1.0;
    daylight = mod(a, 16.0) / 15.0;
    texels = textureSize(Sampler0, 0);
    unitsPerUv = float(texels.x) * texel;
    origin = vec2(mod(r, 64.0), g) * 4.0;
    artPixels = mod(b, 4.0) + 1.0;
    artPhase = vec2(mod(floor(b / 4.0), 4.0), mod(floor(b / 16.0), 4.0));
    seconds = GameTime * 1200.0;

    vec2 index = setUpGrid();
    vec2 c = artCentre(index);
    if (!waterAt(c)) discard;
    vec4 m = textureLod(Sampler0, uvAt(c), 0.0);
    float d = decode(m.r);
    float sea = smoothstep(0.1, 0.9, m.g);
    // How many blocks of distance one art pixel spans here, from its neighbours: across the shore
    // it is about a pixel's size, along it nearly nothing, so lines come out a pixel thick.
    vec2 step = blockGrid ? vec2(1.0, ROW) : vec2(art);
    float pixel = max(max(abs(distanceAt(c + vec2(step.x, 0.0)) - d), abs(distanceAt(c + vec2(0.0, step.y)) - d)), 0.1);
    // Ripples that would crowd closer than three pixels thin out, every other one fading away as
    // the zoom goes out, so far out they stand further apart and reach further.
    float level = log2(max(1.0, 3.0 * pixel / SPACING));
    float spacing = SPACING * exp2(floor(level));
    float ripples = max(RIPPLES, 3.2 * spacing);
    if (d >= max(REACH, ripples)) discard;
    // Waves and their surge would be smaller than a pixel far out: they fade, foam and ripples stay.
    float waves = sea * (1.0 - smoothstep(1.5, 4.0, pixel));
    // Where on the water's plane, in blocks.
    vec2 p = vec2(c.x, c.y / ROW);
    // Each stretch of coast keeps its own beat, so the swell rolls along the shore. Every speed
    // is whole cycles per GameTime loop (1200 s), so it wraps without a jump.
    float beat = noise(p, 64.0, 1);
    float swell = 0.5 + 0.5 * sin(6.2831853 * (seconds / 4.0 + beat * 3.0));
    // Up close foam breaks into clumps; further out that would only shimmer.
    float detail = 1.0 - smoothstep(0.75, 2.0, art);

    // Ocean waves: one rolls in every WAVE_PERIOD seconds, at slightly different times along the
    // coast, and breaks on the shore, flaring the foam and leaving bubbles as it draws back.
    float phase = fract(seconds / WAVE_PERIOD + beat * 1.7);
    float surge = fract(phase - ROLL);
    float rush = smoothstep(0.0, 0.06, surge) * (1.0 - smoothstep(0.06, 0.5, surge));
    // Foam is the water's edge itself, one art pixel at every zoom: water with land beside it, in
    // any of the four directions, that is the shore and not land standing over water it hides
    // (or ground not explored), which lie far from any shore.
    bool edge = !waterAt(c + vec2(step.x, 0.0)) || !waterAt(c - vec2(step.x, 0.0))
        || !waterAt(c + vec2(0.0, step.y)) || !waterAt(c - vec2(0.0, step.y));
    bool foam = edge && d < max(2.0 * pixel, 1.0 + texel);

    vec3 white = sepia ? vec3(0.97, 0.93, 0.82) : vec3(0.95, 0.98, 1.0);
    vec3 pale = sepia ? vec3(0.86, 0.79, 0.64) : vec3(0.78, 0.90, 0.97);
    vec3 night = mix(vec3(0.30, 0.34, 0.48), vec3(1.0), daylight);
    vec4 color = vec4(0.0);
    if (foam) {
        // Breathing with the swell, brightest as a wave breaks; up close, broken here and there.
        float gaps = (0.30 - 0.15 * swell) * (1.0 - 0.6 * waves * rush) * detail;
        if (noise(p + vec2(seconds * 32.0 / 120.0, 0.0), 8.0, 3) >= gaps) {
            color = vec4(white, max(0.6 + 0.25 * swell, 0.95 * waves * rush));
        }
    }
    if (color.a == 0.0 && waves > 0.0 && phase < ROLL) {
        // The crest, a broken white line rising as it nears the shore, and the lighter water of
        // its back a pixel seaward.
        float crest = WAVE_START * (1.0 - phase / ROLL);
        float rise = waves * (0.25 + 0.6 * (1.0 - crest / WAVE_START));
        bool whole = noise(p + vec2(seconds * 64.0 / 1200.0, 0.0), 16.0, 1) >= 0.32;
        if (whole && crest > 2.0 * pixel && onLine(d, crest, pixel)) color = vec4(white, 0.8 * rise);
        else if (whole && crest > 2.0 * pixel && onLine(d, crest + pixel, pixel)) color = vec4(pale, 0.45 * rise);
    }
    if (color.a == 0.0 && waves > 0.0 && d > pixel && d < 0.3 + 1.5 * (1.0 - smoothstep(0.06, 0.5, surge)) && noise(p, 8.0, 2) > 0.62) {
        // Bubbles left behind as the surge draws back, a pixel each.
        color = vec4(white, 0.4 * waves * detail);
    }
    if (color.a == 0.0 && waves < 1.0 && d > 2.0 * pixel && d < ripples) {
        // Ripples on calm water, a few blocks apart, dashed; where waves roll in they give way.
        float wave = (d + seconds * RIPPLE_SPEED + beat * SPACING) / spacing;
        float n = floor(wave + 0.5);
        float keep = mod(n, 2.0) == 0.0 ? 1.0 : 1.0 - fract(level);
        if (onLine(d, (n - seconds * RIPPLE_SPEED / spacing - beat * SPACING / spacing) * spacing, pixel)
            && noise(p + vec2(0.0, n * 37.0), 16.0, 1) >= 0.42) {
            color = vec4(pale, 0.45 * keep * (1.0 - waves) * (1.0 - smoothstep(0.35 * ripples, ripples, d)));
        }
    }
    if (color.a <= 0.0) discard;
    fragColor = vec4(color.rgb * night, color.a) * ColorModulator;
}
