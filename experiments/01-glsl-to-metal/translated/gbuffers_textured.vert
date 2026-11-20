#version 450
// Hand-translated from pack/gbuffers_textured.vsh.
// Each "FIX" marks a change a translator would have to make automatically.

// FIX 1: the old built-in inputs (gl_Vertex, gl_Color, gl_MultiTexCoord0)
// don't exist anymore. They become normal inputs with numbered slots.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;

// FIX 2: the old built-in matrices are gone too, and loose uniforms
// aren't allowed in Vulkan, so they go inside a uniform block.
uniform Matrices {
    mat4 gbufferModelViewProjection;
    mat4 textureMatrix;
};

// FIX 3: "varying" becomes "out", and each one needs a numbered slot.
layout(location = 0) out vec2 texcoord;
layout(location = 1) out vec4 glcolor;

void main() {
    gl_Position = gbufferModelViewProjection * vec4(Position, 1.0);
    texcoord = (textureMatrix * vec4(UV0, 0.0, 1.0)).xy;
    glcolor = Color;
}
