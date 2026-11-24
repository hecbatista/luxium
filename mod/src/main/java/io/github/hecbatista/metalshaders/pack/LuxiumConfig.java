package io.github.hecbatista.metalshaders.pack;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * {@code config/luxium.properties}: whether shaders are on and which pack is used. The key names
 * are Iris's ({@code config/iris.properties}: {@code enableShaders}, {@code shaderPack}), so they
 * look familiar. Read whenever Luxium (re)loads the pack: at startup, at world load, and when the
 * Shader Packs screen applies a change ({@link #write}, then MetalShadersClient.requestReload).
 * Edits made by hand while the game runs apply at the next reload or start.
 * <p>
 * As Iris: shaders are on unless {@code enableShaders=false}; a named pack that isn't in
 * shaderpacks/ means no pack (vanilla rendering), never another pack in its place. With no name,
 * the first pack by file name is used and written into the file, so adding packs later doesn't
 * change the choice. The file is (re)written with the values in use and a list of the packs
 * found, so players can copy an exact name.
 */
public final class LuxiumConfig {
    public static final String ENABLE = "enableShaders";
    public static final String PACK = "shaderPack";

    /** The pack to use ({@code null}: none) and why, for the log. */
    public record Choice(@Nullable Path pack, boolean enabled, String reason) {
    }

    private LuxiumConfig() {
    }

    /** Whether shaders are on in the file (on when the file or the key is missing, as Iris). */
    public static boolean enabled(Path configFile) throws IOException {
        return isOn(read(configFile));
    }

    /** The {@code shaderPack} value in the file ("" when missing: the first pack found). */
    public static String packName(Path configFile) throws IOException {
        return read(configFile).getProperty(PACK, "").strip();
    }

    /** Resolves the pack and rewrites the file when what it should say changed. */
    public static Choice choose(Path shaderpacks, Path configFile) throws IOException {
        Properties props = read(configFile);
        boolean enabled = isOn(props);
        String name = props.getProperty(PACK, "").strip();
        List<Path> packs = PackReader.findPacks(shaderpacks);
        String found = packs.isEmpty() ? "(none)"
                : packs.stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(", "));
        Choice choice;
        if (!enabled) {
            choice = new Choice(null, false, ENABLE + "=false in " + configFile.getFileName() + ": shaders are off");
        } else if (name.isEmpty()) {
            if (packs.isEmpty()) {
                choice = new Choice(null, true, "no shader pack in " + shaderpacks);
            } else {
                Path first = packs.getFirst();
                name = first.getFileName().toString();
                choice = new Choice(first, true, "the first pack found (" + name + "; " + PACK + " was empty)");
            }
        } else {
            String wanted = name;
            Path match = packs.stream().filter(p -> p.getFileName().toString().equals(wanted)).findFirst().orElse(null);
            choice = match != null
                    ? new Choice(match, true, PACK + "=" + name)
                    : new Choice(null, true, PACK + "=" + name + " isn't in " + shaderpacks + " (found: " + found + "): no pack is used");
        }
        String text = render(enabled, name, found);
        if (!Files.exists(configFile) || !Files.readString(configFile, StandardCharsets.UTF_8).equals(text)) {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, text, StandardCharsets.UTF_8);
        }
        return choice;
    }

    /** Writes the file from "k=v;k2=v2" (the test switch LUXIUM_TEST_CONFIG), replacing it. */
    public static void writeOverride(Path configFile, String pairs) throws IOException {
        Properties props = new Properties();
        for (String pair : pairs.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                props.setProperty(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip());
            }
        }
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, render(isOn(props), props.getProperty(PACK, "").strip(), "(not listed yet)"),
                StandardCharsets.UTF_8);
    }

    /**
     * Writes the player's choice (from the Shader Packs screen). An empty {@code pack} means the
     * first pack found, as for a missing key.
     */
    public static void write(Path shaderpacks, Path configFile, boolean enabled, String pack) throws IOException {
        List<Path> packs = PackReader.findPacks(shaderpacks);
        String found = packs.isEmpty() ? "(none)"
                : packs.stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(", "));
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, render(enabled, pack.strip(), found), StandardCharsets.UTF_8);
    }

    static String render(boolean enabled, String pack, String found) {
        return "# Luxium settings. The Shader Packs screen (Video Settings, or the I key) changes them\n"
                + "# while playing; edits made here by hand apply the next time the game starts.\n"
                + "# Shaders on (true) or off (false). Off: the game renders as without Luxium.\n"
                + ENABLE + "=" + enabled + "\n"
                + "# The pack to use: a file or folder name in shaderpacks/, exactly as listed below.\n"
                + "# Empty: the first pack found. A name that isn't there: no pack (vanilla rendering).\n"
                + PACK + "=" + pack + "\n"
                + "# Packs found: " + found + "\n";
    }

    private static boolean isOn(Properties props) {
        return !props.getProperty(ENABLE, "true").strip().equalsIgnoreCase("false");
    }

    private static Properties read(Path configFile) throws IOException {
        Properties props = new Properties();
        if (Files.isRegularFile(configFile)) {
            props.load(new StringReader(Files.readString(configFile, StandardCharsets.UTF_8)));
        }
        return props;
    }
}
