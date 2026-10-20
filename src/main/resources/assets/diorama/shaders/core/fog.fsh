#version 330
//? if >=26.3 {
#extension GL_ARB_separate_shader_objects : require
//?}

// Volumetric-looking fog over the parts of the map with no data.
//
// The fog is a height field: deep in unknown territory it stands fogHeight blocks tall, and it
// thins out toward known ground, with noise breaking up the edge. We look at it through the same
// tilted camera as the map, so we march a few height layers from the top down. A layer at height h
// sits above the ground point h * cos(pitch) further south, which is what makes the banks look
// like they rise off the terrain.

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

const float MASK_TEXELS = 256.0;
const float NOISE_PERIOD = 512.0;
// The default camera angle (50 degrees). At other angles the banks sit slightly too far north or south.
const float COS_PITCH = 0.643;
const int LAYERS = 8;
// Fast quality, for slower graphics cards: fewer layers, one blur, and no sun shading.
const int FAST_LAYERS = 2;

// Surface fog banks are tall and soft. Cave fog hugs the ground with a tight edge, so a
// three-block tunnel you walked down stays readable instead of blurring into the fog.
float fogHeight;
float blurUnits;
float unitsPerUv;
vec2 noiseOrigin;
// The noise pattern grows by this when zoomed far out, so it keeps its size on screen instead of
// repeating every few pixels. The banks keep their size in blocks, so they stay a thin fringe
// around explored land and never bury it.
float fogScale;
float scaleSteps;
float scaleFraction;
float seconds;
bool fast;
// Every mask texel this pixel can read is fully known. Then every thickness is 0 whatever the
// noise, so the texture reads are skipped. The rest still runs: the lowest layers leave a faint
// veil even over known ground.
bool clear;
// Every mask texel this pixel can read is fully unknown: every known share is exactly 0, so the
// mask reads are skipped. The noise still shapes the fog there, so its reads stay.
bool deep;
float revealProgress;

float sampleKnown(vec2 uv) {
    vec4 mask = texture(Sampler0, uv);
    return mix(mask.a, mask.r, revealProgress);
}

// The known share at uv, without the read where the answer is already known.
float knownAt(vec2 uv) {
    return deep ? 0.0 : sampleKnown(uv);
}

// Known share blurred over a radius in map units, so chunk-shaped edges melt away.
float knownBlur(vec2 uv, float units) {
    float r = max(units / unitsPerUv, 1.0 / MASK_TEXELS);
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

// Surface fog blends a near and a wide blur, so banks reach well over the edge of known land
// and the noise has room to billow. Cave fog stays tight to the explored tunnels.
float knownSoft(vec2 uv) {
    if (clear) {
        return 1.0;
    }
    if (deep) {
        return 0.0;
    }
    if (blurUnits < 5.0 || fast) {
        return knownBlur(uv, blurUnits);
    }
    return 0.5 * knownBlur(uv, blurUnits) + 0.5 * knownBlur(uv, blurUnits * 2.4);
}

float noiseLayer(vec2 n) {
    // Speeds are whole periods per 1200 s, so the loop of GameTime wraps without a jump.
    float a = texture(Sampler1, n + vec2(5.0, 2.0) * seconds / 1200.0).r;
    float b = texture(Sampler1, n + vec2(-8.0, 3.0) * seconds / 1200.0).g;
    float c = texture(Sampler1, n + vec2(13.0, -9.0) * seconds / 1200.0).b;
    return a * 0.55 + b * 0.3 + c * 0.15;
}

// Noise at the two whole scale steps around fogScale, blended, so zooming never pops.
float noiseAt(vec2 uv) {
    float coarsePeriod = NOISE_PERIOD * exp2(scaleSteps + 1.0);
    vec2 coarse = noiseOrigin + uv * unitsPerUv / coarsePeriod;
    // The fine noise has half the period, so it runs twice as far from the same origin.
    float fine = noiseLayer(coarse * 2.0);
    // At the detailed zoom and closer there is only the one scale: mix() would return fine anyway.
    if (scaleFraction == 0.0) {
        return fine;
    }
    return mix(fine, noiseLayer(coarse), scaleFraction);
}

float thicknessFrom(float known, float n) {
    float unknown = 1.0 - known;
    // Noise only shapes the edge; where everything is known there is no fog at all.
    float edge = smoothstep(0.0, 0.35, unknown);
    float amount = smoothstep(0.12, 0.75, unknown + (n - 0.5) * 1.5 * edge);
    return fogHeight * amount * (0.45 + 0.75 * n);
}

// From this known share up there is no fog whatever the noise: unknown + 0.75 * edge stays below
// the 0.12 where the amount starts, so the noise needn't be read.
const float NO_FOG_KNOWN = 0.95;

float thicknessAt(vec2 uv, float known) {
    return known >= NO_FOG_KNOWN ? 0.0 : thicknessFrom(known, noiseAt(uv));
}

float thickness(vec2 uv) {
    return thicknessAt(uv, knownSoft(uv));
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

float biomeAt(ivec2 p) {
    return floor(texelFetch(Sampler0, clamp(p, ivec2(0), ivec2(255)), 0).g * 255.0 / 32.0 + 0.5);
}

// Deliberately square, world-anchored ink pixels. Easing changes opacity, never this grid.
void antiqueMap(ivec2 texel, vec4 mask) {
    vec2 mapUnits = noiseOrigin * (NOISE_PERIOD * exp2(scaleSteps + 1.0)) + maskUv * unitsPerUv;
    vec2 pixel = floor(mapUnits / 2.0);
    vec2 q = mod(pixel, 32.0);
    vec2 paperUv = mapUnits / 256.0;
    vec3 paper = texture(Sampler1, paperUv).rgb;
    paper = mix(vec3(0.92, 0.82, 0.62), paper, 0.45);
    float kind = biomeAt(texel);
    float water = kind == 1.0 ? 1.0 : 0.0;
    float coast = 0.0;
    vec2 local = fract(maskUv * MASK_TEXELS);
    float stroke = min(.5, 1.0 / (unitsPerUv / MASK_TEXELS));
    if (kind > 0.0) {
        float west = biomeAt(texel + ivec2(-1, 0)), east = biomeAt(texel + ivec2(1, 0));
        float north = biomeAt(texel + ivec2(0, -1)), south = biomeAt(texel + ivec2(0, 1));
        // Unknown cache boundaries are blank paper, never fabricated shorelines.
        coast = ((local.x < stroke && west > 0.0 && (west == 1.0) != (kind == 1.0))
            || (local.x > 1.0 - stroke && east > 0.0 && (east == 1.0) != (kind == 1.0))
            || (local.y < stroke && north > 0.0 && (north == 1.0) != (kind == 1.0))
            || (local.y > 1.0 - stroke && south > 0.0 && (south == 1.0) != (kind == 1.0))) ? 1.0 : 0.0;
    }
    float ink = coast * 0.58;
    // Offset repeated symbols deterministically by a few grid pixels, like a hand-sketched atlas.
    vec2 cell = floor(pixel / 32.0);
    q += floor(vec2(hash(cell), hash(cell.yx + 7.0)) * 3.0) - 1.0;
    if (kind == 1.0) {
        float wave = mod(q.x + floor(q.y / 3.0), 13.0);
        if ((q.y == 9.0 || q.y == 21.0) && wave < 7.0) ink = max(ink, .25);
        paper *= vec3(.93, .92, .87);
    } else if (kind == 3.0) {
        float dx = abs(q.x - 16.0);
        float width = floor((q.y - 6.0) / 3.0) * 2.0 + 2.0;
        bool crown = q.y >= 6.0 && q.y <= 20.0 && dx <= width && (dx >= width - 1.0 || mod(q.y - 5.0, 3.0) == 0.0);
        bool trunk = q.x == 16.0 && q.y >= 20.0 && q.y <= 25.0;
        if (crown || trunk) ink = max(ink, .45);
    } else if (kind == 4.0) {
        float stair = floor((26.0 - q.y) / 2.0);
        float dx = abs(q.x - 16.0);
        if (q.y >= 6.0 && q.y <= 26.0 && (dx == stair || (q.x > 16.0 && dx < stair && mod(q.x + q.y, 4.0) == 0.0))) ink = max(ink, .48);
    } else if (kind == 5.0) {
        if (mod(q.y, 13.0) == 0.0 && mod(q.x, 11.0) < 3.0) ink = max(ink, .22);
    } else if (kind == 6.0) {
        if ((q.x == 16.0 && q.y >= 12.0 && q.y <= 20.0) || (q.y == 16.0 && q.x >= 12.0 && q.x <= 20.0)) ink = max(ink, .24);
    } else if (kind > 0.0) {
        if ((q.x == 14.0 || q.x == 18.0) && q.y >= 18.0 && q.y <= 20.0) ink = max(ink, .2);
    }
    vec3 cartography = mix(paper, vec3(.30, .17, .075), ink);
    float known = mix(mask.a, mask.r, revealProgress);
    // A narrow square-pixel frontier fades to the detailed map; the paper grain remains everywhere.
    float unknown = 1.0 - smoothstep(.05, .98, known);
    float alpha = mix(.13, 1.0, unknown);
    fragColor = vec4(mix(paper, cartography, unknown), alpha) * ColorModulator;
}

void main() {
    // Reserved tile-mode sentinel; impossible for fog at mask scale zero and noise step seven.
    if (params.a == 0.0 && params.b > .99 && params.r > .99 && params.g > .99) {
        vec4 terrain = texture(Sampler0, maskUv);
        if (terrain.a <= 0.0) discard;
        float value = dot(terrain.rgb, vec3(.299, .587, .114));
        fragColor = vec4(mix(vec3(.22, .12, .055), vec3(.92, .81, .59), smoothstep(0.0, .95, value)), terrain.a) * ColorModulator;
        return;
    }
    seconds = GameTime * 1200.0;
    noiseOrigin = floor((params.rg * 255.0 + 0.001) / 32.0) / 8.0;
    float alphaCode = floor(params.a * 255.0 + 0.5);
    unitsPerUv = MASK_TEXELS * 2.0 * exp2(floor(alphaCode / 16.0));
    fast = mod(floor(alphaCode / 4.0), 2.0) >= 1.0;
    float greenCode = floor(params.g * 255.0 + 0.5);
    revealProgress = (mod(floor(greenCode / 2.0), 16.0) + 16.0 * mod(alphaCode, 4.0)) / 63.0;
    float fogCode = floor(params.b * 255.0 + 0.5);
    bool cave = fogCode >= 128.0;
    fogCode = mod(fogCode, 128.0);
    scaleSteps = floor(fogCode / 16.0);
    scaleFraction = mod(fogCode, 16.0) / 16.0;
    fogScale = exp2(scaleSteps + scaleFraction);
    // MapViewport.fogReach repeats how far the reads below can reach (these sizes, the blur taps,
    // the layer shift and the sun probe), to know where the clear and deep flags are safe. Change both together.
    fogHeight = cave ? 4.0 : 10.0;
    blurUnits = cave ? 2.5 : 7.0;
    ivec2 texel = clamp(ivec2(floor(maskUv * MASK_TEXELS)), ivec2(0), ivec2(int(MASK_TEXELS) - 1));
    vec4 flags = texelFetch(Sampler0, texel, 0);
    if (mod(floor(alphaCode / 8.0), 2.0) >= 1.0) {
        antiqueMap(texel, flags);
        return;
    }
    clear = flags.g > 0.5;
    deep = flags.b > 0.5;

    // A fully revealed neighborhood contributes exactly zero: avoid the layer march and noise.
    if (clear) discard;

    float daylight = (mod(floor(params.r * 255.0 + 0.5), 32.0) + 32.0 * mod(greenCode, 2.0)) / 63.0;
    vec3 lit = cave ? vec3(0.24, 0.26, 0.33) : mix(vec3(0.11, 0.13, 0.20), vec3(0.97, 0.97, 0.99), daylight);
    vec3 shade = cave ? vec3(0.035, 0.04, 0.055) : mix(vec3(0.025, 0.035, 0.065), vec3(0.48, 0.55, 0.67), daylight);
    vec2 toSun = vec2(-1.0, -0.4) * (9.0 * fogScale / unitsPerUv);

    // Deep fog has no coverage edges. Its vertical noise travel is only ~6 map units out of
    // a >=512-unit period: interpolate endpoints instead of re-reading six noises per layer.
    // Keep the original sampling at the explored frontier, where exact mask reads matter.
    bool cacheDeep = deep && !fast;
    vec2 topShift = vec2(0.0, fogHeight * COS_PITCH / unitsPerUv);
    vec4 deepNoise = vec4(0.0);
    if (cacheDeep) deepNoise = vec4(noiseAt(maskUv), noiseAt(maskUv + topShift),
        noiseAt(maskUv + toSun), noiseAt(maskUv + topShift + toSun));

    vec3 color = vec3(0.0);
    float alpha = 0.0;
    float jitter = hash(gl_FragCoord.xy) - 0.5;
    int layers = fast ? FAST_LAYERS : LAYERS;
    for (int i = 0; i < layers; i++) {
        float h = fogHeight * (1.0 - (float(i) + 0.5 + jitter * 0.6) / float(layers));
        vec2 ground = maskUv + vec2(0.0, h * COS_PITCH / unitsPerUv);
        float th = cacheDeep ? thicknessFrom(0.0, mix(deepNoise.x, deepNoise.y, h / fogHeight)) : thickness(ground);
        float a = smoothstep(h - 1.4, h + 1.4, th);
        if (a <= 0.002) {
            continue;
        }
        // More fog between this point and the sun means it sits in shadow.
        float occlusion = 0.0;
        if (!fast) {
            vec2 probe = ground + toSun;
            float toward = cacheDeep ? thicknessFrom(0.0, mix(deepNoise.z, deepNoise.w, h / fogHeight))
                : thicknessAt(probe, clear ? 1.0 : knownAt(probe));
            occlusion = clamp((toward - th) / fogHeight * 2.2, 0.0, 1.0);
        }
        float light = clamp(0.2 + 0.7 * (h / fogHeight) + 0.3 - occlusion * 0.7, 0.0, 1.0);
        vec3 layerColor = mix(shade, lit, light);
        // Fewer layers each carry more, so the bank stays as thick.
        float layerAlpha = a * (fast ? 0.85 : 0.45);
        color += (1.0 - alpha) * layerAlpha * layerColor;
        alpha += (1.0 - alpha) * layerAlpha;
        if (alpha > 0.985) {
            break;
        }
    }

    // The ground under a thick bank is never visible.
    float soft = knownSoft(maskUv);
    float floorFog = smoothstep(0.5, 2.5, cacheDeep ? thicknessFrom(0.0, deepNoise.x) : thicknessAt(maskUv, soft));
    color += (1.0 - alpha) * floorFog * shade;
    alpha += (1.0 - alpha) * floorFog;

    // Ground that is known right here stays visible; fog only rolls over its edges. Caves use the
    // sharp mask so narrow tunnels stay clear; the surface uses the blurred one so banks stay soft.
    float here = cave ? knownAt(maskUv) : soft;
    alpha *= 1.0 - 0.85 * smoothstep(0.45, 0.95, here);

    // Where there is no map at all, nothing should show through, or the edge of the data would
    // read as a hard line inside the bank.
    // The cover takes the colour the layers already have here, so it doesn't show as a band.
    float empty = 1.0 - smoothstep(0.3, 0.85, knownAt(maskUv));
    vec3 coverColor = alpha > 0.02 ? color / alpha : mix(shade, lit, 0.7);
    color += (1.0 - alpha) * empty * coverColor;
    alpha += (1.0 - alpha) * empty;

    if (alpha <= 0.002) {
        discard;
    }
    color += (hash(gl_FragCoord.yx) - 0.5) / 255.0;
    fragColor = vec4(color / alpha, alpha) * ColorModulator;
}
