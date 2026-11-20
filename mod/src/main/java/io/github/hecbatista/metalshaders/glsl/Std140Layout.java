package io.github.hecbatista.metalshaders.glsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out where each uniform sits, in bytes, inside the {@code LuxiumUniforms} block, using
 * the std140 rules of the GLSL spec ("Standard Uniform Block Layout", OpenGL 4.6 spec 7.6.2.2).
 * The game writes values at these offsets and the GPU reads them back from the same places,
 * so a single wrong offset silently shifts every value after it.
 * <p>
 * The rules in short: scalars take 4 bytes; vec2 is 8; vec3 and vec4 start on a 16-byte
 * boundary (a vec3 is 12 bytes, so a float can follow it in the same 16); a matrix is one
 * vec4-aligned column after another; each array element and each struct is rounded up to 16.
 */
public final class Std140Layout {
    /** One value's place in the block. {@code stride} is the distance between array elements (0 if not an array). */
    public record Member(String name, String type, int arrayLength, int offset, int size, int stride) {
    }

    public record Layout(List<Member> members, int size) {
    }

    /** Struct types the translator adds itself: name -> members in order (type, name). */
    private static final Map<String, List<String[]>> STRUCTS = Map.of(
            "LuxiumFog", List.of(
                    new String[]{"vec4", "color"}, new String[]{"float", "density"},
                    new String[]{"float", "start"}, new String[]{"float", "end"}, new String[]{"float", "scale"}));

    private static final Pattern VECTOR = Pattern.compile("^[biu]?vec([234])$");
    private static final Pattern MATRIX = Pattern.compile("^mat([234])(?:x([234]))?$");
    private static final Pattern ARRAY = Pattern.compile("^\\[(\\d+)]$");

    private record Info(int align, int size) {
    }

    private Std140Layout() {
    }

    /** Lays out the uniforms in the order given (the translator's block order). */
    public static Layout of(List<ProgramTranslator.Uniform> uniforms) {
        List<Member> members = new ArrayList<>();
        int offset = 0;
        for (ProgramTranslator.Uniform u : uniforms) {
            Info element = info(u.type());
            int length = arrayLength(u.array());
            int align;
            int size;
            int stride = 0;
            if (length > 0) {
                stride = roundUp(element.size(), 16);
                align = roundUp(element.align(), 16);
                size = stride * length;
            } else {
                align = element.align();
                size = element.size();
            }
            offset = roundUp(offset, align);
            members.add(new Member(u.name(), u.type(), length, offset, size, stride));
            offset += size;
        }
        return new Layout(List.copyOf(members), offset);
    }

    private static Info info(String type) {
        switch (type) {
            case "float", "int", "uint", "bool" -> {
                return new Info(4, 4);
            }
            default -> {
            }
        }
        Matcher v = VECTOR.matcher(type);
        if (v.matches()) {
            int n = Integer.parseInt(v.group(1));
            return n == 2 ? new Info(8, 8) : new Info(16, n * 4);
        }
        Matcher m = MATRIX.matcher(type);
        if (m.matches()) {
            int columns = Integer.parseInt(m.group(1));
            return new Info(16, columns * 16);   // each column is a vector rounded up to vec4
        }
        List<String[]> struct = STRUCTS.get(type);
        if (struct != null) {
            int offset = 0;
            int align = 16;
            for (String[] member : struct) {
                Info info = info(member[0]);
                align = Math.max(align, info.align());
                offset = roundUp(offset, info.align()) + info.size();
            }
            return new Info(align, roundUp(offset, 16));
        }
        throw new IllegalArgumentException("No std140 rule for type " + type);
    }

    private static int arrayLength(String array) {
        if (array == null || array.isEmpty()) {
            return 0;
        }
        Matcher a = ARRAY.matcher(array);
        if (!a.matches()) {
            throw new IllegalArgumentException("Unsupported array in uniform block: " + array);
        }
        return Integer.parseInt(a.group(1));
    }

    private static int roundUp(int value, int multiple) {
        return (value + multiple - 1) / multiple * multiple;
    }
}
