package io.github.hecbatista.metalshaders.gui;

import io.github.hecbatista.metalshaders.MetalShadersClient;
import io.github.hecbatista.metalshaders.pack.PackLang;
import io.github.hecbatista.metalshaders.pack.PackMenus;
import io.github.hecbatista.metalshaders.pack.PackOptions;
import io.github.hecbatista.metalshaders.pack.PackReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One pack's settings for the option screens: its options and applied values (from its settings
 * file), its menus and profiles, its lang, and the values picked but not applied yet. The pending
 * values are kept here between the option screens and the pack list, as Iris keeps its option
 * queue, until Apply or Cancel.
 */
public final class PackSettings {
    private static final Map<String, String> PENDING = new LinkedHashMap<>();
    private static boolean resetAll;
    private static String pendingPack;
    /** The settings the pending values were picked in (for applying them from the pack list). */
    private static PackSettings pendingOwner;

    public final Path pack;
    public final PackOptions options;
    public final PackMenus menus;
    public final PackLang lang;

    private PackSettings(Path pack, PackOptions options, PackMenus menus, PackLang lang) {
        this.pack = pack;
        this.options = options;
        this.menus = menus;
        this.lang = lang;
    }

    public static PackSettings load(Path pack, String languageCode) throws IOException {
        PackOptions options = PackOptions.load(pack);
        String properties = PackReader.open(pack, (n, r, shaders) -> PackReader.readText(shaders, "/shaders.properties").orElse(""));
        PackMenus menus = PackMenus.parse(properties, options.booleanOptions().keySet(), options.stringOptions().keySet());
        if (!menus.warnings().isEmpty()) {
            MetalShadersClient.LOGGER.warn("Shader pack menus of {}: {}", pack.getFileName(), menus.warnings());
        }
        String name = pack.getFileName().toString();
        if (!name.equals(pendingPack)) {   // pending values belong to one pack
            clearPending();
            pendingPack = name;
        }
        PackSettings loaded = new PackSettings(pack, options, menus, PackLang.load(pack, languageCode));
        pendingOwner = loaded;
        return loaded;
    }

    // ---- values ---------------------------------------------------------------------------

    /** The value in use by the pack now. */
    public String applied(String name) {
        String v = options.value(name);
        return v == null ? "" : v;
    }

    /** The value shown: pending if picked, else the default after a pending reset, else applied. */
    public String current(String name) {
        String pending = PENDING.get(name);
        if (pending != null) {
            return pending;
        }
        return resetAll ? options.defaultValue(name) : applied(name);
    }

    public boolean modified(String name) {
        return !current(name).equals(applied(name));
    }

    public void set(String name, String value) {
        PENDING.put(name, value);
    }

    public static boolean hasPending() {
        return !PENDING.isEmpty() || resetAll;
    }

    /**
     * Applies values picked in the option screens, if any (from the pack list's Apply and Done,
     * as Iris reloads when its option queue isn't empty).
     */
    public static void applyPending() throws IOException {
        if (pendingOwner != null && hasPending()) {
            pendingOwner.apply(false);
        }
    }

    public static void clearPending() {
        PENDING.clear();
        resetAll = false;
    }

    public PackMenus.ProfileScan profile() {
        return menus.scan(this::current, options::has);
    }

    public void applyProfile(PackMenus.Profile p) {
        p.values().forEach((k, v) -> {
            if (options.has(k)) {
                set(k, v);
            }
        });
    }

    /**
     * Writes the settings file (only values that differ from the defaults) and reloads the pack.
     * With {@code reset}, every option goes back to its default (the file is deleted).
     */
    public void apply(boolean reset) throws IOException {
        Map<String, String> values = new HashMap<>();
        if (!(reset || resetAll)) {
            values.putAll(options.changed());
        }
        values.putAll(PENDING);
        if (reset) {
            values.clear();
        }
        Map<String, String> written = options.writeSettings(pack, values);
        MetalShadersClient.LOGGER.info("Shader pack settings for {}: {} values differ from the defaults: {}",
                pack.getFileName(), written.size(), written);
        clearPending();
        MetalShadersClient.requestReload();
    }

    // ---- labels ---------------------------------------------------------------------------

    public String optionLabel(String name) {
        return lang.getOrDefault("option." + name, name);
    }

    /** prefix + (value.NAME.v, or v + suffix), as Iris's StringElementWidget. */
    public String valueLabel(String name, String value) {
        String prefix = lang.getOrDefault("prefix." + name, "");
        String suffix = lang.getOrDefault("suffix." + name, "");
        return prefix + lang.get("value." + name + "." + value).orElse(value + suffix);
    }

    public String screenLabel(String id) {
        return lang.getOrDefault("screen." + id, id);
    }

    public String profileLabel(String name) {
        return lang.getOrDefault("profile." + name, name);
    }
}
