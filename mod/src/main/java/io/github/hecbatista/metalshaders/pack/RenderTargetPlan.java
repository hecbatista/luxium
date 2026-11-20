package io.github.hecbatista.metalshaders.pack;

import com.mojang.renderpearl.api.GpuFormat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pack's color buffers (colortex0..31): format, whether they're cleared each frame, and
 * the clear color, read from {@code const} lines in the programs' fragment stages.
 * <p>
 * Rules (see NOTICE): names, defaults and keys follow Iris's PackRenderTargetDirectives
 * (colortexN plus the old names gcolor..gaux4 for 0..7; default format RGBA, cleared, default
 * clear color chosen at draw time). The lines are found even inside comments, because packs
 * write them there (a format name isn't valid GLSL); only lines that survived preprocessing
 * count, so a format behind a switched-off #if doesn't. Programs are read in Iris's fold order
 * and the last declaration wins (Vitrail docs/internals/render-targets.md).
 * <p>
 * Metal has no 3-channel pixel formats (Metallum's MetalConversions has none), so those are
 * promoted to 4 channels. To keep reads as in OpenGL, where alpha always reads as 1, a promoted
 * target should be cleared with alpha 1 and written with a color-only write mask.
 */
public final class RenderTargetPlan {
    /** Iris allows 32 (IrisLimits.MAX_COLOR_BUFFERS); Complementary uses colortex18 and 19. */
    public static final int MAX_TARGETS = 32;
    static final List<String> LEGACY_NAMES = List.of("gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4");

    /**
     * @param requestedFormat the pack's name, e.g. "RGB16F" ("RGBA" if it named none)
     * @param promoted        a 3-channel format given a 4th channel
     * @param clearColor      the pack's clear color, or null for the engine's default
     */
    public record Target(int index, String requestedFormat, GpuFormat format, boolean promoted,
                         boolean clear, float[] clearColor) {
    }

    private record Mapping(GpuFormat format, boolean promoted) {
    }

    /** Iris's format names (InternalTextureFormat) onto the game's formats. */
    private static final Map<String, Mapping> FORMATS = new HashMap<>();

    static {
        put("RGBA", GpuFormat.RGBA8_UNORM, false);
        put("R8", GpuFormat.R8_UNORM, false);
        put("RG8", GpuFormat.RG8_UNORM, false);
        put("RGB8", GpuFormat.RGBA8_UNORM, true);
        put("RGBA8", GpuFormat.RGBA8_UNORM, false);
        put("R8_SNORM", GpuFormat.R8_SNORM, false);
        put("RG8_SNORM", GpuFormat.RG8_SNORM, false);
        put("RGB8_SNORM", GpuFormat.RGBA8_SNORM, true);
        put("RGBA8_SNORM", GpuFormat.RGBA8_SNORM, false);
        put("R16", GpuFormat.R16_UNORM, false);
        put("RG16", GpuFormat.RG16_UNORM, false);
        put("RGB16", GpuFormat.RGBA16_UNORM, true);
        put("RGBA16", GpuFormat.RGBA16_UNORM, false);
        put("R16_SNORM", GpuFormat.R16_SNORM, false);
        put("RG16_SNORM", GpuFormat.RG16_SNORM, false);
        put("RGB16_SNORM", GpuFormat.RGBA16_SNORM, true);
        put("RGBA16_SNORM", GpuFormat.RGBA16_SNORM, false);
        put("R16F", GpuFormat.R16_FLOAT, false);
        put("RG16F", GpuFormat.RG16_FLOAT, false);
        put("RGB16F", GpuFormat.RGBA16_FLOAT, true);
        put("RGBA16F", GpuFormat.RGBA16_FLOAT, false);
        put("R32F", GpuFormat.R32_FLOAT, false);
        put("RG32F", GpuFormat.RG32_FLOAT, false);
        put("RGB32F", GpuFormat.RGBA32_FLOAT, true);
        put("RGBA32F", GpuFormat.RGBA32_FLOAT, false);
        put("R8I", GpuFormat.R8_SINT, false);
        put("RG8I", GpuFormat.RG8_SINT, false);
        put("RGB8I", GpuFormat.RGBA8_SINT, true);
        put("RGBA8I", GpuFormat.RGBA8_SINT, false);
        put("R8UI", GpuFormat.R8_UINT, false);
        put("RG8UI", GpuFormat.RG8_UINT, false);
        put("RGB8UI", GpuFormat.RGBA8_UINT, true);
        put("RGBA8UI", GpuFormat.RGBA8_UINT, false);
        put("R16I", GpuFormat.R16_SINT, false);
        put("RG16I", GpuFormat.RG16_SINT, false);
        put("RGB16I", GpuFormat.RGBA16_SINT, true);
        put("RGBA16I", GpuFormat.RGBA16_SINT, false);
        put("R16UI", GpuFormat.R16_UINT, false);
        put("RG16UI", GpuFormat.RG16_UINT, false);
        put("RGB16UI", GpuFormat.RGBA16_UINT, true);
        put("RGBA16UI", GpuFormat.RGBA16_UINT, false);
        put("R32I", GpuFormat.R32_SINT, false);
        put("RG32I", GpuFormat.RG32_SINT, false);
        put("RGB32I", GpuFormat.RGBA32_SINT, true);
        put("RGBA32I", GpuFormat.RGBA32_SINT, false);
        put("R32UI", GpuFormat.R32_UINT, false);
        put("RG32UI", GpuFormat.RG32_UINT, false);
        put("RGB32UI", GpuFormat.RGBA32_UINT, true);
        put("RGBA32UI", GpuFormat.RGBA32_UINT, false);
        // Old fixed-point formats with no modern equivalent: 8-bit RGBA holds at least what they did.
        put("RGBA2", GpuFormat.RGBA8_UNORM, false);
        put("RGBA4", GpuFormat.RGBA8_UNORM, false);
        put("R3_G3_B2", GpuFormat.RGBA8_UNORM, true);
        put("RGB5_A1", GpuFormat.RGBA8_UNORM, false);
        put("RGB565", GpuFormat.RGBA8_UNORM, true);
        put("RGB10_A2", GpuFormat.RGB10A2_UNORM, false);
        put("RGB10_A2UI", GpuFormat.RGB10A2_UINT, false);
        put("R11F_G11F_B10F", GpuFormat.RG11B10_FLOAT, false);
        // Shared-exponent float: an 8-bit target wouldn't hold it.
        put("RGB9_E5", GpuFormat.RGBA16_FLOAT, true);
    }

    private static void put(String name, GpuFormat format, boolean promoted) {
        FORMATS.put(name, new Mapping(format, promoted));
    }

    private static final Pattern CONST = Pattern.compile("const\\s+(int|bool|vec4)\\s+(\\w+)\\s*=\\s*([^;]+);");
    private static final Pattern NAME = Pattern.compile("^(colortex(\\d+)|gcolor|gdepth|gnormal|composite|gaux[1-4])(Format|Clear|ClearColor)$");

    private final Map<Integer, Target> targets;
    private final List<String> warnings;

    private RenderTargetPlan(Map<Integer, Target> targets, List<String> warnings) {
        this.targets = targets;
        this.warnings = warnings;
    }

    /** The target's settings (defaults if the pack declared nothing for it). */
    public Target target(int index) {
        Target t = targets.get(index);
        return t != null ? t : new Target(index, "RGBA", GpuFormat.RGBA8_UNORM, false, true, null);
    }

    /** Targets the pack declared something for. */
    public Map<Integer, Target> declared() {
        return Map.copyOf(targets);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** Folds the directives of preprocessed fragment sources, given in Iris's fold order. */
    public static RenderTargetPlan fold(List<String> fragmentSourcesInFoldOrder) {
        Map<Integer, String> format = new TreeMap<>();
        Map<Integer, Boolean> clear = new TreeMap<>();
        Map<Integer, float[]> clearColor = new TreeMap<>();
        List<String> warnings = new ArrayList<>();
        for (String source : fragmentSourcesInFoldOrder) {
            Matcher m = CONST.matcher(source);
            while (m.find()) {
                Matcher n = NAME.matcher(m.group(2));
                if (!n.matches()) {
                    continue;
                }
                int index = n.group(2) != null ? Integer.parseInt(n.group(2)) : LEGACY_NAMES.indexOf(n.group(1));
                if (index < 0 || index >= MAX_TARGETS) {
                    continue;
                }
                String value = m.group(3).strip();
                switch (n.group(3)) {
                    case "Format" -> {
                        if (m.group(1).equals("int") && FORMATS.containsKey(value.toUpperCase(Locale.ROOT))) {
                            format.put(index, value.toUpperCase(Locale.ROOT));
                        } else {
                            warnings.add("Unrecognized format " + value + " for " + m.group(2) + ", ignored");
                        }
                    }
                    case "Clear" -> {
                        if (m.group(1).equals("bool")) {
                            clear.put(index, value.equals("true"));
                        }
                    }
                    case "ClearColor" -> {
                        float[] c = parseVec4(value);
                        if (m.group(1).equals("vec4") && c != null) {
                            clearColor.put(index, c);
                        } else {
                            warnings.add("Unreadable clear color " + value + " for " + m.group(2));
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        Map<Integer, Target> targets = new TreeMap<>();
        for (int i = 0; i < MAX_TARGETS; i++) {
            if (!format.containsKey(i) && !clear.containsKey(i) && !clearColor.containsKey(i)) {
                continue;
            }
            String name = format.getOrDefault(i, "RGBA");
            Mapping mapping = FORMATS.get(name);
            targets.put(i, new Target(i, name, mapping.format(), mapping.promoted(), clear.getOrDefault(i, true), clearColor.get(i)));
        }
        return new RenderTargetPlan(targets, warnings);
    }

    /** "vec4(1.0, 0.5, 0.0, 1.0)" -> {1, 0.5, 0, 1}; also "vec4(0.0)". Null if not that shape. */
    static float[] parseVec4(String value) {
        Matcher m = Pattern.compile("^vec4\\s*\\(([^)]*)\\)$").matcher(value.strip());
        if (!m.matches()) {
            return null;
        }
        String[] parts = m.group(1).split(",");
        try {
            if (parts.length == 1) {
                float v = Float.parseFloat(parts[0].strip());
                return new float[]{v, v, v, v};
            }
            if (parts.length == 4) {
                float[] out = new float[4];
                for (int i = 0; i < 4; i++) {
                    out[i] = Float.parseFloat(parts[i].strip());
                }
                return out;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }
}
