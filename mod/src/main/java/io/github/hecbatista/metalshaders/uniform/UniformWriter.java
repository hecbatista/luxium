package io.github.hecbatista.metalshaders.uniform;

import io.github.hecbatista.metalshaders.glsl.Std140Layout;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Writes uniform values into the bytes of a {@code LuxiumUniforms} block, at the offsets
 * {@link Std140Layout} worked out.
 * <p>
 * Values are plain Java objects: Float/Integer/Boolean for scalars, float[] or int[] for
 * vectors, JOML matrices for mat3/mat4. A name with no value gets the pack's own default
 * (like {@code uniform float cloudHeight = 192.0;}) if it has a simple one, otherwise zero,
 * and is reported as missing so the log can say which values the engine didn't supply.
 */
public final class UniformWriter {
    private UniformWriter() {
    }

    /**
     * @param values   name -> value, or null if this engine doesn't supply the name
     * @param defaults name -> the pack's initializer text, for names that have one
     * @param out      a buffer of at least {@code layout.size()} bytes; this writes from position 0
     * @return the names that got neither a value nor a usable default
     */
    public static Set<String> write(Std140Layout.Layout layout, Function<String, Object> values,
                                    Map<String, String> defaults, ByteBuffer out) {
        ByteBuffer b = out.duplicate().order(ByteOrder.nativeOrder());
        for (int i = 0; i < layout.size(); i++) {
            b.put(i, (byte) 0);
        }
        Set<String> missing = new TreeSet<>();
        for (Std140Layout.Member m : layout.members()) {
            Object value = values.apply(m.name());
            if (value == null) {
                value = parseDefault(m.type(), defaults.get(m.name()));
            }
            if (value == null || m.arrayLength() > 0) {
                // Arrays and structs (luxium_TextureMatrix, luxium_Fog) are per-draw values: step 6.
                if (value == null) {
                    missing.add(m.name());
                }
                continue;
            }
            writeOne(b, m.offset(), m.type(), value, m.name());
        }
        return missing;
    }

    private static void writeOne(ByteBuffer b, int at, String type, Object value, String name) {
        switch (type) {
            case "float" -> b.putFloat(at, toFloat(value));
            case "int", "uint" -> b.putInt(at, toInt(value));
            case "bool" -> b.putInt(at, toInt(value) != 0 ? 1 : 0);
            case "vec2", "vec3", "vec4" -> {
                float[] v = (float[]) value;
                for (int i = 0; i < v.length; i++) {
                    b.putFloat(at + 4 * i, v[i]);
                }
            }
            case "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4" -> {
                int[] v = (int[]) value;
                for (int i = 0; i < v.length; i++) {
                    b.putInt(at + 4 * i, v[i]);
                }
            }
            case "mat4" -> {
                Matrix4fc m = (Matrix4fc) value;
                for (int col = 0; col < 4; col++) {
                    for (int row = 0; row < 4; row++) {
                        b.putFloat(at + col * 16 + row * 4, m.get(col, row));
                    }
                }
            }
            case "mat3" -> {
                Matrix3fc m = (Matrix3fc) value;
                for (int col = 0; col < 3; col++) {      // each column padded to 16 bytes
                    for (int row = 0; row < 3; row++) {
                        b.putFloat(at + col * 16 + row * 4, m.get(col, row));
                    }
                }
            }
            default -> throw new IllegalArgumentException("Can't write " + name + " of type " + type);
        }
    }

    private static float toFloat(Object v) {
        return v instanceof Boolean bool ? (bool ? 1f : 0f) : ((Number) v).floatValue();
    }

    private static int toInt(Object v) {
        return v instanceof Boolean bool ? (bool ? 1 : 0) : ((Number) v).intValue();
    }

    /** "192.0" -> 192.0f, "false" -> false. Anything more complex isn't a default we use. */
    static Object parseDefault(String type, String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        try {
            return switch (type) {
                case "float" -> Float.parseFloat(t.replaceAll("[fF]$", ""));
                case "int" -> Integer.parseInt(t);
                case "bool" -> t.equals("true") ? Boolean.TRUE : t.equals("false") ? Boolean.FALSE : null;
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
