package io.github.hecbatista.metalshaders.pack;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

/**
 * A pack's option menus and profiles, from the {@code screen}, {@code screen.<NAME>},
 * {@code screen.columns}, {@code screen.<NAME>.columns}, {@code sliders} and {@code profile.<NAME>}
 * lines of its <em>unpreprocessed</em> shaders.properties, in file order, as Iris reads them
 * (ShaderProperties, OptionMenuContainer, OptionMenuElementScreen, OptionMenuElement, ProfileSet,
 * Profile; see NOTICE):
 * <ul>
 *   <li>Elements, split on spaces: {@code <empty>}; {@code <profile>} (dropped when the pack has
 *   no profiles); {@code [NAME]}, a link to a sub-screen (a missing one opens the main screen);
 *   {@code *}, every option no screen names; an option name. An unknown name is a warning and an
 *   empty slot.</li>
 *   <li>No {@code screen} line means {@code *}. Columns: the screen's {@code .columns}, else 3 when
 *   it has more than 18 elements, else 2.</li>
 *   <li>Profile entries: {@code !program.X} (a switched-off program), {@code profile.X} (included;
 *   a cycle is an error), {@code !OPT} (off), {@code OPT=value} or {@code OPT:value}, or a bare
 *   on/off option name (on).</li>
 *   <li>The current profile is the first, in order of most entries first (ties in file order),
 *   whose options all have the current values; options the pack doesn't have are not compared.
 *   Cycling goes through that order and wraps; with no match, next is the first and previous the
 *   last.</li>
 * </ul>
 * Changed from Iris: a {@code *} on several screens fills the first in file order (Iris's order
 * there is a hash map's); a profile included twice by siblings isn't a cycle (Iris shares one
 * list of parents among siblings).
 */
public final class PackMenus {
    /** One slot on a screen. */
    public sealed interface Element permits Empty, ProfileSlot, Link, OptionSlot {
    }

    public record Empty() implements Element {
    }

    public record ProfileSlot() implements Element {
    }

    public record Link(String target) implements Element {
    }

    /** An option; {@code slider} for value options named in {@code sliders}. */
    public record OptionSlot(String name, boolean slider) implements Element {
    }

    /** A screen: {@code id} is null for the main screen. */
    public record Screen(@Nullable String id, List<Element> elements, int columns) {
    }

    public record Profile(String name, Map<String, String> values, List<String> disabledPrograms) {
        /** Iris's precedence: the number of option entries. */
        public int precedence() {
            return values.size();
        }
    }

    /** The current profile (empty: "Custom"), and the ones before and after it in cycling order. */
    public record ProfileScan(Optional<Profile> current, @Nullable Profile next, @Nullable Profile previous) {
    }

    private final Screen main;
    private final Map<String, Screen> subScreens;
    private final List<Profile> profiles;          // file order
    private final List<Profile> byPrecedence;      // most entries first, ties in file order
    private final Set<String> sliders;
    private final List<String> warnings;
    private final int screenLines;

    private PackMenus(Screen main, Map<String, Screen> subScreens, List<Profile> profiles, Set<String> sliders,
                      List<String> warnings, int screenLines) {
        this.main = main;
        this.subScreens = subScreens;
        this.profiles = profiles;
        List<Profile> sorted = new ArrayList<>(profiles);
        sorted.sort(Comparator.comparingInt(Profile::precedence).reversed());   // stable
        this.byPrecedence = sorted;
        this.sliders = sliders;
        this.warnings = warnings;
        this.screenLines = screenLines;
    }

    /** Properties that remember the order keys first appeared in (Iris's OrderBackedProperties). */
    private static final class OrderedProperties extends Properties {
        final Map<String, String> ordered = new LinkedHashMap<>();

        @Override
        public synchronized Object put(Object key, Object value) {
            ordered.put((String) key, (String) value);
            return super.put(key, value);
        }
    }

    /**
     * @param properties the pack's shaders.properties as written (not preprocessed)
     * @param booleanOptions the pack's on/off options, in the order found
     * @param valueOptions the pack's value options (const ones included), in the order found
     */
    public static PackMenus parse(String properties, Collection<String> booleanOptions, Collection<String> valueOptions) {
        OrderedProperties props = new OrderedProperties();
        try {
            props.load(new StringReader(properties));
        } catch (IOException | IllegalArgumentException e) {
            return new PackMenus(new Screen(null, List.of(), 2), Map.of(), List.of(), Set.of(),
                    List.of("shaders.properties couldn't be read: " + e), 0);
        }
        List<String> warnings = new ArrayList<>();
        Set<String> booleans = new LinkedHashSet<>(booleanOptions);
        Set<String> values = new LinkedHashSet<>(valueOptions);

        List<String> mainList = null;
        Integer mainColumns = null;
        Map<String, List<String>> subLists = new LinkedHashMap<>();
        Map<String, Integer> subColumns = new LinkedHashMap<>();
        Map<String, List<String>> profileLists = new LinkedHashMap<>();
        Set<String> sliders = new LinkedHashSet<>();
        int screenLines = 0;
        for (Map.Entry<String, String> e : props.ordered.entrySet()) {
            String key = e.getKey();
            String value = props.getProperty(key);   // the last definition wins, as in Properties
            if (key.equals("sliders")) {
                sliders = new LinkedHashSet<>(split(value));
            } else if (key.startsWith("profile.")) {
                profileLists.put(key.substring("profile.".length()), split(value));
            } else if (key.equals("screen.columns")) {
                mainColumns = parseInt(value, key, warnings);
            } else if (key.startsWith("screen.") && key.endsWith(".columns") && key.length() > "screen..columns".length()) {
                Integer columns = parseInt(value, key, warnings);
                if (columns != null) {
                    subColumns.put(key.substring("screen.".length(), key.length() - ".columns".length()), columns);
                }
            } else if (key.equals("screen")) {
                mainList = split(value);
                screenLines++;
            } else if (key.startsWith("screen.")) {
                subLists.put(key.substring("screen.".length()), split(value));
                screenLines++;
            }
        }

        // Profiles first: <profile> slots exist only when there are profiles.
        List<Profile> profiles = new ArrayList<>();
        for (String name : profileLists.keySet()) {
            try {
                profiles.add(parseProfile(name, new ArrayList<>(List.of(name)), profileLists, booleans, warnings));
            } catch (IllegalArgumentException ex) {
                warnings.add(ex.getMessage());
            }
        }

        Set<String> used = new LinkedHashSet<>();
        List<String> screenOrder = new ArrayList<>();
        Map<String, List<Element>> built = new LinkedHashMap<>();
        Map<String, Integer> starAt = new LinkedHashMap<>();
        screenOrder.add("");
        built.put("", build(mainList != null ? mainList : List.of("*"), "", booleans, values, sliders, profiles, used, starAt, warnings));
        for (Map.Entry<String, List<String>> e : subLists.entrySet()) {
            screenOrder.add(e.getKey());
            built.put(e.getKey(), build(e.getValue(), e.getKey(), booleans, values, sliders, profiles, used, starAt, warnings));
        }
        // "*": every option no screen names, into the first screen (file order) that has one.
        for (String id : screenOrder) {
            Integer at = starAt.get(id);
            if (at == null) {
                continue;
            }
            List<Element> rest = new ArrayList<>();
            for (String name : booleans) {
                if (used.add(name)) {
                    rest.add(new OptionSlot(name, false));
                }
            }
            for (String name : values) {
                if (used.add(name)) {
                    rest.add(new OptionSlot(name, sliders.contains(name)));
                }
            }
            built.get(id).addAll(at, rest);
        }

        List<Element> mainElements = built.get("");
        Screen main = new Screen(null, List.copyOf(mainElements), columns(mainColumns, mainElements.size()));
        Map<String, Screen> subs = new LinkedHashMap<>();
        for (String id : subLists.keySet()) {
            List<Element> elements = built.get(id);
            subs.put(id, new Screen(id, List.copyOf(elements), columns(subColumns.get(id), elements.size())));
        }
        return new PackMenus(main, Collections.unmodifiableMap(subs), List.copyOf(profiles), Set.copyOf(sliders),
                List.copyOf(warnings), screenLines);
    }

    private static List<String> split(String value) {
        // Iris: value.split(" +"); Properties already dropped the leading spaces.
        return Arrays.asList(value.split(" +"));
    }

    private static @Nullable Integer parseInt(String value, String key, List<String> warnings) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            warnings.add(key + " isn't a number: \"" + value + "\"");
            return null;
        }
    }

    private static int columns(@Nullable Integer given, int elements) {
        return given != null ? given : elements > 18 ? 3 : 2;
    }

    private static List<Element> build(List<String> tokens, String screenId, Set<String> booleans, Set<String> values,
                                       Set<String> sliders, List<Profile> profiles, Set<String> used,
                                       Map<String, Integer> starAt, List<String> warnings) {
        List<Element> out = new ArrayList<>();
        for (String token : tokens) {
            if (token.equals("*")) {
                starAt.put(screenId, out.size());
            } else if (token.equals("<empty>")) {
                out.add(new Empty());
            } else if (token.equals("<profile>")) {
                if (!profiles.isEmpty()) {
                    out.add(new ProfileSlot());
                }
            } else if (token.length() >= 2 && token.startsWith("[") && token.endsWith("]")) {
                out.add(new Link(token.substring(1, token.length() - 1)));
            } else if (booleans.contains(token)) {
                out.add(new OptionSlot(token, false));
                used.add(token);
            } else if (values.contains(token)) {
                out.add(new OptionSlot(token, sliders.contains(token)));
                used.add(token);
            } else {
                warnings.add("screen" + (screenId.isEmpty() ? "" : "." + screenId) + ": no option \"" + token + "\"");
                out.add(new Empty());
            }
        }
        return out;
    }

    private static Profile parseProfile(String name, List<String> path, Map<String, List<String>> tree,
                                        Set<String> booleans, List<String> warnings) {
        List<String> entries = tree.get(name);
        if (entries == null) {
            throw new IllegalArgumentException("Profile \"" + name + "\" does not exist");
        }
        Map<String, String> values = new LinkedHashMap<>();
        List<String> programs = new ArrayList<>();
        for (String entry : entries) {
            if (entry.startsWith("!program.")) {
                programs.add(entry.substring("!program.".length()));
            } else if (entry.startsWith("profile.")) {
                String included = entry.substring("profile.".length());
                if (path.contains(included)) {
                    throw new IllegalArgumentException("Profile \"" + name + "\" includes itself through " + String.join(" > ", path));
                }
                List<String> deeper = new ArrayList<>(path);
                deeper.add(included);
                Profile inner = parseProfile(included, deeper, tree, booleans, warnings);
                values.putAll(inner.values());
                programs.addAll(inner.disabledPrograms());
            } else if (entry.startsWith("!")) {
                values.put(entry.substring(1), "false");
            } else if (entry.contains("=")) {
                int at = entry.indexOf('=');
                values.put(entry.substring(0, at), entry.substring(at + 1));
            } else if (entry.contains(":")) {
                int at = entry.indexOf(':');
                values.put(entry.substring(0, at), entry.substring(at + 1));
            } else if (booleans.contains(entry)) {
                values.put(entry, "true");
            } else if (!entry.isEmpty()) {
                warnings.add("profile." + name + ": no on/off option \"" + entry + "\"");
            }
        }
        return new Profile(name, Collections.unmodifiableMap(values), List.copyOf(programs));
    }

    // ---- Results --------------------------------------------------------------------------

    public Screen main() {
        return main;
    }

    public Map<String, Screen> subScreens() {
        return subScreens;
    }

    /** The screen a link opens: the named sub-screen, or the main screen when there's none. */
    public Screen screen(@Nullable String id) {
        return id == null ? main : subScreens.getOrDefault(id, main);
    }

    /** Profiles in file order. */
    public List<Profile> profiles() {
        return profiles;
    }

    public Set<String> sliders() {
        return sliders;
    }

    public List<String> warnings() {
        return warnings;
    }

    /** How many {@code screen} and {@code screen.<NAME>} lines the file has. */
    public int screenLines() {
        return screenLines;
    }

    /**
     * The profile the current values match, and its neighbours in cycling order.
     *
     * @param value   an option's current value ("true"/"false" for on/off options)
     * @param isOption whether the pack has this option (others aren't compared)
     */
    public ProfileScan scan(Function<String, String> value, java.util.function.Predicate<String> isOption) {
        if (byPrecedence.isEmpty()) {
            return new ProfileScan(Optional.empty(), null, null);
        }
        int n = byPrecedence.size();
        for (int i = 0; i < n; i++) {
            Profile p = byPrecedence.get(i);
            if (matches(p, value, isOption)) {
                return new ProfileScan(Optional.of(p), byPrecedence.get(Math.floorMod(i + 1, n)), byPrecedence.get(Math.floorMod(i - 1, n)));
            }
        }
        return new ProfileScan(Optional.empty(), byPrecedence.getFirst(), byPrecedence.getLast());
    }

    private static boolean matches(Profile p, Function<String, String> value, java.util.function.Predicate<String> isOption) {
        for (Map.Entry<String, String> e : p.values().entrySet()) {
            if (isOption.test(e.getKey()) && !e.getValue().equals(value.apply(e.getKey()))) {
                return false;
            }
        }
        return true;
    }
}
