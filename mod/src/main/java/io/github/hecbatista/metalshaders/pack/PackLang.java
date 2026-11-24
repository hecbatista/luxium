package io.github.hecbatista.metalshaders.pack;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * A pack's own names and comments for its menus: {@code shaders/lang/<language>.lang} (UTF-8, in
 * Properties format), the player's language first, then {@code en_us}, as Iris loads them
 * (ShaderPack's language map). File names are matched without regard to case, since packs ship
 * {@code en_US.lang}. Keys: {@code option.X}, {@code option.X.comment}, {@code value.X.v},
 * {@code prefix.X}, {@code suffix.X}, {@code screen.X}, {@code screen.X.comment},
 * {@code profile.X}, {@code profile.comment}.
 */
public final class PackLang {
    public static final PackLang EMPTY = new PackLang(Map.of());

    private final Map<String, String> entries;

    private PackLang(Map<String, String> entries) {
        this.entries = entries;
    }

    /** From the texts of the player's language file and of en_us (either may be null). */
    public static PackLang of(@Nullable String playerLanguage, @Nullable String english) {
        Map<String, String> out = new HashMap<>();
        load(english, out);
        load(playerLanguage, out);   // the player's language wins
        return new PackLang(out);
    }

    private static void load(@Nullable String text, Map<String, String> out) {
        if (text == null) {
            return;
        }
        Properties p = new Properties();
        try {
            p.load(new StringReader(text));
        } catch (IOException | IllegalArgumentException e) {
            return;
        }
        p.stringPropertyNames().forEach(k -> out.put(k, p.getProperty(k)));
    }

    /** Reads {@code shaders/lang/} of a pack (folder or zip). */
    public static PackLang load(Path pack, String languageCode) throws IOException {
        return PackReader.open(pack, (name, root, shaders) -> {
            Path dir = shaders.resolve("lang");
            if (!Files.isDirectory(dir)) {
                return EMPTY;
            }
            String player = null;
            String english = null;
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (fileName.equals(languageCode.toLowerCase(Locale.ROOT) + ".lang")) {
                        player = readLenient(file);
                    }
                    if (fileName.equals("en_us.lang")) {
                        english = readLenient(file);
                    }
                }
            }
            return of(player, english);
        });
    }

    /**
     * UTF-8, with bad bytes replaced rather than failing (Iris reads them through an
     * InputStreamReader, which does the same), so one stray Latin-1 byte can't hide the menus.
     */
    private static @Nullable String readLenient(Path file) {
        try {
            return new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    public String getOrDefault(String key, String fallback) {
        return entries.getOrDefault(key, fallback);
    }

    public int size() {
        return entries.size();
    }
}
