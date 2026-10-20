#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
layout(location = 0) in vec2 maskUv;
layout(location = 1) in vec4 params;
layout(location = 0) out vec4 fragColor;

vec3 linearColor(vec3 x) {
    return mix(x / 12.92, pow((x + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), x));
}
vec3 srgb(vec3 x) {
    return mix(12.92 * x, 1.055 * pow(max(x, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), x));
}
void main() {
    vec4 day = texture(Sampler0, maskUv);
    if (day.a <= 0.0) discard;
    vec4 night = texture(Sampler1, maskUv);
    vec3 d = linearColor(day.rgb);
    // Legacy pixels carry no local-light data. Their approximation never invents torches.
    vec3 fallback = d * vec3(0.045, 0.06, 0.095);
    float available = params.b * clamp(night.a / day.a, 0.0, 1.0);
    vec3 n = mix(fallback, linearColor(night.rgb), available);
    vec3 overcast = mix(vec3(1.0), vec3(0.64, 0.69, 0.77), params.g);
    fragColor = vec4(srgb(mix(n, d * overcast, params.r)), day.a);
    if (params.a < 0.5) {
        float value = dot(fragColor.rgb, vec3(0.299, 0.587, 0.114));
        fragColor.rgb = mix(vec3(0.22, 0.12, 0.055), vec3(0.92, 0.81, 0.59), smoothstep(0.0, 0.95, value));
    }
}
