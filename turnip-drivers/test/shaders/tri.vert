#version 450
layout(location = 0) out vec4 vcolor;
void main() {
    vec2 p[3] = vec2[](vec2(-0.8, -0.8), vec2(0.8, -0.8), vec2(0.0, 0.8));
    gl_Position = vec4(p[gl_VertexIndex], 0.0, 1.0);
    vcolor = vec4(1.0, 0.0, 0.0, 1.0);
}
