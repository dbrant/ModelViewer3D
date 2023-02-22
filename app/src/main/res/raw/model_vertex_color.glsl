precision mediump float;

attribute vec4 a_Position;
attribute vec3 a_Normal;
attribute vec4 a_Color;
varying vec3 v_Normal;
varying vec3 v_Position;
varying vec4 v_Color;
uniform mat4 u_MVP;

/*
Dmitry Brant, 2017
*/
void main() {
    v_Normal = normalize(vec3(u_MVP * vec4(a_Normal, 0.0)));
    gl_Position = u_MVP * a_Position;
    v_Position = gl_Position.xyz / gl_Position.w;
    v_Color = a_Color;
}
