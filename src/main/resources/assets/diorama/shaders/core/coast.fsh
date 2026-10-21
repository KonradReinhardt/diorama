#version 330
//? if >=26.3 {
#extension GL_ARB_separate_shader_objects : require
//?}

// Foam and ripples along the coasts of the explored map, in pixel art on the map's art pixels.
// Drawn over the tiles, under the clouds and the fog, never baked into them: see
// docs/design/coast.md. Foam breathes in and out along the water's edge, broken here and there,
// and dashed ripples roll in toward the shore. Distances from the shore are measured on the
// water's plane in world blocks, so the lines follow the map's perspective like its blocks.

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

// The coast mask (CoastMask): for water, how far the nearest land is in sixteenths of a block in
// red; green is water, blue land.
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
// Blocks from the shore: the farthest ripple, and the spacing between ripples.
const float REACH = 11.0;
const float SPACING = 3.5;

vec2 origin;
float unitsPerUv;
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
    ivec2 texels = textureSize(Sampler0, 0);
    unitsPerUv = float(texels.x) * texel;
    origin = vec2(mod(r, 64.0), g) * 4.0;
    artPixels = mod(b, 4.0) + 1.0;
    artPhase = vec2(mod(floor(b / 4.0), 4.0), mod(floor(b / 16.0), 4.0));
    seconds = GameTime * 1200.0;

    vec2 index = setUpGrid();
    // Lines a block or two wide would only shimmer once an art pixel spans several blocks.
    float fade = 1.0 - smoothstep(2.0, 5.0, art);
    if (fade <= 0.0) discard;
    vec2 c = artCentre(index);
    vec2 uv = uvAt(c);
    ivec2 t = clamp(ivec2(floor(uv * vec2(texels))), ivec2(0), texels - 1);
    if (texelFetch(Sampler0, t, 0).g < 0.5) discard;
    // Blocks to the nearest land, smoothed between texels; and where on the water's plane.
    float d = textureLod(Sampler0, uv, 0.0).r * 255.0 / 16.0;
    if (d >= REACH) discard;
    vec2 p = vec2(c.x, c.y / ROW);
    // Each stretch of coast keeps its own beat, so the foam rolls along the shore. Speeds are
    // whole cycles per GameTime loop (1200 s), so it wraps without a jump.
    float beat = noise(p, 32.0, 1);
    float swell = 0.5 + 0.5 * sin(6.2831853 * (seconds / 4.0 + beat * 2.0));
    // At least half an art pixel, so foam never vanishes between two zoom steps.
    float pixelBlocks = art / ROW * 0.5;
    float foam = max(pixelBlocks, 0.4 + 0.9 * swell);
    vec3 white = sepia ? vec3(0.97, 0.93, 0.82) : vec3(0.95, 0.98, 1.0);
    vec3 pale = sepia ? vec3(0.86, 0.79, 0.64) : vec3(0.80, 0.91, 0.97);
    vec3 night = mix(vec3(0.30, 0.34, 0.48), vec3(1.0), daylight);
    vec4 color;
    if (d < foam) {
        // Broken into clumps here and there, less so at the crest of the swell.
        if (noise(p + vec2(seconds * 32.0 / 120.0, 0.0), 8.0, 3) < 0.30 - 0.15 * swell) discard;
        color = vec4(white, d < foam - 0.6 ? 0.85 : 0.6);
    } else if (d < foam + 0.6 && noise(p, 8.0, 2) > 0.55) {
        // A few bubbles left behind as the foam draws back.
        color = vec4(white, 0.35);
    } else {
        // Ripples a few blocks apart rolling in, each a dashed line about an art pixel thick,
        // fainter further out.
        float wave = (d + seconds * 0.7 + beat * SPACING) / SPACING;
        float width = max(0.35, pixelBlocks) / SPACING;
        if (fract(wave) >= width || d < 1.5) discard;
        if (noise(p + vec2(0.0, floor(wave) * 37.0), 16.0, 1) < 0.42) discard;
        color = vec4(pale, 0.5 * (1.0 - smoothstep(4.0, REACH, d)));
    }
    fragColor = vec4(color.rgb * night, color.a * fade) * ColorModulator;
}
