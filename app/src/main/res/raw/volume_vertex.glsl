#version 300 es

uniform mat4 u_MVP;
in vec3 a_Position;
out vec3 v_TexCoord;

/*
Dmitry Brant, 2026
*/
void main() {
    // The vertices of the volume's box are in texture coordinates.
    v_TexCoord = a_Position;
    gl_Position = u_MVP * vec4(a_Position, 1.0);
}
