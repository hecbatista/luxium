package io.github.hecbatista.metalshaders.glsl;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolumeTexturesTest {
    private static final VolumeTextures.Volume LUT = new VolumeTextures.Volume(32, 64, 32, true, false);

    @Test
    void volumeSamplerBecomesAnAtlasAndItsCallsUseHelpers() throws Exception {
        // Photon v1.4: uniform sampler3D depthtex0 (texture.deferred.depthtex0 = scattering.dat TEXTURE_3D ...).
        String src = """
                #version 400
                uniform sampler3D depthtex0;
                uniform sampler2D colortex1;
                layout(location = 0) out vec4 c;
                void main() {
                    c = texture(depthtex0, vec3(0.5)) + textureLod(depthtex0, vec3(0.1), 0.0)
                        + texelFetch(depthtex0, ivec3(1), 0) + vec4(textureSize(depthtex0, 0), 1.0)
                        + texture(colortex1, vec2(0.5));
                }
                """;
        String out = VolumeTextures.rewrite(src, Map.of("depthtex0", LUT)::get);
        assertFalse(out.contains("sampler3D"), out);
        assertTrue(out.contains("uniform sampler2D luxium_vol_depthtex0;"), out);
        assertTrue(out.contains("const vec4 luxium_vol_info_depthtex0 = vec4(32.0, 64.0, 32.0, 0.0);"), out);
        assertTrue(out.contains("luxium_vol_sample(luxium_vol_depthtex0, luxium_vol_info_depthtex0, vec3(0.5))"), out);
        assertTrue(out.contains("luxium_vol_fetch(luxium_vol_depthtex0, luxium_vol_info_depthtex0, ivec3(1), 0)"), out);
        assertTrue(out.contains("luxium_vol_size(luxium_vol_depthtex0, luxium_vol_info_depthtex0, 0)"), out);
        assertTrue(out.contains("texture(colortex1, vec2(0.5))"), out);
        // And it still translates.
        ProgramTranslator.translate("#version 400\nvoid main() { gl_Position = vec4(0.0); }", out, ProgramTranslator.Mode.FULLSCREEN);
    }

    @Test
    void volumeWithoutDataIsLeftAlone() {
        String src = "uniform sampler3D voxels;\nvoid main() { texture(voxels, vec3(0.0)); }\n";
        assertEquals(src, VolumeTextures.rewrite(src, n -> null));
    }

    @Test
    void atlasHasABorderedSlicePerDepth() {
        assertEquals(34 * 32, LUT.atlasWidth());
        assertEquals(66, LUT.atlasHeight());
    }
}
