#version 300 es
precision highp float;
precision mediump sampler3D;
precision mediump sampler2D;

// Density of each texel (as a fraction of the density scale), the fraction of the light from above
// that reaches it, and its temperature (from cold to the hottest), if the volume has one.
uniform sampler3D u_Volume;
// Color of each texel, premultiplied by its density.
uniform sampler3D u_Color;
uniform bool u_HasColor;
// Fraction of the light that the volume reflects, when it has no colors.
uniform float u_Albedo;
// Color of the light that fire emits, for each temperature.
uniform sampler2D u_FireRamp;
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
const float EMISSION = 4.0;

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
    float stepDensity = u_DensityScale * length(u_TextureToWorld * dir) * dt;
    // Start each ray at a random offset, which turns the banding of the steps into fine noise.
    t += dt * fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);

    vec3 color = vec3(0.0);
    float transmittance = 1.0;
    for (int i = 0; i < MAX_STEPS && t < 1.0; i++) {
        vec3 pos = u_CameraPos + dir * t;
        vec3 voxel = textureLod(u_Volume, pos, 0.0).rgb;
        if (voxel.r > 0.0) {
            float alpha = 1.0 - exp(-voxel.r * stepDensity);
            vec3 albedo = u_HasColor ? clamp(textureLod(u_Color, pos, 0.0).rgb / voxel.r, 0.0, 1.0) : vec3(u_Albedo);
            // Light from above (attenuated by the volume above this point), from the camera
            // (attenuated by the volume in front of it), and ambient light.
            vec3 light = albedo * (AMBIENT_LIGHT + TOP_LIGHT * voxel.g + CAMERA_LIGHT * transmittance);
            if (voxel.b > 0.0) {
                // Hot parts of the volume glow, as much as they absorb light (by Kirchhoff's law).
                light += EMISSION * textureLod(u_FireRamp, vec2(voxel.b * (255.0 / 256.0) + 0.5 / 256.0, 0.5), 0.0).rgb;
            }
            color += transmittance * alpha * light;
            transmittance *= 1.0 - alpha;
            if (transmittance < MIN_TRANSMITTANCE) {
                break;
            }
        }
        t += dt;
    }

    float alpha = 1.0 - transmittance;
    if (alpha < 1.0 / 255.0) {
        discard;
    }
    // The light is computed in linear space, and converted to sRGB for display (with premultiplied alpha).
    fragColor = vec4(pow(color / alpha, vec3(1.0 / 2.2)) * alpha, alpha);
}
