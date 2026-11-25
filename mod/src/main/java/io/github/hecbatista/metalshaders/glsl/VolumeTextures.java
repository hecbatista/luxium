package io.github.hecbatista.metalshaders.glsl;

import io.github.hecbatista.metalshaders.glsl.GlslTokenizer.Kind;
import io.github.hecbatista.metalshaders.glsl.GlslTokenizer.Token;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * 3D textures as 2D atlases. The game's pipeline builder refuses 3D samplers
 * (PipelineBuilder: "3D textures not supported"), but packs load volumes with Iris's raw-texture
 * directive ({@code texture.deferred.depthtex0 = scattering.dat TEXTURE_3D RGB16F 32 64 32 ...}:
 * Photon's atmosphere LUT and cloud noise). Luxium uploads such a volume as a 2D texture whose
 * slices sit side by side, each with a one-texel border copied from its opposite edge (repeat) or
 * its own edge (clamp), so the hardware's bilinear filter is exact inside a slice
 * (CustomTextures.loadRaw). This class rewrites a stage to read that atlas:
 * <ul>
 *   <li>{@code uniform sampler3D X;} becomes {@code uniform sampler2D luxium_vol_X;} plus a
 *   constant with the volume's size and mode. The new name binds only the volume, as Iris renames
 *   such a sampler to its own {@code customtex<N>}: another program of the stage that reads X as
 *   a 2D sampler still reads the render target (Photon's composite1 reads colortex0, the scene,
 *   where its composite reads colortex0 as 3D noise);</li>
 *   <li>{@code texture(X, p)}, {@code textureLod(X, p, l)}, {@code texelFetch(X, c, l)} and
 *   {@code textureSize(X, l)} call helpers that blend the two nearest slices.</li>
 * </ul>
 * Only samplers the pack has volume data for are rewritten; any other sampler3D is left alone
 * (and the pipeline then fails as before). Our own code.
 */
public final class VolumeTextures {
    /** A volume's size in texels and how it is read (Iris TextureFilteringData: blur, clamp). */
    public record Volume(int x, int y, int z, boolean linear, boolean repeat) {
        /** Atlas width: one bordered slice after another. */
        public int atlasWidth() {
            return (x + 2) * z;
        }

        public int atlasHeight() {
            return y + 2;
        }

        float mode() {
            return (repeat ? 1 : 0) + (linear ? 0 : 2);
        }
    }

    /** A volume sampler's name in the rewritten stage: {@code luxium_vol_<pack name>}. */
    public static final String PREFIX = "luxium_vol_";

    /** info = (size x, size y, size z, mode); mode bit 0 = repeat, bit 1 = nearest. */
    private static final String HELPERS = """
            vec4 luxium_vol_sample(sampler2D s, vec4 info, vec3 p) {
                vec3 n = info.xyz;
                bool repeat = mod(info.w, 2.0) > 0.5;
                bool nearest = info.w > 1.5;
                p = repeat ? fract(p) : clamp(p, 0.0, 1.0);
                vec2 uv = p.xy * n.xy + 1.0;
                float z = p.z * n.z - 0.5;
                float z0 = floor(z);
                float f = z - z0;
                if (nearest) {
                    z0 = min(floor(p.z * n.z), n.z - 1.0);
                    f = 0.0;
                }
                float z1 = z0 + 1.0;
                if (repeat) {
                    z0 = mod(z0, n.z);
                    z1 = mod(z1, n.z);
                } else {
                    z0 = clamp(z0, 0.0, n.z - 1.0);
                    z1 = clamp(z1, 0.0, n.z - 1.0);
                }
                vec2 atlas = vec2((n.x + 2.0) * n.z, n.y + 2.0);
                vec4 a = textureLod(s, vec2(uv.x + z0 * (n.x + 2.0), uv.y) / atlas, 0.0);
                vec4 b = textureLod(s, vec2(uv.x + z1 * (n.x + 2.0), uv.y) / atlas, 0.0);
                return mix(a, b, f);
            }
            vec4 luxium_vol_sample(sampler2D s, vec4 info, vec3 p, float biasOrLod) {
                return luxium_vol_sample(s, info, p);
            }
            vec4 luxium_vol_fetch(sampler2D s, vec4 info, ivec3 c, int lod) {
                ivec3 n = ivec3(info.xyz);
                c = clamp(c, ivec3(0), n - 1);
                return texelFetch(s, ivec2(c.x + 1 + c.z * (n.x + 2), c.y + 1), 0);
            }
            ivec3 luxium_vol_size(sampler2D s, vec4 info, int lod) {
                return ivec3(info.xyz);
            }
            """;

    private VolumeTextures() {
    }

    /**
     * Rewrites a preprocessed stage. {@code volumes} gives the volume a sampler name reads in this
     * stage, or null. Returns the source unchanged when it declares no such sampler.
     */
    public static String rewrite(String source, Function<String, @Nullable Volume> volumes) {
        if (!source.contains("sampler3D")) {
            return source;
        }
        List<Token> t = GlslTokenizer.tokenize(source);
        Set<String> names = new LinkedHashSet<>();
        List<Token> out = new ArrayList<>(t.size() + 32);
        boolean helpersAdded = false;
        int depth = 0;
        for (int i = 0; i < t.size(); i++) {
            Token tok = t.get(i);
            if (tok.is("{")) {
                depth++;
            } else if (tok.is("}")) {
                depth = Math.max(0, depth - 1);
            }
            if (depth == 0 && tok.kind() == Kind.IDENT && tok.is("sampler3D")) {
                // uniform [layout...] sampler3D a, b; : every name must have a volume
                int end = i + 1;
                List<String> declared = new ArrayList<>();
                while (end < t.size() && !t.get(end).is(";")) {
                    if (t.get(end).kind() == Kind.IDENT) {
                        declared.add(t.get(end).text());
                    }
                    end++;
                }
                if (end < t.size() && !declared.isEmpty() && declared.stream().allMatch(n -> volumes.apply(n) != null)) {
                    out.add(new Token(Kind.IDENT, "sampler2D"));
                    for (int j = i + 1; j <= end; j++) {
                        Token d = t.get(j);
                        out.add(d.kind() == Kind.IDENT && declared.contains(d.text()) ? new Token(Kind.IDENT, PREFIX + d.text()) : d);
                    }
                    StringBuilder sb = new StringBuilder("\n");
                    for (String n : declared) {
                        Volume v = volumes.apply(n);
                        sb.append(String.format(Locale.ROOT, "const vec4 %sinfo_%s = vec4(%d.0, %d.0, %d.0, %.1f);\n",
                                PREFIX, n, v.x(), v.y(), v.z(), v.mode()));
                        names.add(n);
                    }
                    if (!helpersAdded) {
                        sb.append(HELPERS);
                        helpersAdded = true;
                    }
                    out.add(new Token(Kind.SPACE, sb.toString()));
                    i = end;
                    continue;
                }
            }
            out.add(tok);
        }
        if (names.isEmpty()) {
            return source;
        }
        // Every other use of a volume's name reads the renamed sampler (as Iris renames such a
        // sampler to its own customtex<N>, so other programs' samplers of that name are untouched).
        for (int i = 0; i < out.size(); i++) {
            Token tok = out.get(i);
            if (tok.kind() == Kind.IDENT && names.contains(tok.text())) {
                out.set(i, new Token(Kind.IDENT, PREFIX + tok.text()));
            }
        }
        // Calls whose first argument is one of the volumes.
        List<Token> result = new ArrayList<>(out.size());
        for (int i = 0; i < out.size(); i++) {
            Token tok = out.get(i);
            String helper = tok.kind() == Kind.IDENT ? helperFor(tok.text()) : null;
            if (helper != null) {
                int open = nextCode(out, i + 1);
                int arg = open >= 0 && out.get(open).is("(") ? nextCode(out, open + 1) : -1;
                int comma = arg >= 0 ? nextCode(out, arg + 1) : -1;
                if (arg >= 0 && out.get(arg).kind() == Kind.IDENT && out.get(arg).text().startsWith(PREFIX)
                        && names.contains(out.get(arg).text().substring(PREFIX.length()))
                        && comma >= 0 && out.get(comma).is(",")) {
                    String n = out.get(arg).text().substring(PREFIX.length());
                    result.add(new Token(Kind.IDENT, helper));
                    for (int j = i + 1; j <= arg; j++) {
                        result.add(out.get(j));
                    }
                    result.add(new Token(Kind.SYMBOL, ","));
                    result.add(new Token(Kind.SPACE, " "));
                    result.add(new Token(Kind.IDENT, PREFIX + "info_" + n));
                    i = arg;
                    continue;
                }
            }
            result.add(tok);
        }
        return GlslTokenizer.join(result);
    }

    private static @Nullable String helperFor(String function) {
        return switch (function) {
            case "texture", "textureLod", "texture3D", "texture3DLod" -> PREFIX + "sample";
            case "texelFetch", "texelFetch3D" -> PREFIX + "fetch";
            case "textureSize" -> PREFIX + "size";
            default -> null;
        };
    }

    private static int nextCode(List<Token> t, int from) {
        for (int i = from; i < t.size(); i++) {
            if (!t.get(i).trivia() && !t.get(i).text().isEmpty()) {
                return i;
            }
        }
        return -1;
    }
}
