#version 330
//? if >=26.3 {
#extension GL_ARB_separate_shader_objects : require
//?}

// The regions the player drew on the map (docs/design/map-regions.md), in pixel art on the map's
// art pixels: a line one art pixel thick along each region's edge, and its inside washed with its
// dye. Drawn over the tiles and the coast, under the fog; then once more over the fog, the lines
// alone and fainter, so borders run on across unexplored land while fills never cover it. Never
// baked into the tiles. The mask holds, per texel, the region's dye and the distance to its edge,
// measured exactly, so read smoothed between texels the edge falls between them, and lines stay
// smooth as well as one art pixel thick at any zoom.

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

// The region mask (RegionMask): red holds the dye (bits 0-3), the fill (4-5), a highlight (6)
// and whether the texel is inside (7); green the signed distance to the edge in eighths of a texel
// around 128; blue a tag telling regions apart.
uniform sampler2D Sampler0;
// The fog's tileable noise; not used by the plain look yet.
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

// Map units down the map per block of depth, at the map's 45 degree pitch (see Projection).
const float ROW = 0.75;

// The sixteen dyes in vanilla order, as banners are tinted (BannerDesign.Dye).
const vec3 DYES[16] = vec3[16](
    vec3(0.976, 1.000, 0.996), vec3(0.976, 0.502, 0.114), vec3(0.780, 0.306, 0.741), vec3(0.227, 0.702, 0.855),
    vec3(0.996, 0.847, 0.239), vec3(0.502, 0.780, 0.122), vec3(0.953, 0.545, 0.667), vec3(0.278, 0.310, 0.322),
    vec3(0.616, 0.616, 0.592), vec3(0.086, 0.612, 0.612), vec3(0.537, 0.196, 0.722), vec3(0.235, 0.267, 0.667),
    vec3(0.514, 0.329, 0.196), vec3(0.369, 0.486, 0.086), vec3(0.690, 0.180, 0.149), vec3(0.114, 0.114, 0.129)
);
// How much of its dye each fill lays down: none, light, normal, strong.
const float FILLS[4] = float[4](0.0, 0.16, 0.30, 0.48);

vec2 origin;
float unitsPerUv;
float texel;
ivec2 texels;
float daylight;

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
// The regions

vec4 cellAt(vec2 c) {
    ivec2 t = clamp(ivec2(floor(uvAt(c) * vec2(texels))), ivec2(0), texels - 1);
    return texelFetch(Sampler0, t, 0);
}

// Texels from the edge of the region at the point drawn at c, smoothed between texels: positive
// inside it, negative outside every region.
float edgeDistance(vec2 c) {
    return (textureLod(Sampler0, uvAt(c), 0.0).g * 255.0 - 128.0) / 8.0;
}

// The tag of the region the point drawn at c is in, or 0 outside every region.
int regionAt(vec2 c) {
    if (edgeDistance(c) <= 0.0) return 0;
    return int(cellAt(c).b * 255.0 + 0.5);
}

void main() {
    // Alpha: texel size code, log2 of the texel over a map pixel (bits 4-7), daylight in
    // fifteenths (0-3). Red: the mask origin's u modulo 256 in fours (0-5), lines only (6). Green:
    // its v modulo 768 in fours. Blue: art pixel size - 1 (0-1), phase x (2-3), phase y (4-5).
    float a = floor(params.a * 255.0 + 0.5);
    float r = floor(params.r * 255.0 + 0.5);
    float g = floor(params.g * 255.0 + 0.5);
    float b = floor(params.b * 255.0 + 0.5);
    texel = 0.25 * exp2(floor(a / 16.0));
    daylight = mod(a, 16.0) / 15.0;
    bool linesOnly = mod(floor(r / 64.0), 2.0) >= 1.0;
    texels = textureSize(Sampler0, 0);
    unitsPerUv = float(texels.x) * texel;
    origin = vec2(mod(r, 64.0), g) * 4.0;
    artPixels = mod(b, 4.0) + 1.0;
    artPhase = vec2(mod(floor(b / 4.0), 4.0), mod(floor(b / 16.0), 4.0));

    vec2 index = setUpGrid();
    vec2 c = artCentre(index);
    int tag = regionAt(c);
    if (tag == 0) discard;
    int bits = int(cellAt(c).r * 255.0 + 0.5);
    vec3 dye = DYES[bits & 15];
    float fill = FILLS[(bits >> 4) & 3];
    bool lit = ((bits >> 6) & 1) != 0;
    // A line where any of the four art pixels beside this one is in another region or none: one
    // art pixel thick, on the inside of the edge.
    vec2 step = blockGrid ? vec2(1.0, ROW) : vec2(art);
    bool edge = regionAt(c + vec2(step.x, 0.0)) != tag || regionAt(c - vec2(step.x, 0.0)) != tag
        || regionAt(c + vec2(0.0, step.y)) != tag || regionAt(c - vec2(0.0, step.y)) != tag;
    vec4 color;
    if (edge) {
        color = vec4(dye, linesOnly ? 0.55 : lit ? 1.0 : 0.9);
    } else {
        if (linesOnly) discard;
        color = vec4(dye, fill + (lit ? 0.12 : 0.0));
    }
    if (color.a <= 0.0) discard;
    // Dimmed by night like the ground under it, never quite dark.
    vec3 night = mix(vec3(0.42, 0.46, 0.60), vec3(1.0), daylight);
    fragColor = vec4(color.rgb * night, color.a) * ColorModulator;
}
