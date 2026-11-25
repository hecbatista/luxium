package io.github.hecbatista.metalshaders.uniform;

import io.github.hecbatista.metalshaders.glsl.ProgramTranslator.Uniform;
import io.github.hecbatista.metalshaders.glsl.Std140Layout;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UniformWriterTest {
    private static Uniform u(String type, String name) {
        return new Uniform(type, name, "", null);
    }

    @Test
    void writesTheFogStruct() {
        // gl_Fog: vec4 color at 0, then density, start, end, scale (std140).
        Std140Layout.Layout layout = Std140Layout.of(List.of(u("float", "far"), u("LuxiumFog", "luxium_Fog")));
        float[] fog = {0.1f, 0.2f, 0.3f, 1f, 0f, 160f, 192f, 1f / 32f};
        ByteBuffer out = ByteBuffer.allocate(layout.size()).order(ByteOrder.nativeOrder());

        Set<String> missing = UniformWriter.write(layout, Map.<String, Object>of("far", 192f, "luxium_Fog", fog)::get, Map.of(), out);

        assertEquals(Set.of(), missing);
        assertEquals(0.3f, out.getFloat(16 + 8));
        assertEquals(160f, out.getFloat(16 + 20));
        assertEquals(192f, out.getFloat(16 + 24));
        assertEquals(1f / 32f, out.getFloat(16 + 28));
    }

    @Test
    void writesEachValueAtItsOffset() {
        Std140Layout.Layout layout = Std140Layout.of(List.of(
                u("float", "rainStrength"),     // 0
                u("vec3", "cameraPosition"),    // 16
                u("int", "worldTime"),          // 28: fits after the vec3
                u("ivec2", "eyeBrightness"),    // 32
                u("bool", "is_invisible"),      // 40
                u("mat3", "normal"),            // 48: three columns, each 16 bytes
                u("mat4", "proj")));            // 96
        Map<String, Object> values = Map.of(
                "rainStrength", 0.5f, "cameraPosition", new float[]{1, 2, 3}, "worldTime", 6000,
                "eyeBrightness", new int[]{16, 240}, "is_invisible", true,
                "normal", new Matrix3f().m10(7f), "proj", new Matrix4f().m32(-9f));
        ByteBuffer out = ByteBuffer.allocate(layout.size()).order(ByteOrder.nativeOrder());

        Set<String> missing = UniformWriter.write(layout, values::get, Map.of(), out);

        assertEquals(Set.of(), missing);
        assertEquals(0.5f, out.getFloat(0));
        assertEquals(3f, out.getFloat(16 + 8));
        assertEquals(6000, out.getInt(28));
        assertEquals(240, out.getInt(36));
        assertEquals(1, out.getInt(40));
        assertEquals(7f, out.getFloat(48 + 16));           // column 1, row 0
        assertEquals(1f, out.getFloat(48 + 32 + 8));       // column 2, row 2 (identity)
        assertEquals(-9f, out.getFloat(96 + 3 * 16 + 8));  // column 3, row 2
    }

    @Test
    void missingNamesUseThePackDefaultOrZeroAndAreReported() {
        Std140Layout.Layout layout = Std140Layout.of(List.of(u("float", "cloudHeight"), u("float", "unknownThing")));
        ByteBuffer out = ByteBuffer.allocate(layout.size()).order(ByteOrder.nativeOrder());

        Set<String> missing = UniformWriter.write(layout, name -> null, Map.of("cloudHeight", "192.0"), out);

        assertEquals(192f, out.getFloat(0));
        assertEquals(0f, out.getFloat(4));
        assertEquals(Set.of("unknownThing"), missing);
    }
}
