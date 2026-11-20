package io.github.hecbatista.metalshaders.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The default value of each of the pack's settings, so .properties files can test them with
 * {@code #if} the way Iris lets them (Iris's PropertiesPreprocessor defines every option).
 * <p>
 * A simple first version of Iris's option discovery: a setting is a {@code #define NAME VALUE}
 * followed by a comment listing allowed values ({@code // [a b c]}), or a {@code #define NAME}
 * that is on (a boolean switch). The first definition found wins, scanning files in name order.
 * Iris's full rules (which defines count as options, conflicts between files, user-changed
 * values) come with the settings step.
 */
public final class OptionDefaults {
    private static final Pattern VALUE = Pattern.compile("^\\s*#define\\s+([A-Za-z_]\\w*)\\s+([^\\s/]+)\\s*//\\s*\\[", Pattern.MULTILINE);
    private static final Pattern SWITCH = Pattern.compile("^\\s*#define\\s+([A-Za-z_]\\w*)\\s*(?://.*)?$", Pattern.MULTILINE);

    private OptionDefaults() {
    }

    /** Name -> default value ("" for a switch that is on), from every shader file of the pack. */
    public static Map<String, String> read(Path shaders) throws IOException {
        Map<String, String> found = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(shaders, 16)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().matches(".*\\.(glsl|vsh|fsh|gsh|csh|inc)$"))
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                String text = Files.readString(file);
                Matcher v = VALUE.matcher(text);
                while (v.find()) {
                    found.putIfAbsent(v.group(1), v.group(2));
                }
                Matcher s = SWITCH.matcher(text);
                while (s.find()) {
                    found.putIfAbsent(s.group(1), "");
                }
            }
        }
        return found;
    }
}
