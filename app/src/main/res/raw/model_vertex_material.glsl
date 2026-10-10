// Positions are transformed with full precision, since on some GPUs, mediump floats have only
// 16 bits, which isn't enough for them.
precision highp float;

attribute vec4 a_Position;
attribute vec3 a_Normal;
attribute vec4 a_Color;
attribute vec2 a_TexCoord;
varying vec3 v_Normal;
varying vec3 v_Position;
varying vec4 v_Color;
varying vec2 v_TexCoord;
uniform mat4 u_MVP;
uniform vec2 u_TexScale;
uniform vec2 u_TexOffset;

/*
Dmitry Brant, 2026
*/
void main() {
    v_Normal = normalize(vec3(u_MVP * vec4(a_Normal, 0.0)));
    gl_Position = u_MVP * a_Position;
    v_Position = gl_Position.xyz / gl_Position.w;
    v_Color = a_Color;
    // Texture coordinates have their origin at the bottom left, but textures are uploaded top row first.
    vec2 texCoord = a_TexCoord * u_TexScale + u_TexOffset;
    v_TexCoord = vec2(texCoord.x, 1.0 - texCoord.y);
}
