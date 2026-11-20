package io.github.hecbatista.metalshaders.pack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pack-wide settings written as {@code const} lines in shader code, like
 * {@code const float wetnessHalflife = 300.0;}.
 * <p>
 * Iris reads these from the fragment stage of every program except final, in this order:
 * shadowcomp, begin, prepare, the gbuffers (shadow included), deferred, composite
 * (ProgramSet.locateDirectives, ProgramSet.java:211-268). Order matters when two programs
 * disagree, because the later one wins.
 */
public final class PackDirectives {
    private static final Pattern WETNESS = Pattern.compile("const\\s+float\\s+(wetnessHalflife|drynessHalflife)\\s*=\\s*([0-9.]+)");

    private PackDirectives() {
    }

    /** Iris's reading order for a program name; final and unknown names aren't read (-1). */
    static int order(String program) {
        if (program.startsWith("shadowcomp")) {
            return 0;
        }
        if (program.startsWith("begin")) {
            return 1;
        }
        if (program.startsWith("prepare")) {
            return 2;
        }
        if (program.startsWith("gbuffers_") || program.equals("shadow") || program.startsWith("shadow_")) {
            return 3;
        }
        if (program.startsWith("deferred")) {
            return 4;
        }
        if (program.startsWith("composite")) {
            return 5;
        }
        return -1;
    }

    /** Every wetness/dryness half-life directive in one world folder, in Iris's reading order. */
    public static List<Map.Entry<String, Float>> wetnessHalfLives(Path pack, String folder, Map<String, String> defines)
            throws IOException {
        PackInfo info = PackReader.read(pack);
        List<String> fragments = new ArrayList<>(info.programFiles().getOrDefault(folder, List.of()).stream()
                .filter(f -> f.endsWith(".fsh"))
                .map(f -> f.substring(0, f.length() - 4))
                .filter(p -> order(p) >= 0)
                .toList());
        fragments.sort(Comparator.comparingInt(PackDirectives::order).thenComparing(Comparator.naturalOrder()));

        List<Map.Entry<String, Float>> found = new ArrayList<>();
        for (String program : fragments) {
            PackPreprocessor.Program p = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/" + program + ".fsh", defines);
            if (!p.ok()) {
                continue;
            }
            Matcher m = WETNESS.matcher(p.result().text());
            while (m.find()) {
                found.add(Map.entry(m.group(1), Float.parseFloat(m.group(2))));
            }
        }
        return found;
    }
}
