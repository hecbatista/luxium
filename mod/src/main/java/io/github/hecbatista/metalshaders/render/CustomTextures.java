package io.github.hecbatista.metalshaders.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.hecbatista.metalshaders.MetalShadersClient;
import io.github.hecbatista.metalshaders.glsl.VolumeTextures;
import io.github.hecbatista.metalshaders.pack.PackReader;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The pack's {@code texture.<stage>.<sampler>=<png>} directives (shaders.properties): in the
 * programs of that stage, the sampler reads the pack's own image instead of what it would read
 * otherwise. Complementary uses it for {@code texture.deferred.colortex3} and
 * {@code texture.gbuffers.gaux4} (lib/textures/cloud-water.png: cloud shapes, water waves, cloud
 * shadows and caustics). Without it those samplers read live render targets, which gave blotchy,
 * cut and flickering clouds, reflections that broke while jumping, and grey patterns flashing on
 * the ground (the user's reports, 2026-11-23).
 * <p>
 * Behaviour follows Iris (see NOTICE): stages from {@code TextureStage.parse} (gbuffers also
 * covers the shadow passes; composite also covers final); a directive names a sampler by any of
 * its names, so {@code gaux4} also covers {@code colortex7} ({@code ProgramSamplers
 * .CustomTextureSamplerInterceptor}); in deferred and composite passes an override of a render
 * target is off once an earlier pass of the same stage has written that target this frame
 * ({@code CompositeRenderer}'s flippedAtLeastOnce); filtering from {@code <png>.mcmeta}, nearest
 * and repeat unless it says {@code blur} or {@code clamp} ({@code NativeImageBackedCustomTexture}).
 * {@code customTexture.<sampler>=<png>} (Solas: {@code milkyWayTex}) binds the image to that
 * sampler name in every program, wherever the name isn't a render target (Iris
 * {@code IrisSamplers.addCustomTextures}).
 * Iris's raw-texture forms (TEXTURE_2D, TEXTURE_3D) and {@code minecraft:} resource paths are
 * read too (loadRaw, gameTexture).
 */
public final class CustomTextures {
    public enum Stage { SETUP, BEGIN, SHADOWCOMP, PREPARE, GBUFFERS, DEFERRED, COMPOSITE }

    /** texture is null for the game's own textures ({@code minecraft:...}), which aren't ours to close. */
    private record Entry(@Nullable GpuTexture texture, GpuTextureView view, GpuSampler sampler, VolumeTextures.@Nullable Volume volume) {
        Entry(GpuTexture texture, GpuTextureView view, GpuSampler sampler) {
            this(texture, view, sampler, null);
        }

        void close() {
            if (texture != null) {
                view.close();
                texture.close();
            }
        }
    }

    /** Per stage: sampler key (see {@link #key}) to the image. */
    private static final Map<Stage, Map<String, Entry>> byStage = new EnumMap<>(Stage.class);
    /**
     * {@code customTexture.<sampler>=<png>}: sampler name to the image, in every program of every
     * stage (Iris CustomTextureManager's irisCustomTextures, bound by IrisSamplers.addCustomTextures
     * after the render targets, so a render-target name keeps its target).
     */
    private static final Map<String, Entry> byName = new HashMap<>();
    /** Raw TEXTURE_3D images, per stage and by name: read only through VolumeTextures' renamed samplers. */
    private static final Map<Stage, Map<String, Entry>> volumesByStage = new EnumMap<>(Stage.class);
    private static final Map<String, Entry> volumesByName = new HashMap<>();
    /** The sampler each loaded image (and noisetex) is read with. */
    private static final Map<GpuTextureView, GpuSampler> samplerOf = new IdentityHashMap<>();

    private CustomTextures() {
    }

    static @Nullable Stage parseStage(String name) {
        return switch (name) {
            case "setup" -> Stage.SETUP;
            case "begin" -> Stage.BEGIN;
            case "shadowcomp" -> Stage.SHADOWCOMP;
            case "prepare" -> Stage.PREPARE;
            case "gbuffers" -> Stage.GBUFFERS;
            case "deferred" -> Stage.DEFERRED;
            case "composite" -> Stage.COMPOSITE;
            default -> null;
        };
    }

    /** The stage a chain pass belongs to, by its program name. */
    static Stage stageOfPass(String pass) {
        if (pass.startsWith("deferred")) {
            return Stage.DEFERRED;
        }
        if (pass.startsWith("prepare")) {
            return Stage.PREPARE;
        }
        if (pass.startsWith("shadowcomp")) {
            return Stage.SHADOWCOMP;
        }
        return Stage.COMPOSITE;   // composite*, final
    }

    /** A render target's samplers share one key ("colortex7" for gaux4 too); other names stay as they are. */
    static String key(String samplerName) {
        Integer index = PackChain.targetIndex(samplerName);
        return index != null ? "colortex" + index : samplerName;
    }

    /** Reads every {@code texture.<stage>.<sampler>} directive of the preprocessed properties. */
    public static void load(Path pack, Properties props) {
        unload();
        GpuDevice device = RenderSystem.getDevice();
        List<String> loaded = new ArrayList<>();
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith("texture.") || name.equals("texture.noise")) {
                continue;
            }
            String[] parts = name.split("\\.", 3);
            Stage stage = parts.length == 3 ? parseStage(parts[1]) : null;
            if (stage == null) {
                MetalShadersClient.LOGGER.warn("Custom texture {}: unknown stage, skipped (Iris does the same)", name);
                continue;
            }
            // Iris: "TODO: Is there a better way" -- the sampler name is cut at its first dot.
            String sampler = parts[2].split("\\.")[0];
            String value = props.getProperty(name).strip();
            try {
                Entry e = value.contains(" ") ? loadRaw(device, pack, value) : value.contains(":") ? gameTexture(value) : loadPng(device, pack, value);
                if (e == null) {
                    MetalShadersClient.LOGGER.warn("Custom texture {} = {}: no such file in the pack, skipped", name, value);
                    continue;
                }
                (e.volume() != null ? volumesByStage : byStage).computeIfAbsent(stage, s -> new HashMap<>()).put(key(sampler), e);
                loaded.add(name + "=" + value);
            } catch (Exception ex) {
                MetalShadersClient.LOGGER.warn("Custom texture {} = {}: {}", name, value, ex.toString());
            }
        }
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith("customTexture.")) {
                continue;
            }
            // Iris ShaderProperties: the sampler name is everything after the prefix, uncut.
            String sampler = name.substring("customTexture.".length());
            String value = props.getProperty(name).strip();
            try {
                Entry e = value.contains(" ") ? loadRaw(device, pack, value) : value.contains(":") ? gameTexture(value) : loadPng(device, pack, value);
                if (e == null) {
                    MetalShadersClient.LOGGER.warn("Custom texture {} = {}: no such file in the pack, skipped", name, value);
                    continue;
                }
                (e.volume() != null ? volumesByName : byName).put(sampler, e);
                loaded.add(name + "=" + value);
            } catch (Exception ex) {
                MetalShadersClient.LOGGER.warn("Custom texture {} = {}: {}", name, value, ex.toString());
            }
        }
        if (!loaded.isEmpty()) {
            MetalShadersClient.LOGGER.info("Custom textures: {}", loaded);
        }
    }

    private static @Nullable Entry loadPng(GpuDevice device, Path pack, String value) throws Exception {
        String path = value.replaceFirst("^/", "");
        byte[] png = readPackFile(pack, path);
        if (png == null) {
            return null;
        }
        byte[] meta = readPackFile(pack, path + ".mcmeta");
        boolean blur = false;
        boolean clamp = false;
        if (meta != null) {
            JsonObject root = JsonParser.parseString(new String(meta, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject texture = root.has("texture") ? root.getAsJsonObject("texture") : null;
            if (texture != null) {
                blur = texture.has("blur") && texture.get("blur").getAsBoolean();
                clamp = texture.has("clamp") && texture.get("clamp").getAsBoolean();
            }
        }
        try (NativeImage image = NativeImage.read(png)) {
            GpuTexture t = device.createTexture("Luxium " + path, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA8_UNORM, image.getWidth(), image.getHeight(), 1, 1);
            device.createCommandEncoder().writeToTexture(t, image);
            GpuTextureView v = device.createTextureView(t);
            FilterMode filter = blur ? FilterMode.LINEAR : FilterMode.NEAREST;
            GpuSampler s = clamp ? RenderSystem.getSamplerCache().getClampToEdge(filter) : RenderSystem.getSamplerCache().getRepeat(filter);
            samplerOf.put(v, s);
            return new Entry(t, v, s);
        }
    }

    /**
     * A {@code namespace:path} value (Mellow: {@code minecraft:textures/environment/clouds.png}): the
     * game's own texture of that name, with its own sampler, as Iris's CustomTextureManager binds
     * ResourceData (its PBR-suffix forms aren't handled). Looked up once, when the pack loads.
     */
    private static Entry gameTexture(String value) {
        net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.parse(value);
        net.minecraft.client.renderer.texture.AbstractTexture t = net.minecraft.client.Minecraft.getInstance().getTextureManager().getTexture(id);
        samplerOf.put(t.getTextureView(), t.getSampler());
        return new Entry(null, t.getTextureView(), t.getSampler(), null);
    }

    /**
     * Iris's raw form: {@code <file> TEXTURE_2D <internal> <w> <h> <pixel format> <pixel type>} or
     * {@code <file> TEXTURE_3D <internal> <w> <h> <d> <pixel format> <pixel type>} (ShaderProperties;
     * the 1D and rectangle forms aren't supported). As Iris (ShaderPack), a raw texture is linear and
     * clamped unless its {@code .mcmeta} says otherwise. A 2D one is a plain texture; a 3D one is
     * uploaded as a 2D atlas of bordered slices that VolumeTextures reads (the game has no 3D
     * textures). Data is read as the pixel format and type say and stored as RGBA8, R8 or a
     * float format with as many channels.
     */
    private static @Nullable Entry loadRaw(GpuDevice device, Path pack, String value) throws Exception {
        String[] parts = value.split("\\s+");
        boolean volume = parts.length == 8 && parts[1].equalsIgnoreCase("TEXTURE_3D");
        if (!volume && !(parts.length == 7 && parts[1].equalsIgnoreCase("TEXTURE_2D"))) {
            throw new IllegalArgumentException("only the TEXTURE_2D and TEXTURE_3D raw forms are supported");
        }
        String path = parts[0].replaceFirst("^/", "");
        byte[] data = readPackFile(pack, path);
        if (data == null) {
            return null;
        }
        boolean[] filtering = mcmeta(pack, path, true, true);
        boolean blur = filtering[0];
        boolean clamp = filtering[1];
        int w = Integer.parseInt(parts[3]);
        int h = Integer.parseInt(parts[4]);
        int d = volume ? Integer.parseInt(parts[5]) : 1;
        String pixelFormat = parts[volume ? 6 : 5].toUpperCase(java.util.Locale.ROOT);
        String pixelType = parts[volume ? 7 : 6].toUpperCase(java.util.Locale.ROOT);
        String internal = parts[2].toUpperCase(java.util.Locale.ROOT);

        int inChannels = switch (pixelFormat.replace("_INTEGER", "")) {
            case "RED", "R" -> 1;
            case "RG" -> 2;
            case "RGB", "BGR" -> 3;
            case "RGBA", "BGRA" -> 4;
            default -> throw new IllegalArgumentException("pixel format " + pixelFormat + " isn't supported");
        };
        boolean bgr = pixelFormat.startsWith("BGR");
        int inBytes = switch (pixelType) {
            case "UNSIGNED_BYTE", "BYTE" -> 1;
            case "UNSIGNED_SHORT", "SHORT", "HALF_FLOAT" -> 2;
            case "FLOAT", "UNSIGNED_INT", "INT" -> 4;
            default -> throw new IllegalArgumentException("pixel type " + pixelType + " isn't supported");
        };
        long need = (long) w * h * d * inChannels * inBytes;
        if (data.length < need) {
            throw new IllegalArgumentException("the file has " + data.length + " bytes, the size needs " + need);
        }
        // Stored format: as many channels as the internal format (RGB widened to RGBA), 8-bit
        // normalized unless the internal format is a float one.
        int outChannels = internal.startsWith("RGBA") || internal.startsWith("RGB") ? 4 : internal.startsWith("RG") ? 2 : 1;
        int outBytes = internal.contains("32F") ? 4 : internal.contains("16F") ? 2 : 1;
        GpuFormat format = switch (outBytes * 10 + outChannels) {
            case 11 -> GpuFormat.R8_UNORM;
            case 12 -> GpuFormat.RG8_UNORM;
            case 14 -> GpuFormat.RGBA8_UNORM;
            case 21 -> GpuFormat.R16_FLOAT;
            case 22 -> GpuFormat.RG16_FLOAT;
            case 24 -> GpuFormat.RGBA16_FLOAT;
            case 41 -> GpuFormat.R32_FLOAT;
            case 42 -> GpuFormat.RG32_FLOAT;
            default -> GpuFormat.RGBA32_FLOAT;
        };
        java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        VolumeTextures.Volume vol = volume ? new VolumeTextures.Volume(w, h, d, blur, !clamp) : null;
        int outW = vol != null ? vol.atlasWidth() : w;
        int outH = vol != null ? vol.atlasHeight() : h;
        java.nio.ByteBuffer out = org.lwjgl.system.MemoryUtil.memAlloc(outW * outH * outChannels * outBytes).order(java.nio.ByteOrder.nativeOrder());
        try {
            float[] texel = new float[4];
            for (int oy = 0; oy < outH; oy++) {
                for (int ox = 0; ox < outW; ox++) {
                    int sx;
                    int sy;
                    int sz;
                    if (vol != null) {
                        sz = ox / (w + 2);
                        sx = edge(ox % (w + 2) - 1, w, clamp);
                        sy = edge(oy - 1, h, clamp);
                    } else {
                        sx = ox;
                        sy = oy;
                        sz = 0;
                    }
                    int base = (((sz * h) + sy) * w + sx) * inChannels;
                    texel[0] = 0;
                    texel[1] = 0;
                    texel[2] = 0;
                    texel[3] = 1;
                    for (int c = 0; c < inChannels; c++) {
                        texel[bgr && c < 3 ? 2 - c : c] = read(in, (base + c) * inBytes, pixelType);
                    }
                    for (int c = 0; c < outChannels; c++) {
                        float v = texel[c];
                        switch (outBytes) {
                            case 1 -> out.put((byte) Math.round(Math.clamp(v, 0f, 1f) * 255f));
                            case 2 -> out.putShort(Float.floatToFloat16(v));
                            default -> out.putFloat(v);
                        }
                    }
                }
            }
            out.flip();
            GpuTexture t = device.createTexture("Luxium " + path, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    format, outW, outH, 1, 1);
            device.createCommandEncoder().writeToTexture(t, out, 0, 0, 0, 0, outW, outH);
            GpuTextureView v = device.createTextureView(t);
            FilterMode filter = blur ? FilterMode.LINEAR : FilterMode.NEAREST;
            // A volume's borders do the wrapping, so its atlas is always read clamped.
            GpuSampler s = clamp || vol != null ? RenderSystem.getSamplerCache().getClampToEdge(filter) : RenderSystem.getSamplerCache().getRepeat(filter);
            samplerOf.put(v, s);
            return new Entry(t, v, s, vol);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(out);
        }
    }

    /** A texel index outside 0..n-1, wrapped (repeat) or clamped. */
    private static int edge(int i, int n, boolean clamp) {
        return clamp ? Math.clamp(i, 0, n - 1) : Math.floorMod(i, n);
    }

    /** One component of a raw texture, normalized as OpenGL reads it for a float internal format. */
    private static float read(java.nio.ByteBuffer in, int offset, String pixelType) {
        return switch (pixelType) {
            case "UNSIGNED_BYTE" -> (in.get(offset) & 0xFF) / 255f;
            case "BYTE" -> Math.max(in.get(offset) / 127f, -1f);
            case "UNSIGNED_SHORT" -> (in.getShort(offset) & 0xFFFF) / 65535f;
            case "SHORT" -> Math.max(in.getShort(offset) / 32767f, -1f);
            case "HALF_FLOAT" -> Float.float16ToFloat(in.getShort(offset));
            case "UNSIGNED_INT" -> (float) ((in.getInt(offset) & 0xFFFFFFFFL) / 4294967295.0);
            case "INT" -> (float) Math.max(in.getInt(offset) / 2147483647.0, -1.0);
            default -> in.getFloat(offset);
        };
    }

    /** blur and clamp from {@code <path>.mcmeta}, else the defaults given. */
    private static boolean[] mcmeta(Path pack, String path, boolean blur, boolean clamp) throws java.io.IOException {
        byte[] meta = readPackFile(pack, path + ".mcmeta");
        if (meta != null) {
            JsonObject root = JsonParser.parseString(new String(meta, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject texture = root.has("texture") ? root.getAsJsonObject("texture") : null;
            if (texture != null) {
                blur = texture.has("blur") ? texture.get("blur").getAsBoolean() : blur;
                clamp = texture.has("clamp") ? texture.get("clamp").getAsBoolean() : clamp;
            }
        }
        return new boolean[] {blur, clamp};
    }

    /**
     * The volume a sampler reads in a stage's programs (a raw TEXTURE_3D directive), or null. As
     * {@link #override}, by any of the sampler's names.
     */
    public static VolumeTextures.@Nullable Volume volume(Stage stage, String samplerName) {
        Entry e = volumeEntry(stage, samplerName);
        return e != null ? e.volume() : null;
    }

    /**
     * The atlas a rewritten volume sampler ({@code luxium_vol_<name>}, VolumeTextures) reads in a
     * stage, or null if {@code samplerName} isn't one. Always the volume: as Iris binds a raw
     * texture to its own customtex sampler, no render target takes its place.
     */
    static @Nullable GpuTextureView volumeView(Stage stage, String samplerName) {
        if (!samplerName.startsWith(VolumeTextures.PREFIX)) {
            return null;
        }
        Entry e = volumeEntry(stage, samplerName.substring(VolumeTextures.PREFIX.length()));
        return e != null ? e.view() : null;
    }

    private static @Nullable Entry volumeEntry(Stage stage, String samplerName) {
        Map<String, Entry> m = volumesByStage.get(stage);
        Entry e = m != null ? m.get(key(samplerName)) : null;
        return e != null ? e : volumesByName.get(samplerName);
    }

    /** A file under the pack's shaders/ folder (zip or directory), or null. */
    static byte @Nullable [] readPackFile(Path pack, String path) throws java.io.IOException {
        return PackReader.open(pack, (name, root, shaders) -> {
            Path file = shaders.resolve(path).normalize();
            return file.startsWith(shaders) && Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        });
    }

    /**
     * The override for a sampler in a stage, or null. {@code writtenBefore}: the render targets
     * earlier passes of the same stage wrote this frame (empty for gbuffers).
     */
    static @Nullable GpuTextureView override(Stage stage, String samplerName, Set<Integer> writtenBefore) {
        Map<String, Entry> m = byStage.get(stage);
        if (m == null) {
            return null;
        }
        Entry e = m.get(key(samplerName));
        if (e == null) {
            return null;
        }
        Integer index = PackChain.targetIndex(samplerName);
        return index != null && writtenBefore.contains(index) ? null : e.view();
    }

    /** The {@code customTexture.<name>} image a sampler of that exact name reads, or null. */
    static @Nullable GpuTextureView byName(String samplerName) {
        Entry e = byName.get(samplerName);
        return e != null ? e.view() : null;
    }

    /** Registers the sampler a non-custom image is read with (noisetex: Iris NativeImageBackedNoiseTexture, linear and repeat). */
    static void setSampler(GpuTextureView view, GpuSampler sampler) {
        samplerOf.put(view, sampler);
    }

    /**
     * The sampler to read a bound texture with: the image's own (custom textures, noisetex),
     * nearest for depth and shadow maps, else linear and clamped (render targets, as Iris).
     */
    public static GpuSampler samplerFor(String samplerName, GpuTextureView view) {
        GpuSampler own = samplerOf.get(view);
        if (own != null) {
            return own;
        }
        boolean depthLike = samplerName.startsWith("depthtex") || samplerName.startsWith("shadowtex");
        return RenderSystem.getSamplerCache().getClampToEdge(depthLike ? FilterMode.NEAREST : FilterMode.LINEAR);
    }

    /** Metal's sampler slots per stage ([[sampler(0..15)]]); see MetalSamplerSlotsMixin. */
    public static final int SAMPLER_SLOTS = 16;

    /**
     * {linear, repeat} for a texture whose sampler has to be built into the shader (slot 16 and
     * up, MetalSamplerSlotsMixin), by its name: what {@link #samplerFor} binds for that name. A
     * pack image under that name in any stage gives its own filtering; noisetex is linear and
     * repeated (Iris NativeImageBackedNoiseTexture); depth and shadow maps nearest; render
     * targets and everything else linear, clamped.
     */
    public static boolean[] builtInSampler(String samplerName) {
        if (samplerName.startsWith(VolumeTextures.PREFIX)) {
            Entry v = null;
            for (Stage stage : Stage.values()) {
                if (v == null) {
                    v = volumeEntry(stage, samplerName.substring(VolumeTextures.PREFIX.length()));
                }
            }
            return new boolean[] {v == null || v.volume().linear(), false};   // borders do the wrapping
        }
        Entry e = byName.get(samplerName);
        for (Map<String, Entry> m : byStage.values()) {
            if (e == null) {
                e = m.get(key(samplerName));
            }
        }
        if (e != null) {
            boolean linear = e.sampler() == RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
                    || e.sampler() == RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
            boolean repeat = e.sampler() == RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR)
                    || e.sampler() == RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST);
            return new boolean[] {linear, repeat};
        }
        if (samplerName.equals("noisetex")) {
            return new boolean[] {true, true};
        }
        boolean depthLike = samplerName.startsWith("depthtex") || samplerName.startsWith("shadowtex");
        return new boolean[] {!depthLike, false};
    }

    /** Frees the loaded images (a new world folder is loading; see PackChain.unload). */
    static void unload() {
        for (Map<String, Entry> m : byStage.values()) {
            for (Entry e : m.values()) {
                e.close();
            }
        }
        byStage.clear();
        for (Entry e : byName.values()) {
            e.close();
        }
        byName.clear();
        for (Map<String, Entry> m : volumesByStage.values()) {
            for (Entry e : m.values()) {
                e.close();
            }
        }
        volumesByStage.clear();
        for (Entry e : volumesByName.values()) {
            e.close();
        }
        volumesByName.clear();
        samplerOf.clear();   // noisetex's too: PackChain registers it again with the next world's
    }
}
