#version 300 es
precision highp float;
precision mediump sampler3D;
precision mediump sampler2D;

// Density of each texel (as a fraction of the density scale), the fraction of the light from above
// that reaches it, and how much it glows (from its temperature or flames), if the volume does.
uniform sampler3D u_Volume;
// Color of each texel, premultiplied by its density.
uniform sampler3D u_Color;
uniform bool u_HasColor;
// Fraction of the light that the volume reflects, when it has no colors.
uniform float u_Albedo;
// Color of the light that fire emits, for each amount of glow, at full brightness.
uniform sampler2D u_FireRamp;
// Brightness of the glow increases with this power of the amount of glow.
uniform float u_GlowExponent;
// Brightness of the glow from temperature, relative to how much light the volume absorbs.
uniform float u_ThermalGlow;
// Brightness of the glow from flames, per world unit.
uniform float u_FlameGlow;
// Position of the camera, in texture coordinates.
uniform vec3 u_CameraPos;
uniform vec3 u_TextureSize;
// Transform of texture coordinates to world units, for measuring the distance that light travels.
uniform mat3 u_TextureToWorld;
// Extinction coefficient (per world unit) of the highest density.
uniform float u_DensityScale;
in vec3 v_TexCoord;
out vec4 fragColor;

const int MAX_STEPS = 4096;
const float STEP_TEXELS = 1.0;
// Once this little light gets through, the rest of the volume behind it can't be seen.
const float MIN_TRANSMITTANCE = 0.01;
const float AMBIENT_LIGHT = 0.15;
const float TOP_LIGHT = 0.75;
const float CAMERA_LIGHT = 0.35;
const vec3 LUMINANCE = vec3(0.2126, 0.7152, 0.0722);

/*
Dmitry Brant, 2026
*/
void main()
{
    // This fragment is on a back face of the volume's box, which is where the ray from the camera
    // leaves the box (at t = 1). The ray enters the box where it crosses the last of the near
    // planes of each axis, or at the camera, if it's inside the box.
    vec3 dir = v_TexCoord - u_CameraPos;
    vec3 safeDir = mix(dir, vec3(1e-6), lessThan(abs(dir), vec3(1e-6)));
    vec3 tNear = min(-u_CameraPos / safeDir, (1.0 - u_CameraPos) / safeDir);
    float t = max(max(tNear.x, tNear.y), max(tNear.z, 0.0));

    float dt = STEP_TEXELS / length(dir * u_TextureSize);
    float stepLength = length(u_TextureToWorld * dir) * dt;
    float stepDensity = u_DensityScale * stepLength;
    // Start each ray at a random offset, which turns the banding of the steps into fine noise.
    t += dt * fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);

    // Light that the volume reflects, and light that it emits by glowing.
    vec3 reflected = vec3(0.0);
    vec3 emitted = vec3(0.0);
    float transmittance = 1.0;
    for (int i = 0; i < MAX_STEPS && t < 1.0; i++) {
        vec3 pos = u_CameraPos + dir * t;
        vec3 voxel = textureLod(u_Volume, pos, 0.0).rgb;
        float alpha = 0.0;
        if (voxel.r > 0.0) {
            alpha = 1.0 - exp(-voxel.r * stepDensity);
            vec3 albedo = u_HasColor ? clamp(textureLod(u_Color, pos, 0.0).rgb / voxel.r, 0.0, 1.0) : vec3(u_Albedo);
            // Light from above (attenuated by the volume above this point), from the camera
            // (attenuated by the volume in front of it), and ambient light.
            reflected += transmittance * alpha * albedo * (AMBIENT_LIGHT + TOP_LIGHT * voxel.g + CAMERA_LIGHT * transmittance);
        }
        if (voxel.b > 0.0) {
            // Hot parts of the volume glow as much as they absorb light (by Kirchhoff's law), and
            // flames glow on their own.
            vec3 glow = textureLod(u_FireRamp, vec2(voxel.b * (255.0 / 256.0) + 0.5 / 256.0, 0.5), 0.0).rgb
                    * pow(voxel.b, u_GlowExponent);
            emitted += transmittance * glow * (alpha * u_ThermalGlow + stepLength * u_FlameGlow);
        }
        transmittance *= 1.0 - alpha;
        if (transmittance < MIN_TRANSMITTANCE) {
            break;
        }
        t += dt;
    }

    // The light is computed in linear space, and converted to sRGB for display (with premultiplied
    // alpha). Reflected light covers what's behind the volume as much as the volume absorbs it,
    // but emitted light is added to it, so the light is converted according to how much of it is
    // emitted.
    float alpha = 1.0 - transmittance;
    float reflectedLuminance = dot(reflected, LUMINANCE);
    float emittedLuminance = dot(emitted, LUMINANCE);
    float coverage = mix(alpha, 1.0, emittedLuminance / max(reflectedLuminance + emittedLuminance, 1e-6));
    if (coverage < 1.0 / 255.0) {
        discard;
    }
    fragColor = vec4(pow((reflected + emitted) / coverage, vec3(1.0 / 2.2)) * coverage, alpha);
}
