#!/bin/zsh
# Experiment 01: push one pack-style shader through GLSL -> SPIR-V -> Metal.
# Settings copy Minecraft's GlslCompiler (Vulkan 1.2, auto bindings) and
# Metallum's MetalCrossShaderCompiler (macOS, MSL 4.0, decoration bindings,
# native texel buffers, flipped Y in the vertex stage).
set -e
cd "${0:A:h}"
mkdir -p out

echo "1) The original pack shader as old OpenGL GLSL (should pass):"
glslangValidator -S vert pack/gbuffers_textured.vsh
glslangValidator -S frag pack/gbuffers_textured.fsh

echo "2) The same file compiled for Vulkan (expected to FAIL):"
glslangValidator -V -S vert pack/gbuffers_textured.vsh -o out/orig-vert.spv || echo "   -> failed, as expected"

echo "3) The hand-translated version to SPIR-V:"
glslangValidator -V --target-env vulkan1.2 --auto-map-bindings translated/gbuffers_textured.vert -o out/vert.spv
glslangValidator -V --target-env vulkan1.2 --auto-map-bindings translated/gbuffers_textured.frag -o out/frag.spv

echo "4) SPIR-V to Metal code:"
spirv-cross out/vert.spv --msl --msl-version 40000 --msl-decoration-binding --msl-texture-buffer-native \
  --flip-vert-y --rename-entry-point main vert_main vert --output out/vert.metal
spirv-cross out/frag.spv --msl --msl-version 40000 --msl-decoration-binding --msl-texture-buffer-native \
  --rename-entry-point main frag_main frag --output out/frag.metal

echo "5) Compile the Metal code with Apple's compiler:"
xcrun metal -std=metal4.0 -c out/vert.metal -o out/vert.air
xcrun metal -std=metal4.0 -c out/frag.metal -o out/frag.air
xcrun metallib out/vert.air out/frag.air -o out/gbuffers_textured.metallib

echo "Done: out/gbuffers_textured.metallib"
