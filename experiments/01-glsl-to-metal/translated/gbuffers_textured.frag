#version 450
// Hand-translated from pack/gbuffers_textured.fsh.

// FIX 4: the pack named its texture "texture", but in newer GLSL that's
// the name of a built-in function. Rename it.
uniform sampler2D gtexture;

// FIX 3 again: "varying" becomes "in", same slot numbers as the vertex side.
layout(location = 0) in vec2 texcoord;
layout(location = 1) in vec4 glcolor;

// FIX 5: gl_FragData[0] is gone. Declare a real output for render target 0.
layout(location = 0) out vec4 outColor0;

void main() {
    // FIX 6: texture2D() was renamed to texture().
    vec4 color = texture(gtexture, texcoord) * glcolor;
    outColor0 = color;
}
