precision mediump float;

uniform vec3 u_ambientColor;

/*
Dmitry Brant, 2017
*/
void main()
{
    float maxDepth = 50.0;
    float depth = gl_FragCoord.z / gl_FragCoord.w;
    gl_FragColor = vec4(u_ambientColor * (maxDepth / depth), 1.0);
}
