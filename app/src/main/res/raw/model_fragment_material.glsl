precision mediump float;

uniform vec3 u_LightPos;
uniform vec4 u_DiffuseColor;
uniform vec3 u_SpecularColor;
uniform vec3 u_EmissiveColor;
uniform float u_Shininess;
uniform float u_Lighting;
uniform float u_UseTexture;
uniform float u_AlphaCutoff;
uniform float u_Opaque;
uniform sampler2D u_Texture;
varying vec3 v_Normal;
varying vec3 v_Position;
varying vec4 v_Color;
varying vec2 v_TexCoord;

const float ambient = 0.3;

/*
Dmitry Brant, 2026
*/
void main()
{
    vec4 base = u_DiffuseColor * v_Color;
    if (u_UseTexture > 0.5) {
        base *= texture2D(u_Texture, v_TexCoord);
    }
    // Materials with an alpha mask are only shown where their alpha reaches the cutoff.
    if (base.a < u_AlphaCutoff) {
        discard;
    }
    vec3 color = base.rgb;
    // Lighting: 0 = none (constant color), 1 = diffuse only, 2 = diffuse and specular
    if (u_Lighting > 0.5) {
        vec3 normal = normalize(v_Normal);
        vec3 lightDir = normalize(u_LightPos);
        float diffuse = max(dot(lightDir, normal), 0.0);
        color *= ambient + (1.0 - ambient) * diffuse;
        if (u_Lighting > 1.5) {
            vec3 halfDir = normalize(lightDir + normalize(-v_Position));
            color += u_SpecularColor * pow(max(dot(halfDir, normal), 0.0), u_Shininess);
        }
    }
    gl_FragColor = vec4(color + u_EmissiveColor, mix(base.a, 1.0, u_Opaque));
}
