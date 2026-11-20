#!/bin/zsh
# Checks that the preprocessed programs are valid OpenGL GLSL, using glslangValidator.
# Run `./gradlew test` first: RealPackTest writes them to build/preprocessed/<pack>/.
#
# This is OpenGL mode, not Vulkan, so it checks the preprocessor's output, not the translator.
# Expected failures on Complementary Unbound r5.10 (as of 2026-11-20), all explained:
#   dh_*     needs the Distant Horizons mod (dhProjection only exists under DISTANT_HORIZONS)
#   clrwl_*  needs the Colorwheel mod (it supplies clrwl_computeFragment)
#   shadowcomp.csh  off on Mac: shaders.properties disables it when COLORED_LIGHTING == 0
#   gbuffers_line.vsh  uses projectionMatrix/modelViewMatrix/vaPosition, which Iris injects (step 4)
cd "${0:A:h}/.."
setopt null_glob
pass=0; fail=0
for pack in build/preprocessed/*(/); do
  for f in $pack/**/*.{vsh,fsh,csh,gsh}; do
    case $f in
      *.vsh) stage=vert;; *.fsh) stage=frag;; *.csh) stage=comp;; *.gsh) stage=geom;;
    esac
    if out=$(glslangValidator -S $stage $f 2>&1); then
      pass=$((pass + 1))
    else
      fail=$((fail + 1))
      print "FAIL ${f#build/preprocessed/}: $(print -r -- $out | grep -m1 ERROR)"
    fi
  done
done
print "pass=$pass fail=$fail"
