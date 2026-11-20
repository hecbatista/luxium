package io.github.hecbatista.metalshaders.pack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The names the game defines before a pack's program starts, so the pack can ask
 * "which game, which shader mod, which OS?" with {@code #if}.
 * <p>
 * The plan is to look like <b>Iris on a Mac</b>, so packs take the branches they were tested on
 * there. Only names Complementary Unbound r5.10 actually reads are here, and only where we can
 * justify the value. Everything else Iris defines (StandardMacros.java:47-121) is left out on
 * purpose until we need it, because defining a name changes which branch a pack takes:
 * <ul>
 *   <li>Feature claims (IRIS_FEATURE_*, IRIS_INLINE_GLINT, IRIS_HAS_TRANSLUCENCY_SORTING,
 *       DISTANT_HORIZONS, ...): only once we actually support the feature.</li>
 *   <li>MC_GL_VERSION, MC_GLSL_VERSION, MC_GL_VENDOR_*, MC_GL_RENDERER_*: there's no OpenGL
 *       driver under Metal, so we'd be guessing. Complementary doesn't read the versions.</li>
 * </ul>
 */
public final class StandardDefines {
    /** Minecraft 26.3 written the way Iris writes versions: "26" + "03" + "00". */
    public static final String MC_VERSION = "260300";

    /**
     * The Iris release whose behavior we follow: 1.11.6 on Iris's 26.3 branch
     * (references/iris/build.gradle.kts:20), written the same way.
     */
    public static final String IRIS_VERSION = "11106";

    /**
     * Iris's render stages in its order; each name's value is its position.
     * Table copied from Iris {@code WorldRenderingPhase.java} (see NOTICE).
     */
    static final List<String> RENDER_STAGES = List.of(
            "NONE", "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID",
            "TERRAIN_SOLID", "TERRAIN_CUTOUT_MIPPED", "TERRAIN_CUTOUT", "ENTITIES", "BLOCK_ENTITIES",
            "DESTROY", "OUTLINE", "DEBUG", "HAND_SOLID", "TERRAIN_TRANSLUCENT", "TRIPWIRE",
            "PARTICLES", "CLOUDS", "RAIN_SNOW", "WORLD_BORDER", "HAND_TRANSLUCENT");

    private StandardDefines() {
    }

    /** Name -> value. A value of "" means "just defined". */
    public static Map<String, String> forMac() {
        Map<String, String> defines = new LinkedHashMap<>();
        defines.put("MC_VERSION", MC_VERSION);
        // Complementary reads this to switch off colored lighting and its compute/3D-image code.
        defines.put("MC_OS_MAC", "");
        defines.put("IS_IRIS", "");
        defines.put("IRIS_VERSION", IRIS_VERSION);
        for (int i = 0; i < RENDER_STAGES.size(); i++) {
            defines.put("MC_RENDER_STAGE_" + RENDER_STAGES.get(i), Integer.toString(i));
        }
        return Collections.unmodifiableMap(defines);
    }
}
