precision mediump float;

uniform mat4 u_Model;
uniform mat4 u_MVP;
attribute vec4 a_Position;
attribute vec3 a_Normal;
varying vec3 v_Grid;

/*
Dmitry Brant, 2017
*/
void main() {
   v_Grid = vec3(u_Model * a_Position);
   gl_Position = u_MVP * a_Position;
}
