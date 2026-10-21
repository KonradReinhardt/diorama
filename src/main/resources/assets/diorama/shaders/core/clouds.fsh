#version 330
//? if >=26.3 {
#extension GL_ARB_separate_shader_objects : require
//?}

// Small pixel-art cumulus clouds drifting over the explored map, and their shadows on the ground
// below, on the map's art pixels. Drawn over the tiles and under the fog, never baked into them:
// see docs/design/map-clouds.md. They are built of boxes and seen the way the map sees its
// blocks, through the same oblique projection: white tops, pale blue south faces under each step.
// Weather noise anchored to the world decides where they gather; they drift with the wind,
// growing as they drift into cloudy weather and shrinking away as they leave it.

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

// The fog's mask: share of known ground per texel in red.
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

// How far the wind carries the clouds per GameTime loop, in blocks, and the period of the weather
// that decides where they gather. MapViewport keeps the mask's origin modulo REGION_PERIOD across
// and REGION_PERIOD * ROW down (MapClouds), so nothing jumps while panning; the cells below
// repeat with both, so nothing jumps as GameTime wraps either.
const float DRIFT = 1024.0;
const float REGION_PERIOD = 8192.0;
// Map units down the map per block of depth or of height, at the map's 45 degree pitch: a block's
// top and south face are each 1 by ROW (4 by 3 pixels; see Projection).
const float ROW = 0.75;
// The clouds are built of boxes VOXEL blocks square and LAYER blocks tall, at most LAYERS high.
const float VOXEL = 2.0;
const float LAYER = 2.0;
const float LAYERS = 5.0;
// Each cloud has a cell of its own, this many blocks square, and only some cells have one. It
// sits anywhere within JITTER of the cell's middle and its puffs within 2 of its radius, at most
// 9.5, so it keeps within REACH of the middle; a line of sight then meets at most two by two cells.
const float CELL = 128.0;
const float JITTER = 38.0;
const float REACH = 57.0;
// Blocks from the ground up to the clouds' flat bases.
const float HEIGHT = 40.0;
// The sun: the tiles' own (TileBaker.SUN_AZIMUTH_*), in the west and slightly south, 50 degrees
// up. World x and z toward it, and its elevation's cotangent.
const vec2 TOWARD_SUN = vec2(-0.878, 0.479);
const float SUN_COT = 0.84;
// Puffs per cloud, and room for those of the four cells REACH can span.
const int PUFFS = 5;
const int NEAR = 4 * PUFFS;

vec2 origin;
float unitsPerUv;
float maskTexels;
float wind;
float daylight;
float rain;
bool sepia;
float fade;

// ---------------------------------------------------------------------------------------------
// The art pixel grid, as in fog.fsh: one GUI pixel on a grid aligned to the framebuffer; or,
// once a block is bigger than that, the blocks' own faces, 1 unit across and ROW down, so the
// clouds' pixels lie on the terrain's.

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
// Clouds. They live in the world over its horizontal plane: a point is (u, w, y), u across the
// map as usual, w its depth from the clouds' bases and y its height above them, and the map draws
// it at v = ROW * (w - y). Each is a small cumulus of boxes on a flat base, shaped by a few round
// puffs, and the map sees it as it sees blocks: white tops, pale blue south faces under each step.
// Positions are in the frame the wind carries along (see drifted).

float known(vec2 c) {
    return textureLod(Sampler0, uvAt(c), 0.0).r;
}

// How cloudy the weather is over a point of the plane, 0..1. It stays put: in clear weather
// clouds gather over a few patches some hundreds of blocks across; rain spreads them everywhere.
float coverAt(vec2 p) {
    vec4 n = textureLod(Sampler1, p / REGION_PERIOD, 0.0);
    float weather = n.g * 0.65 + n.b * 0.35;
    return smoothstep(0.48 - 0.4 * rain, 0.64 - 0.4 * rain, weather);
}

// A hash of a cell, repeating every DRIFT blocks across and REGION_PERIOD down.
float cellHash(vec2 cell, uint salt) {
    uvec2 q = uvec2(mod(cell, vec2(DRIFT / CELL, REGION_PERIOD / CELL)));
    uint h = q.x * 1597334677u ^ q.y * 3812015801u ^ salt * 2654435761u;
    h ^= h >> 15u;
    h *= 2246822519u;
    h ^= h >> 13u;
    return float(h & 0xFFFFu) / 65535.0;
}

// A point of the plane in the frame the wind carries along.
vec2 drifted(vec2 p) {
    return p - vec2(wind * DRIFT, 0.0);
}

vec4 near[NEAR];
int nearCount;

// Adds the puffs of the cloud in a drifting cell to the near ones, if it could reach u between
// depths w0 and w1: each the (u, w) of its middle, its height and its radius. Fewer than half the
// cells have a cloud, placed anywhere in the cell, most of them small, each of its own shape: a
// tall middle puff, lower ones either side, sometimes one in front and a taller one behind. A
// cloud grows from nothing as its cell drifts into cloudy weather and shrinks away as it leaves.
void addCloud(vec2 cell, float u, float w0, float w1) {
    if (cellHash(cell, 8u) > 0.42) return;
    vec2 middle = (cell + 0.5) * CELL + (vec2(cellHash(cell, 1u), cellHash(cell, 2u)) * 2.0 - 1.0) * JITTER;
    float size = cellHash(cell, 4u);
    float r = 3.5 + 6.0 * size * size;
    if (abs(u - middle.x) > 2.0 * r || w0 > middle.y + 2.0 * r || w1 < middle.y - 2.0 * r) return;
    float grown = clamp((coverAt(middle + vec2(wind * DRIFT, 0.0)) - 0.2 - 0.8 * cellHash(cell, 3u)) * 4.0, 0.0, 1.0);
    if (grown <= 0.0) return;
    r *= 0.35 + 0.65 * smoothstep(0.0, 1.0, grown);
    vec4 h = vec4(cellHash(cell, 5u), cellHash(cell, 6u), cellHash(cell, 7u), cellHash(cell, 9u));
    near[nearCount++] = vec4(middle, (0.7 + 0.25 * h.x) * r, r);
    near[nearCount++] = vec4(middle + vec2(-0.75 - 0.4 * h.y, h.z * 0.6 - 0.2) * r, (0.4 + 0.25 * h.w) * r, (0.55 + 0.25 * h.x) * r);
    near[nearCount++] = vec4(middle + vec2(0.75 + 0.4 * h.z, h.y * 0.6 - 0.2) * r, (0.4 + 0.25 * h.x) * r, (0.55 + 0.25 * h.w) * r);
    if (h.w > 0.4) near[nearCount++] = vec4(middle + vec2(h.z * 0.8 - 0.4, 0.6 + 0.25 * h.x) * r, 0.35 * r, (0.5 + 0.15 * h.y) * r);
    if (h.y > 0.5) near[nearCount++] = vec4(middle + vec2(h.w - 0.5, -0.45 - 0.25 * h.z) * r, (0.8 + 0.2 * h.x) * r, (0.6 + 0.2 * h.z) * r);
}

// Gathers the clouds that could stand anywhere from (u, w0) to (u, w1), drifted.
void gather(float u, float w0, float w1) {
    nearCount = 0;
    vec2 first = floor((vec2(u, w0) - REACH) / CELL), last = floor((vec2(u, w1) + REACH) / CELL);
    for (float cu = first.x; cu <= last.x; cu++) {
        for (float cw = first.y; cw <= last.y; cw++) {
            addCloud(vec2(cu, cw), u, w0, w1);
        }
    }
}

// How many layers of boxes stand on the voxel holding a drifted point of the plane: each puff a
// dome, and the tallest one counts.
float layersAt(vec2 p) {
    vec2 centre = (floor(p / VOXEL) + 0.5) * VOXEL;
    float top = 0.0;
    for (int i = 0; i < nearCount; i++) {
        vec2 d = centre - near[i].xy;
        float across = near[i].w * near[i].w - dot(d, d);
        if (across > 0.0) top = max(top, near[i].z * sqrt(across) / near[i].w);
    }
    return min(LAYERS, floor(top / LAYER + 0.5));
}

const int NONE = 0;
const int TOP = 1;
const int SIDE = 2;

// What the map's line of sight through the point drawn at c meets first. Coming from the camera,
// south of the map and above it, it runs north and down: through (u, w0 + y, y) for falling y, so
// voxel rows are met from the south. Sets the layers of the voxel met, the height met at, and the
// layers of the voxel south of it, whose top a south face stands on.
int sight(vec2 c, out float layers, out float y, out float below) {
    float u = drifted(vec2(c.x, 0.0)).x;
    float w0 = c.y / ROW;
    float tallest = LAYERS * LAYER;
    gather(u, w0, w0 + tallest);
    layers = 0.0;
    y = 0.0;
    below = 0.0;
    if (nearCount == 0) return NONE;
    float south = 0.0;
    for (float row = floor((w0 + tallest) / VOXEL); row >= floor(w0 / VOXEL); row--) {
        float h = layersAt(vec2(u, (row + 0.5) * VOXEL)) * LAYER;
        float enter = (row + 1.0) * VOXEL - w0, leave = max(0.0, row * VOXEL - w0);
        layers = h / LAYER;
        below = south;
        if (enter < h) {
            y = enter;
            return SIDE;
        }
        if (leave < h) {
            y = h;
            return TOP;
        }
        south = layers;
    }
    return NONE;
}

// Whether the ground drawn at c lies in the clouds' shadow, and under how many layers. The
// ground is taken HEIGHT under the bases, whatever its own height, and the cloud shading it is
// that far up toward the sun, turned the way the map faces (MapDirection).
float shadowAt(vec2 c, int direction) {
    vec2 t = TOWARD_SUN;
    vec2 toward = direction == 0 ? t : direction == 1 ? vec2(t.y, -t.x) : direction == 2 ? -t : vec2(-t.y, t.x);
    vec2 p = drifted(vec2(c.x, c.y / ROW - HEIGHT) + toward * HEIGHT * SUN_COT);
    gather(p.x, p.y, p.y);
    return nearCount == 0 ? 0.0 : layersAt(p);
}

void main() {
    // Alpha: mask scale code (bits 4-7), daylight in fifteenths (0-3). Red: the mask origin's u
    // modulo REGION_PERIOD, in 128s (0-5), rain in thirds (6-7). Green: its v modulo
    // REGION_PERIOD * ROW, in 128s (0-5), map direction (6-7). Blue: art pixel size - 1 (0-1),
    // phase x (2-3), phase y (4-5), sepia (6).
    float a = floor(params.a * 255.0 + 0.5);
    float r = floor(params.r * 255.0 + 0.5);
    float g = floor(params.g * 255.0 + 0.5);
    float b = floor(params.b * 255.0 + 0.5);
    maskTexels = float(textureSize(Sampler0, 0).x);
    unitsPerUv = maskTexels * 2.0 * exp2(floor(a / 16.0));
    daylight = mod(a, 16.0) / 15.0;
    origin = vec2(mod(r, 64.0), mod(g, 64.0)) * 128.0;
    rain = floor(r / 64.0) / 3.0;
    int direction = int(floor(g / 64.0));
    artPixels = mod(b, 4.0) + 1.0;
    artPhase = vec2(mod(floor(b / 4.0), 4.0), mod(floor(b / 16.0), 4.0));
    sepia = b >= 64.0;
    wind = GameTime;

    vec2 index = setUpGrid();
    // A cloud is a few dozen blocks across, of boxes two blocks wide: zoomed far out they would
    // only flicker, so they fade.
    fade = 1.0 - smoothstep(4.0, 10.0, art);
    if (fade <= 0.0) discard;

    vec2 c = artCentre(index);
    float ground = smoothstep(0.35, 0.9, known(c));
    if (ground <= 0.0) discard;
    float layers, y, below;
    int face = sight(c, layers, y, below);

    // Flat pixel-art tones: white tops, pale blue faces, a deeper blue along the base. By night
    // everything sinks toward a moonlit blue.
    vec3 white = vec3(0.965, 0.975, 0.99), pale = vec3(0.72, 0.86, 0.95), deep = vec3(0.58, 0.76, 0.90);
    vec3 dark = vec3(0.10, 0.14, 0.26);
    if (sepia) {
        white = vec3(0.97, 0.94, 0.86); pale = vec3(0.85, 0.77, 0.63); deep = vec3(0.76, 0.66, 0.50);
        dark = vec3(0.24, 0.15, 0.08);
    }
    vec3 night = mix(vec3(0.22, 0.26, 0.40), vec3(1.0), daylight);

    if (face == NONE) {
        // The shadow, square like the boxes casting it, deeper under the thicker middle. Only
        // with the sun up.
        float shadow = shadowAt(c, direction);
        if (shadow <= 0.0) discard;
        float s = (shadow > 1.0 ? 0.42 : 0.32) * daylight * fade * ground;
        fragColor = vec4(dark, s) * ColorModulator;
        return;
    }
    vec3 color = white;
    if (face == SIDE) {
        // The lowest box's face, along the base of the cloud, deeper; the rest pale.
        color = below == 0.0 && y < LAYER ? deep : pale;
    }
    fragColor = vec4(color * night, 0.9 * mix(0.75, 1.0, daylight) * fade * ground) * ColorModulator;
}
