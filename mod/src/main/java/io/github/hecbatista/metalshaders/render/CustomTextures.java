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
 * Iris's raw-texture form ({@code texture.<stage>.<name>=<file> TEXTURE_2D ...}) and
 * {@code minecraft:} resource paths aren't supported yet, for either directive; they are logged
 * and skipped.
 */
public final class CustomTextures {
    public enum Stage { SETUP, BEGIN, SHADOWCOMP, PREPARE, GBUFFERS, DEFERRED, COMPOSITE }

    private record Entry(GpuTexture texture, GpuTextureView view, GpuSampler sampler) {
    }

    /** Per stage: sampler key (see {@link #key}) to the image. */
    private static final Map<Stage, Map<String, Entry>> byStage = new EnumMap<>(Stage.class);
    /**
     * {@code customTexture.<sampler>=<png>}: sampler name to the image, in every program of every
     * stage (Iris CustomTextureManager's irisCustomTextures, bound by IrisSamplers.addCustomTextures
     * after the render targets, so a render-target name keeps its target).
     */
    private static final Map<String, Entry> byName = new HashMap<>();
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
            if (value.contains(" ")) {
                MetalShadersClient.LOGGER.warn("Custom texture {} = {}: raw textures aren't supported yet, skipped", name, value);
                continue;
            }
            if (value.contains(":")) {
                MetalShadersClient.LOGGER.warn("Custom texture {} = {}: resource-pack textures aren't supported yet, skipped", name, value);
                continue;
            }
            try {
                Entry e = loadPng(device, pack, value);
                if (e == null) {
                    MetalShadersClient.LOGGER.warn("Custom texture {} = {}: no such file in the pack, skipped", name, value);
                    continue;
                }
                byStage.computeIfAbsent(stage, s -> new HashMap<>()).put(key(sampler), e);
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
            if (value.contains(" ")) {
                MetalShadersClient.LOGGER.warn("Custom texture {} = {}: raw textures aren't supported yet, skipped", name, value);
                continue;
            }
            if (value.contains(":")) {
                MetalShadersClient.LOGGER.warn("Custom texture {} = {}: resource-pack textures aren't supported yet, skipped", name, value);
                continue;
            }
            try {
                Entry e = loadPng(device, pack, value);
                if (e == null) {
                    MetalShadersClient.LOGGER.warn("Custom texture {} = {}: no such file in the pack, skipped", name, value);
                    continue;
                }
                byName.put(sampler, e);
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

    /** Frees the loaded images (a new world folder is loading; see PackChain.unload). */
    static void unload() {
        for (Map<String, Entry> m : byStage.values()) {
            for (Entry e : m.values()) {
                e.view().close();
                e.texture().close();
            }
        }
        byStage.clear();
        for (Entry e : byName.values()) {
            e.view().close();
            e.texture().close();
        }
        byName.clear();
        samplerOf.clear();   // noisetex's too: PackChain registers it again with the next world's
    }
}
