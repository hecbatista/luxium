#!/bin/zsh
# Experiment 02: how far do Complementary's world0 programs get toward Metal with only
# crude stand-ins (stand-ins/*.inc: #defines for texture2D, gl_FragData, gl_ModelViewMatrix...)?
# Measurement only, not the translator. Two shortcuts make it optimistic:
#   -R    lets loose uniforms through (the real path needs a uniform block)
#   --aml numbers in/out slots per stage on its own (the real path must match stages by name)
# Needs: cd mod && ./gradlew test first (writes build/preprocessed/).
# Result 2026-11-20: 59/60 to SPIR-V, 59/59 to Metal; gbuffers_line.vsh needs Iris's projectionMatrix.
cd "${0:A:h}"
IN=../../mod/build/preprocessed/ComplementaryUnbound_r5.10/world0
rm -rf out; mkdir -p out
spv=0; metal=0; total=0
for f in $IN/*.{vsh,fsh}(N); do
  n=${f:t}; case $n in clrwl_*|dh_*) continue;; esac
  case $n in *.vsh) st=vert; flip=(--flip-vert-y);; *.fsh) st=frag; flip=();; esac
  total=$((total + 1))
  { sed -n 1p $f | sed -E 's/^#version .*/#version 450/'; cat stand-ins/common.inc stand-ins/$st.inc; tail -n +2 $f; } > out/$n
  if glslangValidator -V -R --aml --target-env vulkan1.2 --auto-map-bindings -S $st out/$n -o out/$n.spv > out/$n.log 2>&1; then
    spv=$((spv + 1))
    if spirv-cross out/$n.spv --msl --msl-version 40000 --msl-decoration-binding --msl-texture-buffer-native $flip --output out/$n.metal >> out/$n.log 2>&1 \
       && xcrun metal -std=metal4.0 -c out/$n.metal -o out/$n.air >> out/$n.log 2>&1; then
      metal=$((metal + 1))
    else print "METAL FAIL $n"; fi
  else
    print "SPIR-V FAIL $n: $(grep -m1 -E '^ERROR: .+:[0-9]+:' out/$n.log | sed -E 's/^ERROR: [^:]+:[0-9]+: //')"
  fi
done
print "SPIR-V: $spv/$total   Metal: $metal/$spv"
