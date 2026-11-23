package io.github.hecbatista.metalshaders.render;

import io.github.hecbatista.metalshaders.MetalShadersClient;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Step 7f: measurements, on with {@code LUXIUM_PERF=1} (otherwise every call is one boolean test).
 * <p>
 * Frame time is the time between two frames' {@code GameRenderer.extract}, so it includes the
 * GPU when the frame waits for it. Section times are Luxium's CPU time recording its GPU work
 * (encoding draws, mapping uniform buffers, compiling), not GPU time: Metal's timestamps aren't
 * reachable through the game's API.
 */
public final class PerfStats {
    public static final boolean ON = System.getenv("LUXIUM_PERF") != null;

    public enum Section {
        VALUES("game values"), SHADOW("shadow pass"), TERRAIN("opaque terrain"), DEFERRED("deferred"),
        TRANSLUCENT("translucent terrain"), COMPOSITES("composites+final"), COMPILE("first-use compiles");

        final String label;

        Section(String label) {
            this.label = label;
        }
    }

    private static final int MAX_FRAMES = 4096;
    private static final long[] frames = new long[MAX_FRAMES];
    private static int frameCount;
    private static long lastFrame;
    private static final Map<Section, long[]> sections = new EnumMap<>(Section.class);   // {nanos, calls}

    private PerfStats() {
    }

    /** A start time, or 0 when measuring is off. */
    public static long start() {
        return ON ? System.nanoTime() : 0;
    }

    public static void end(Section section, long start) {
        if (!ON || start == 0) {
            return;
        }
        long[] s = sections.computeIfAbsent(section, k -> new long[2]);
        s[0] += System.nanoTime() - start;
        s[1]++;
    }

    /** Once per frame (GameRenderer.extract). */
    public static void frame() {
        if (!ON) {
            return;
        }
        long now = System.nanoTime();
        if (lastFrame != 0 && frameCount < MAX_FRAMES) {
            frames[frameCount++] = now - lastFrame;
        }
        lastFrame = now;
    }

    /** Starts a measuring window. */
    public static void reset() {
        frameCount = 0;
        lastFrame = 0;
        sections.clear();
    }

    /** Logs the window since {@link #reset} and returns the summary line. */
    public static String report(String label) {
        if (!ON || frameCount == 0) {
            return "";
        }
        long[] sorted = Arrays.copyOf(frames, frameCount);
        Arrays.sort(sorted);
        double total = 0;
        int hitches = 0;
        for (long f : sorted) {
            total += f;
            if (f > 50_000_000L) {
                hitches++;
            }
        }
        double avg = total / frameCount / 1e6;
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "Perf [%s]: %d frames, avg %.2f ms (%.0f fps), p50 %.2f, p95 %.2f, p99 %.2f, max %.2f ms, %d over 50 ms. Luxium CPU per frame:",
                label, frameCount, avg, 1000.0 / avg, ms(sorted, 0.50), ms(sorted, 0.95), ms(sorted, 0.99),
                sorted[frameCount - 1] / 1e6, hitches));
        for (Section s : Section.values()) {
            long[] v = sections.get(s);
            if (v != null) {
                sb.append(String.format(Locale.ROOT, " %s %.3f ms", s.label, v[0] / 1e6 / frameCount));
                if (s == Section.COMPILE) {
                    sb.append(String.format(Locale.ROOT, " (%d compiles, %.1f ms in all)", v[1], v[0] / 1e6));
                }
                sb.append(';');
            }
        }
        String line = sb.toString();
        MetalShadersClient.LOGGER.info(line);
        return line;
    }

    private static double ms(long[] sorted, double q) {
        return sorted[Math.min(sorted.length - 1, (int) (q * sorted.length))] / 1e6;
    }
}
