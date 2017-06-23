precision mediump float;

attribute vec4 a_Position;
uniform mat4 u_MVP;
uniform float u_PointThickness;

/*
Dmitry Brant, 2017
*/
void main() {
    gl_Position = u_MVP * a_Position;
    gl_PointSize = u_PointThickness;
}
