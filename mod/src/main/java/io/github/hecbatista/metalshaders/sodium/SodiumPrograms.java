package io.github.hecbatista.metalshaders.sodium;

import io.github.hecbatista.metalshaders.mixin.sodium.ShaderChunkRendererAccessor;

import java.util.HashMap;
import java.util.Map;

/**
 * Sodium's own terrain pipelines, one set per chunk format. Sodium builds them once per session
 * with whichever format came first (ShaderChunkRendererAccessor), so after Luxium switches the
 * chunk format live, Sodium would draw the new meshes with the old vertex layout: with shaders
 * turned off after being on, the terrain vanished. Swapping the sets with the format keeps every
 * pipeline Sodium draws with matching the meshes, without compiling anything twice.
 */
public final class SodiumPrograms {
    private static final Map<Object, Object> PROGRAMS_LUXIUM = new HashMap<>();
    private static final Map<Object, Object> OIT_LUXIUM = new HashMap<>();
    private static final Map<Object, Object> PROGRAMS_SODIUM = new HashMap<>();
    private static final Map<Object, Object> OIT_SODIUM = new HashMap<>();

    private SodiumPrograms() {
    }

    /** Render thread, together with the format switch and before the chunk rebuild. */
    public static void switchFormat(boolean toLuxium) {
        io.github.hecbatista.metalshaders.MetalShadersClient.LOGGER.info(
                "Sodium terrain pipelines: {} kept for the {} format, {} restored for the {} format",
                ShaderChunkRendererAccessor.luxium$programs().size() + ShaderChunkRendererAccessor.luxium$oitPrograms().size(),
                toLuxium ? "Sodium" : "Luxium", (toLuxium ? PROGRAMS_LUXIUM.size() + OIT_LUXIUM.size() : PROGRAMS_SODIUM.size() + OIT_SODIUM.size()),
                toLuxium ? "Luxium" : "Sodium");
        swap(ShaderChunkRendererAccessor.luxium$programs(), toLuxium ? PROGRAMS_SODIUM : PROGRAMS_LUXIUM,
                toLuxium ? PROGRAMS_LUXIUM : PROGRAMS_SODIUM);
        swap(ShaderChunkRendererAccessor.luxium$oitPrograms(), toLuxium ? OIT_SODIUM : OIT_LUXIUM,
                toLuxium ? OIT_LUXIUM : OIT_SODIUM);
    }

    private static void swap(Map<Object, Object> live, Map<Object, Object> keepOld, Map<Object, Object> bringNew) {
        keepOld.clear();
        keepOld.putAll(live);
        live.clear();
        live.putAll(bringNew);
        bringNew.clear();
    }
}
