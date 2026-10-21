#version 330
//? if >=26.3 {
#extension GL_ARB_separate_shader_objects : require
//?}

// The regions the player drew on the map (docs/design/map-regions.md), in pixel art on the map's
// art pixels: a line along each region's edge, and its inside washed with its dye, in the region's
// style (RegionStyle): plain, outlined, painted with or without ink, or an old map's dash-dot border
// and hand-tinted band. Optionally a band of the dye along the inside of every border, fading
// inwards, as strategy games draw territories. Drawn over the tiles and the coast, under the fog; then once more over the fog, the lines
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
// and whether the texel is inside (7); green the signed distance to the edge in quarters of a
// texel around 128; blue a tag telling regions apart; alpha the style (bits 0-2).
uniform sampler2D Sampler0;
// The fog's tileable noise, a coarser scale in each channel from alpha to red: the painted looks'
// paper grain and the wavering of their pen.
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
// The painted map's sepia ink, and an old map's darker one (fog.fsh's INK).
const vec3 PAINT_INK = vec3(0.30, 0.19, 0.13);
const vec3 OLD_INK = vec3(0.235, 0.145, 0.075);
// The styles, as RegionStyle.shaderIndex().
const int PLAIN = 0, OUTLINED = 1, PAINTED = 2, PAINTED_NO_INK = 3, ANTIQUE = 4, ANTIQUE_COLOR = 5;
// The border band's width in art pixels, off, narrow and wide, and how much dye it lays at the border.
const float BAND_WIDTH[3] = float[3](0.0, 4.0, 8.0);
const float BAND_ALPHA[3] = float[3](0.0, 0.6, 0.55);

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
    return (textureLod(Sampler0, uvAt(c), 0.0).g * 255.0 - 128.0) / 4.0;
}

// The tag of the region the point drawn at c is in, or 0 outside every region.
int regionAt(vec2 c) {
    if (edgeDistance(c) <= 0.0) return 0;
    return int(cellAt(c).b * 255.0 + 0.5);
}

// Whether an art pixel within k art pixels of this one, straight across or down, is in another
// region or none: k = 1 is the line along the edge, one art pixel thick; k = 2 the line inside it.
bool nearEdge(vec2 c, vec2 step, float k, int tag) {
    for (float j = 1.0; j <= k; j += 1.0) {
        if (regionAt(c + vec2(step.x * j, 0.0)) != tag || regionAt(c - vec2(step.x * j, 0.0)) != tag
            || regionAt(c + vec2(0.0, step.y * j)) != tag || regionAt(c - vec2(0.0, step.y * j)) != tag) return true;
    }
    return false;
}

// Tileable noise at a point of the ground's plane, (u, depth) in blocks, as coast.fsh's.
float noise(vec2 p, float blocks, int channel) {
    vec4 n = textureLod(Sampler1, p / (blocks * 4.0), 0.0);
    return channel == 0 ? n.r : channel == 1 ? n.g : channel == 2 ? n.b : n.a;
}

vec3 sepia(float l) {
    l = clamp(l, 0.0, 1.0);
    return l < 0.5 ? mix(vec3(0.20, 0.12, 0.06), vec3(0.58, 0.42, 0.25), l * 2.0)
        : mix(vec3(0.58, 0.42, 0.25), vec3(0.95, 0.875, 0.71), (l - 0.5) * 2.0);
}

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

// A dye bright enough to read over the map: the dark ones are lightened.
vec3 readable(vec3 c) {
    return luma(c) < 0.27 ? mix(c, vec3(1.0), 0.4) : c;
}

void main() {
    // Alpha: texel size code, log2 of the texel over a map pixel (bits 4-7), daylight in
    // fifteenths (0-3). Red: the mask origin's u modulo 256 in fours (0-5), lines only (6). Green:
    // its v modulo 768 in fours. Blue: art pixel size - 1 (0-1), phase x (2-3), phase y (4-5), the
    // border band (6-7).
    float a = floor(params.a * 255.0 + 0.5);
    float r = floor(params.r * 255.0 + 0.5);
    float g = floor(params.g * 255.0 + 0.5);
    float b = floor(params.b * 255.0 + 0.5);
    texel = 0.25 * exp2(floor(a / 16.0));
    daylight = mod(a, 16.0) / 15.0;
    bool linesOnly = mod(floor(r / 64.0), 2.0) >= 1.0;
    int bandKind = min(2, int(floor(b / 64.0)));
    texels = textureSize(Sampler0, 0);
    unitsPerUv = float(texels.x) * texel;
    origin = vec2(mod(r, 64.0), g) * 4.0;
    artPixels = mod(b, 4.0) + 1.0;
    artPhase = vec2(mod(floor(b / 4.0), 4.0), mod(floor(b / 16.0), 4.0));

    vec2 index = setUpGrid();
    vec2 c = artCentre(index);
    int tag = regionAt(c);
    if (tag == 0) discard;
    vec4 cell = cellAt(c);
    int bits = int(cell.r * 255.0 + 0.5);
    int look = int(cell.a * 255.0 + 0.5) & 7;
    vec3 dye = DYES[bits & 15];
    float fill = FILLS[(bits >> 4) & 3];
    float lit = ((bits >> 6) & 1) != 0 ? 0.12 : 0.0;
    vec2 step = blockGrid ? vec2(1.0, ROW) : vec2(art);
    // The line along the edge: one art pixel inside it wherever another region, or none, is beside.
    bool edge = nearEdge(c, step, 1.0, tag);
    // Art pixels from the edge, as far as the mask tells (a few at the finest zoom).
    float d = max(0.0, edgeDistance(c)) * texel / art;
    // Where on the ground's plane, in blocks, for paper and pen.
    vec2 p = vec2(c.x, c.y / ROW);
    vec4 color = vec4(0.0);

    if (look == PLAIN) {
        if (edge) color = vec4(readable(dye), linesOnly ? 0.55 : 0.9 + lit);
        else if (!linesOnly) color = vec4(dye, fill + lit);
    } else if (look == OUTLINED) {
        // Dark ink outside, the dye inside it, and a band of stronger dye along the edge, as a
        // political map has; with no fill the band stays.
        if (edge) color = vec4(dye * 0.35, linesOnly ? 0.55 : 0.95);
        else if (linesOnly) discard;
        else if (nearEdge(c, step, 2.0, tag)) color = vec4(readable(dye), 0.9 + lit);
        else color = vec4(dye, fill + (d < 4.0 ? 0.2 : 0.0) + lit);
    } else if (look == PAINTED || look == PAINTED_NO_INK) {
        // A watercolour wash: broken by the paper's grain, and its pigment pooling darker towards
        // the edge, where the brush stopped. With no fill the pooled edge stays.
        bool ink = look == PAINTED;
        float pool = 1.0 - smoothstep(0.0, ink ? 4.0 : 5.0, d);
        float grain = noise(p, 4.0, 3), broad = noise(p, 32.0, 1);
        float wash = fill * (0.7 + 0.6 * grain) * (0.85 + 0.3 * broad);
        float alpha = wash + max(fill, 0.2) * pool * (ink ? 1.2 : 1.6) + lit;
        vec3 paint = mix(dye, dye * 0.7, pool * 0.6);
        if (edge && ink) {
            // A thin pen line in sepia, broken now and then where the pen skipped.
            if (noise(p, 8.0, 2) >= 0.12) color = vec4(PAINT_INK, linesOnly ? 0.5 : 0.85);
            else if (!linesOnly) color = vec4(paint, alpha);
        } else if (edge) {
            color = vec4(dye * 0.7, linesOnly ? 0.45 : min(1.0, alpha + 0.35));
        } else if (linesOnly) {
            discard;
        } else if (ink && nearEdge(c, step, 2.0, tag) && noise(p, 8.0, 0) > 0.8) {
            // The pen wavers inward here and there.
            color = vec4(PAINT_INK, 0.6);
        } else {
            color = vec4(paint, alpha);
        }
    } else {
        // An old map: a dash-dot border in old ink over a band tinted by hand along the inside of
        // the edge; the sepia map hatches the inside, the coloured one leaves it almost bare.
        bool coloured = look == ANTIQUE_COLOR;
        vec3 tint = coloured ? dye : sepia(0.3 + 0.6 * luma(dye));
        float band = (1.0 - smoothstep(0.0, 5.0, d)) * 0.55;
        if (edge) {
            float t = mod(index.x + index.y, 8.0);
            if (t < 4.0 || t == 5.0) color = vec4(OLD_INK, linesOnly ? 0.5 : 0.9);
            else if (!linesOnly) color = vec4(tint, band);
        } else if (!linesOnly) {
            float alpha = band;
            if (!coloured && fill > 0.0 && mod(index.x - index.y, 4.0) == 0.0) alpha = max(alpha, fill * 1.1);
            if (coloured) alpha = max(alpha, fill * 0.35);
            color = vec4(tint, alpha + lit);
        }
    }
    if (bandKind > 0 && !linesOnly && !edge) {
        // The border band, laid over the style: the dye strongest at the border, fading inwards.
        float t = 1.0 - clamp(d / BAND_WIDTH[bandKind], 0.0, 1.0);
        float glow = t * t * BAND_ALPHA[bandKind];
        vec3 band = look == ANTIQUE ? sepia(0.3 + 0.6 * luma(dye)) : readable(dye);
        float alpha = glow + color.a * (1.0 - glow);
        if (alpha > 0.0) color = vec4((band * glow + color.rgb * color.a * (1.0 - glow)) / alpha, alpha);
    }
    if (color.a <= 0.0) discard;
    // Dimmed by night like the ground under it, never quite dark.
    vec3 night = mix(vec3(0.42, 0.46, 0.60), vec3(1.0), daylight);
    fragColor = vec4(color.rgb * night, min(1.0, color.a)) * ColorModulator;
}
