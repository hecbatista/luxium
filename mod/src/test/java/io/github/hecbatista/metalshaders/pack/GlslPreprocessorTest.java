package io.github.hecbatista.metalshaders.pack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlslPreprocessorTest {
    @Test
    void macOnlyBranchIsTakenWhenMcOsMacIsDefined() throws Exception {
        String source = """
                #version 130
                #ifdef MC_OS_MAC
                    #define COLORED_LIGHTING 0
                #else
                    #define COLORED_LIGHTING 512
                #endif
                int x = COLORED_LIGHTING;
                """;

        String mac = GlslPreprocessor.preprocess(source, Map.of("MC_OS_MAC", "")).body();
        String other = GlslPreprocessor.preprocess(source, Map.of()).body();

        assertTrue(mac.contains("int x = 0;"), mac);
        assertTrue(other.contains("int x = 512;"), other);
    }

    @Test
    void versionIsHoistedToTheTop() throws Exception {
        GlslPreprocessor.Result result = GlslPreprocessor.preprocess("""
                // a comment before the version
                #version 130
                void main() {}
                """, Map.of());

        assertEquals("130", result.version());
        assertTrue(result.text().startsWith("#version 130\n"), result.text());
        assertFalse(result.body().contains("#version"), result.body());
        assertFalse(result.body().contains("METALSHADERS"), result.body());
    }

    @Test
    void onlyLiveExtensionsAreKept() throws Exception {
        GlslPreprocessor.Result result = GlslPreprocessor.preprocess("""
                #version 430 compatibility
                #if MC_VERSION >= 260300
                #extension GL_ARB_shader_image_load_store : enable
                #else
                #extension GL_ARB_never_used : enable
                #endif
                """, Map.of("MC_VERSION", "260300"));

        assertEquals("430 compatibility", result.version());
        assertEquals(List.of("GL_ARB_shader_image_load_store : enable"), result.extensions());
    }

    @Test
    void numericComparisonsWork() throws Exception {
        String body = GlslPreprocessor.preprocess("""
                #if MC_VERSION >= 260400
                newer
                #elif MC_VERSION >= 260200
                ours
                #else
                older
                #endif
                """, StandardDefines.forMac()).body();

        assertTrue(body.contains("ours"), body);
        assertFalse(body.contains("newer"), body);
        assertFalse(body.contains("older"), body);
    }

    @Test
    void unbalancedEndifIsAnError() {
        assertThrows(GlslPreprocessor.PreprocessException.class,
                () -> GlslPreprocessor.preprocess("#endif\n", Map.of()));
    }
}
