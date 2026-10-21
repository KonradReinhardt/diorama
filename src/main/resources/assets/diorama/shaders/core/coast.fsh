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

// The coast mask (CoastMask): how far the nearest shore is in eighths of a block in red, how much
// the water is open sea in green, and water in blue.
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

// Blocks to the nearest shore at a point drawn at c, smoothed between texels.
float distanceAt(vec2 c) {
    return textureLod(Sampler0, uvAt(c), 0.0).r * 255.0 / 8.0;
}

// Whether a line `at` blocks from the shore runs through this art pixel, which spans `pixel`
// blocks of distance: lines are one art pixel thick at every zoom.
bool onLine(float d, float at, float pixel) {
    return abs(d - at) < 0.5 * pixel;
}

void main() {
    // Alpha: texel size code, log2 of the texel over a map pixel (bits 5-7), sepia (4), daylight
    // in fifteenths (0-3). Red: the mask origin's u modulo 256 in fours (0-5). Green: its v
    // modulo 768 in fours. Blue: art pixel size - 1 (0-1), phase x (2-3), phase y (4-5).
    float a = floor(params.a * 255.0 + 0.5);
    float r = floor(params.r * 255.0 + 0.5);
    float g = floor(params.g * 255.0 + 0.5);
    float b = floor(params.b * 255.0 + 0.5);
    float texel = 0.25 * exp2(floor(a / 32.0));
    sepia = mod(floor(a / 16.0), 2.0) >= 1.0;
    daylight = mod(a, 16.0) / 15.0;
    texels = textureSize(Sampler0, 0);
    unitsPerUv = float(texels.x) * texel;
    origin = vec2(mod(r, 64.0), g) * 4.0;
    artPixels = mod(b, 4.0) + 1.0;
    artPhase = vec2(mod(floor(b / 4.0), 4.0), mod(floor(b / 16.0), 4.0));
    seconds = GameTime * 1200.0;

    vec2 index = setUpGrid();
    // Once an art pixel spans several blocks the coast is a line or two of pixels: it fades out.
    float fade = 1.0 - smoothstep(3.0, 6.0, art);
    if (fade <= 0.0) discard;
    vec2 c = artCentre(index);
    ivec2 t = clamp(ivec2(floor(uvAt(c) * vec2(texels))), ivec2(0), texels - 1);
    if (texelFetch(Sampler0, t, 0).b < 0.5) discard;
    vec4 m = textureLod(Sampler0, uvAt(c), 0.0);
    float d = m.r * 255.0 / 8.0;
    float sea = smoothstep(0.1, 0.9, m.g);
    if (d >= REACH) discard;
    // How many blocks of distance one art pixel spans here, from its neighbours: across the shore
    // it is about a pixel's size, along it nearly nothing, so lines come out a pixel thick.
    vec2 step = blockGrid ? vec2(1.0, ROW) : vec2(art);
    float pixel = max(max(abs(distanceAt(c + vec2(step.x, 0.0)) - d), abs(distanceAt(c + vec2(0.0, step.y)) - d)), 0.1);
    // Where on the water's plane, in blocks.
    vec2 p = vec2(c.x, c.y / ROW);
    // Each stretch of coast keeps its own beat, so the swell rolls along the shore. Every speed
    // is whole cycles per GameTime loop (1200 s), so it wraps without a jump.
    float beat = noise(p, 64.0, 1);
    float swell = 0.5 + 0.5 * sin(6.2831853 * (seconds / 4.0 + beat * 3.0));
    // Up close foam breaks into clumps; further out that would only shimmer.
    float detail = 1.0 - smoothstep(0.75, 2.0, art);

    // Ocean waves: one rolls in every WAVE_PERIOD seconds, at slightly different times along the
    // coast, and breaks into a surge of foam that runs up the shore and draws back.
    float phase = fract(seconds / WAVE_PERIOD + beat * 1.7);
    float surge = fract(phase - ROLL);
    float rush = smoothstep(0.0, 0.06, surge) * (1.0 - smoothstep(0.06, 0.5, surge));
    float calm = 0.35 + 0.55 * swell;
    float foam = max(pixel, mix(calm, max(calm, 0.3 + 2.4 * rush), sea));

    vec3 white = sepia ? vec3(0.97, 0.93, 0.82) : vec3(0.95, 0.98, 1.0);
    vec3 pale = sepia ? vec3(0.86, 0.79, 0.64) : vec3(0.78, 0.90, 0.97);
    vec3 night = mix(vec3(0.30, 0.34, 0.48), vec3(1.0), daylight);
    vec4 color = vec4(0.0);
    if (d < foam) {
        float gaps = (0.30 - 0.15 * swell) * (1.0 - 0.6 * sea * rush) * detail;
        if (noise(p + vec2(seconds * 32.0 / 120.0, 0.0), 8.0, 3) >= gaps) {
            color = vec4(white, d < foam - pixel ? 0.9 : 0.65);
        }
    }
    if (color.a == 0.0 && sea > 0.0 && phase < ROLL) {
        // The crest, a broken white line rising as it nears the shore, and the lighter water of
        // its back a pixel seaward.
        float crest = WAVE_START * (1.0 - phase / ROLL);
        float rise = sea * (0.25 + 0.6 * (1.0 - crest / WAVE_START));
        bool whole = noise(p + vec2(seconds * 64.0 / 1200.0, 0.0), 16.0, 1) >= 0.32;
        if (whole && crest > foam && onLine(d, crest, pixel)) color = vec4(white, 0.8 * rise);
        else if (whole && crest > foam && onLine(d, crest + pixel, pixel)) color = vec4(pale, 0.45 * rise);
    }
    if (color.a == 0.0 && sea > 0.0 && d < 0.3 + 2.4 * (1.0 - smoothstep(0.06, 0.5, surge)) && noise(p, 8.0, 2) > 0.62) {
        // Bubbles left on the sand as the surge draws back.
        color = vec4(white, 0.4 * sea * detail);
    }
    if (color.a == 0.0 && sea < 1.0 && d > foam + pixel && d < RIPPLES) {
        // Ripples on calm water, a few blocks apart, dashed. Further out, where they would crowd
        // closer than three pixels, every other one fades away, then every other again.
        float level = log2(max(1.0, 3.0 * pixel / SPACING));
        float spacing = SPACING * exp2(floor(level));
        float wave = (d + seconds * RIPPLE_SPEED + beat * SPACING) / spacing;
        float n = floor(wave + 0.5);
        float keep = mod(n, 2.0) == 0.0 ? 1.0 : 1.0 - fract(level);
        if (onLine(d, (n - seconds * RIPPLE_SPEED / spacing - beat * SPACING / spacing) * spacing, pixel)
            && noise(p + vec2(0.0, n * 37.0), 16.0, 1) >= 0.42) {
            color = vec4(pale, 0.45 * keep * (1.0 - sea) * (1.0 - smoothstep(4.0, RIPPLES, d)));
        }
    }
    if (color.a <= 0.0) discard;
    fragColor = vec4(color.rgb * night, color.a * fade) * ColorModulator;
}
