package io.github.hecbatista.metalshaders.uniform;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pack's own values, defined in shaders.properties as formulas:
 * {@code uniform.float.rainFactor = smooth(1, rainStrength, 3, 3)} (sent to the shaders) or
 * {@code variable.float.difX = ...} (a helper only other formulas read).
 * <p>
 * Rules follow Iris's CustomUniforms (see NOTICE): formulas are ordered by what they read, a
 * cycle is refused naming its members, a formula that can't be resolved is dropped with a
 * reason and so is everything that reads it, and a name that would shadow a game value is
 * refused. Iris reads these lines from shaders.properties after preprocessing it.
 */
public final class CustomUniforms {
    private static final Pattern KEY = Pattern.compile("^(uniform|variable)\\.(float|int|bool)\\.([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern ANY_KEY = Pattern.compile("^(uniform|variable)\\.([A-Za-z0-9]+)\\.(.+)$");

    private record Entry(String name, String type, boolean uniform, Formula formula) {
    }

    /** In evaluation order. */
    private final List<Entry> entries;
    /** Names dropped while loading, with why. */
    private final Map<String, String> dropped;
    private final Map<String, Object> values = new HashMap<>();

    private CustomUniforms(List<Entry> entries, Map<String, String> dropped) {
        this.entries = entries;
        this.dropped = dropped;
    }

    /**
     * Reads the formulas out of an already preprocessed shaders.properties.
     *
     * @param gameValues names the game supplies (a formula may read them; one may not be redefined)
     */
    public static CustomUniforms load(String properties, Set<String> gameValues) throws IOException {
        Properties p = new Properties();
        p.load(new StringReader(properties));
        Map<String, String> dropped = new TreeMap<>();
        Map<String, Entry> parsed = new LinkedHashMap<>();
        for (String key : p.stringPropertyNames().stream().sorted().toList()) {
            Matcher any = ANY_KEY.matcher(key);
            if (!any.matches()) {
                continue;
            }
            Matcher m = KEY.matcher(key);
            if (!m.matches()) {
                dropped.put(any.group(3), "type '" + any.group(2) + "' isn't supported yet");
                continue;
            }
            String name = m.group(3);
            if (gameValues.contains(name)) {
                dropped.put(name, "would hide the game value of the same name");
                continue;
            }
            try {
                parsed.put(name, new Entry(name, m.group(2), m.group(1).equals("uniform"), Formula.parse(p.getProperty(key).strip())));
            } catch (Formula.FormulaException e) {
                dropped.put(name, e.getMessage());
            }
        }

        // Order by dependencies (depth-first), refusing cycles and anything that reads a dropped name.
        List<Entry> ordered = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (String name : parsed.keySet()) {
            visit(name, parsed, gameValues, dropped, done, new ArrayList<>(), ordered);
        }
        return new CustomUniforms(List.copyOf(ordered), dropped);
    }

    private static boolean visit(String name, Map<String, Entry> parsed, Set<String> gameValues, Map<String, String> dropped,
                                 Set<String> done, List<String> path, List<Entry> ordered) {
        if (done.contains(name)) {
            return !dropped.containsKey(name);
        }
        if (path.contains(name)) {
            List<String> cycle = new ArrayList<>(path.subList(path.indexOf(name), path.size()));
            cycle.add(name);
            for (String member : cycle) {
                dropped.putIfAbsent(member, "part of a cycle: " + String.join(" -> ", cycle));
            }
            return false;
        }
        Entry e = parsed.get(name);
        path.add(name);
        boolean ok = true;
        for (String dep : e.formula().names()) {
            if (parsed.containsKey(dep)) {
                if (!visit(dep, parsed, gameValues, dropped, done, path, ordered)) {
                    dropped.putIfAbsent(name, "reads '" + dep + "', which was dropped");
                    ok = false;
                }
            } else if (!gameValues.contains(dep)) {
                dropped.putIfAbsent(name, "reads unknown name '" + dep + "'");
                ok = false;
            }
        }
        path.removeLast();
        done.add(name);
        if (ok && !dropped.containsKey(name)) {
            ordered.add(e);
            return true;
        }
        return false;
    }

    /** Runs every formula once. Call once per frame, after the game values are updated. */
    public void update(Formula.Names gameValues, float frameTime) {
        Formula.Names names = n -> {
            Object own = values.get(n);
            return own != null ? own : gameValues.get(n);
        };
        for (Entry e : entries) {
            try {
                values.put(e.name(), convert(e.formula().evaluate(names, frameTime), e.type()));
            } catch (Formula.FormulaException ex) {
                values.remove(e.name());
                dropped.putIfAbsent(e.name(), "failed while running: " + ex.getMessage());
            }
        }
    }

    private static Object convert(Object v, String type) throws Formula.FormulaException {
        float f = v instanceof Integer i ? i : v instanceof Float x ? x : v instanceof Boolean b ? (b ? 1f : 0f) : Float.NaN;
        if (Float.isNaN(f) && !(v instanceof Float)) {
            throw new Formula.FormulaException("result is a vector, not a " + type);
        }
        return switch (type) {
            case "float" -> f;
            case "int" -> v instanceof Integer i ? i : (int) f;
            case "bool" -> v instanceof Boolean b ? b : f != 0f;
            default -> throw new Formula.FormulaException("unsupported type " + type);
        };
    }

    /** A custom uniform's value this frame (variables aren't sent to shaders), or null. */
    public Object get(String name) {
        for (Entry e : entries) {
            if (e.name().equals(name)) {
                return e.uniform() ? values.get(name) : null;
            }
        }
        return null;
    }

    public int size() {
        return entries.size();
    }

    public Map<String, String> dropped() {
        return Map.copyOf(dropped);
    }
}
