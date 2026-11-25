package io.github.hecbatista.metalshaders.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pack's settings ("options"), found and changed the way Iris does it: by editing the lines
 * of the pack's source files before the preprocessor runs.
 * <p>
 * Behaviour follows Iris (branch 26.3; see NOTICE):
 * <ul>
 *   <li><b>Which lines are options</b> ({@code OptionAnnotatedSource}): a value option is
 *       {@code #define NAME value // [allowed values]}; an on/off option is {@code #define NAME}
 *       (on) or {@code //#define NAME} (off), optionally followed by a comment; a {@code const}
 *       option is {@code const int|float|bool name = value;} for one of Iris's directive names.</li>
 *   <li><b>On/off options must be used:</b> a {@code #define NAME} only counts if some file in the
 *       same include group tests it with a bare {@code #ifdef NAME} / {@code #ifndef NAME}
 *       ({@code #if defined(...)} doesn't count, matching OptiFine).</li>
 *   <li><b>Include groups</b> ({@code ShaderPackOptions}): files are grouped by the include graph
 *       (weakly connected), starting from the program files Iris knows by name
 *       ({@code ShaderPackSourceNames}).</li>
 *   <li><b>Conflicts</b> ({@code OptionSet.Builder}): the same name with two different defaults
 *       is ambiguous and dropped (Iris logs "Ignoring ambiguous ... option").</li>
 *   <li><b>Settings file</b> ({@code Iris.loadExternalShaderpack}): {@code shaderpacks/<pack file
 *       name>.txt}, a Java properties file of {@code NAME=value}. Values for on/off options must be
 *       {@code true}/{@code false}; value options take any value, even one not in the list
 *       ({@code MutableOptionValues}).</li>
 *   <li><b>Applying</b> ({@code OptionAnnotatedSource.edit}): a changed value option's line becomes
 *       {@code #define NAME value}; an on/off option is commented or uncommented; a const option
 *       has its value replaced after the '='.</li>
 * </ul>
 * Not done: profiles ({@code profile.*} in shaders.properties) and Iris's rewrite of the settings
 * file; Luxium only reads it.
 */
public final class PackOptions {
    public enum Kind { DEFINE, CONST }

    public record BooleanOption(String name, Kind kind, boolean defaultValue) {
    }

    public record StringOption(String name, Kind kind, String defaultValue, List<String> allowedValues) {
    }

    /** Iris's {@code VALID_CONST_OPTION_NAMES}: the const directives a user may change. */
    static final Set<String> CONST_NAMES;

    static {
        Set<String> names = new HashSet<>(List.of(
                "shadowMapResolution", "shadowDistance", "voxelDistance", "shadowDistanceRenderMul",
                "entityShadowDistanceMul", "shadowIntervalSize", "generateShadowMipmap",
                "generateShadowColorMipmap", "shadowHardwareFiltering", "shadowtex0Mipmap", "shadowtexMipmap",
                "shadowtex1Mipmap", "shadowtex0Nearest", "shadowtexNearest", "shadow0MinMagNearest",
                "shadowtex1Nearest", "shadow1MinMagNearest", "wetnessHalflife", "drynessHalflife",
                "eyeBrightnessHalflife", "centerDepthHalflife", "sunPathRotation", "ambientOcclusionLevel",
                "superSamplingLevel", "noiseTextureResolution"));
        // PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_IRIS = 8
        for (int i = 0; i < 8; i++) {
            names.add("shadowcolor" + i + "Mipmap");
            names.add("shadowColor" + i + "Mipmap");
            names.add("shadowcolor" + i + "Nearest");
            names.add("shadowColor" + i + "Nearest");
            names.add("shadowcolor" + i + "MinMagNearest");
            names.add("shadowColor" + i + "MinMagNearest");
            names.add("shadowHardwareFiltering" + i);
        }
        CONST_NAMES = Collections.unmodifiableSet(names);
    }

    private final Map<String, BooleanOption> booleans;
    private final Map<String, StringOption> strings;
    private final Map<String, Boolean> changedBooleans = new TreeMap<>();
    private final Map<String, String> changedStrings = new TreeMap<>();
    private final Map<String, String> ignored = new TreeMap<>();
    private final List<String> ambiguous;

    private PackOptions(Map<String, BooleanOption> booleans, Map<String, StringOption> strings,
                        List<String> ambiguous, Map<String, String> settings) {
        this.booleans = booleans;
        this.strings = strings;
        this.ambiguous = ambiguous;
        // MutableOptionValues.addAll: a value equal to the default isn't a change.
        for (Map.Entry<String, String> e : settings.entrySet()) {
            String name = e.getKey();
            String value = e.getValue();
            BooleanOption b = booleans.get(name);
            StringOption s = strings.get(name);
            if (b != null) {
                if (!value.equals("true") && !value.equals("false")) {
                    ignored.put(name, "on/off option needs true or false, got \"" + value + "\"");
                } else if (Boolean.parseBoolean(value) != b.defaultValue()) {
                    changedBooleans.put(name, Boolean.parseBoolean(value));
                }
            } else if (s != null) {
                if (!value.equals(s.defaultValue())) {
                    changedStrings.put(name, value);
                }
            } else {
                ignored.put(name, "not an option of this pack");
            }
        }
    }

    // ---- Finding the options --------------------------------------------------------------

    /**
     * Finds the options of a pack and applies a settings map (NAME -> value).
     *
     * @param files  reads pack files by path, like "/lib/common.glsl"
     * @param starts program files to start the include graph from, like "/world0/composite.fsh"
     */
    public static PackOptions discover(IncludeExpander.FileSource files, List<String> starts,
                                       Map<String, String> settings) throws IOException {
        // The include graph: every file reachable from the starts, and its include targets.
        Map<String, List<String>> lines = new TreeMap<>();
        Map<String, List<String>> edges = new HashMap<>();
        Deque<String> todo = new ArrayDeque<>();
        for (String start : starts) {
            todo.add(IncludeExpander.normalize(start));
        }
        while (!todo.isEmpty()) {
            String path = todo.poll();
            if (lines.containsKey(path)) {
                continue;
            }
            Optional<String> text = files.read(path);
            if (text.isEmpty()) {
                continue;   // a missing include fails later, when the program is expanded
            }
            List<String> fileLines = List.of(text.get().replace("\u0000", "").split("\\R", -1));
            lines.put(path, fileLines);
            List<String> targets = new ArrayList<>();
            for (String line : fileLines) {
                String target = includeTarget(path, line);
                if (target != null) {
                    targets.add(target);
                    todo.add(target);
                }
            }
            edges.put(path, targets);
        }

        // Weakly connected components (union-find over include edges).
        Map<String, String> parent = new HashMap<>();
        for (String path : lines.keySet()) {
            parent.put(path, path);
        }
        edges.forEach((from, targets) -> {
            for (String to : targets) {
                if (parent.containsKey(to)) {
                    parent.put(find(parent, from), find(parent, to));
                }
            }
        });
        Map<String, List<String>> components = new TreeMap<>();
        for (String path : lines.keySet()) {
            components.computeIfAbsent(find(parent, path), k -> new ArrayList<>()).add(path);
        }

        Merger merger = new Merger();
        for (List<String> component : components.values()) {
            Set<String> referenced = new HashSet<>();
            for (String path : component) {
                for (String line : lines.get(path)) {
                    if (parse(line) instanceof IfdefRef ref) {
                        referenced.add(ref.name());
                    }
                }
            }
            for (String path : component) {
                for (String line : lines.get(path)) {
                    Object parsed = parse(line);
                    if (parsed instanceof BooleanOption b && referenced.contains(b.name())) {
                        merger.add(b);
                    } else if (parsed instanceof StringOption s) {
                        merger.add(s);
                    }
                }
            }
        }
        return new PackOptions(merger.booleans, merger.strings, merger.ambiguous, settings);
    }

    private static String find(Map<String, String> parent, String x) {
        while (!parent.get(x).equals(x)) {
            parent.put(x, parent.get(parent.get(x)));
            x = parent.get(x);
        }
        return x;
    }

    /** The resolved path of an {@code #include} line (Iris FileNode.findIncludes), or null. */
    private static String includeTarget(String path, String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("#include")) {
            return null;
        }
        String target = trimmed.substring("#include".length()).trim();
        if (target.startsWith("\"")) {
            target = target.substring(1);
        }
        if (target.endsWith("\"")) {
            target = target.substring(0, target.length() - 1);
        }
        int slash = path.lastIndexOf('/');
        String folder = slash <= 0 ? "" : path.substring(0, slash);
        return target.startsWith("/") ? IncludeExpander.normalize(target) : IncludeExpander.normalize(folder + "/" + target);
    }

    /** OptionSet.Builder's merge rule: same default merges, a different default drops the name. */
    private static final class Merger {
        final Map<String, BooleanOption> booleans = new TreeMap<>();
        final Map<String, StringOption> strings = new TreeMap<>();
        final List<String> ambiguous = new ArrayList<>();

        void add(BooleanOption o) {
            BooleanOption existing = booleans.get(o.name());
            if (existing == null) {
                booleans.put(o.name(), o);
            } else if (existing.defaultValue() != o.defaultValue()) {
                booleans.remove(o.name());
                ambiguous.add(o.name());
            }
        }

        void add(StringOption o) {
            StringOption existing = strings.get(o.name());
            if (existing == null) {
                strings.put(o.name(), o);
            } else if (!existing.defaultValue().equals(o.defaultValue())) {
                strings.remove(o.name());
                ambiguous.add(o.name());
            }
        }
    }

    // ---- Reading one line (Iris OptionAnnotatedSource.parseLine) ---------------------------

    /** A bare {@code #ifdef NAME} or {@code #ifndef NAME}. */
    record IfdefRef(String name) {
    }

    /** What a line is: a {@link BooleanOption}, a {@link StringOption}, an {@link IfdefRef}, or null. */
    static Object parse(String lineText) {
        if (!lineText.contains("#define") && !lineText.contains("const")
                && !lineText.contains("#ifdef") && !lineText.contains("#ifndef")) {
            return null;
        }
        Cursor line = new Cursor(lineText.trim());
        if (line.literal("#ifdef") || line.literal("#ifndef")) {
            if (!line.whitespace()) {
                return null;
            }
            String name = line.word();
            line.whitespace();
            return name == null || !line.end() ? null : new IfdefRef(name);
        } else if (line.literal("const")) {
            return parseConst(line);
        } else if (line.text.contains("#define")) {
            return parseDefine(line);
        }
        return null;
    }

    private static Object parseConst(Cursor line) {
        if (!line.whitespace()) {
            return null;
        }
        boolean isString;
        if (line.literal("int") || line.literal("float")) {
            isString = true;
        } else if (line.literal("bool")) {
            isString = false;
        } else {
            return null;
        }
        if (!line.whitespace()) {
            return null;
        }
        String name = line.word();
        if (name == null) {
            return null;
        }
        line.whitespace();
        if (!line.literal("=")) {
            return null;
        }
        line.whitespace();
        String value = line.wordOrNumber();
        if (value == null) {
            return null;
        }
        line.whitespace();
        if (!line.literal(";")) {
            return null;
        }
        line.whitespace();
        String comment;
        if (line.comments()) {
            comment = line.text.trim();
        } else if (!line.end()) {
            return null;
        } else {
            comment = null;
        }
        if (!CONST_NAMES.contains(name)) {
            return null;
        }
        if (!isString) {
            if (value.equals("true") || value.equals("false")) {
                return new BooleanOption(name, Kind.CONST, value.equals("true"));
            }
            return null;
        }
        return stringOption(Kind.CONST, name, comment, value);
    }

    private static Object parseDefine(Cursor line) {
        boolean leadingComment = line.comments();
        line.whitespace();
        if (!line.literal("#define") || !line.whitespace()) {
            return null;
        }
        String name = line.word();
        if (name == null) {
            return null;
        }
        boolean tookWhitespace = line.whitespace();
        if (line.end()) {
            return new BooleanOption(name, Kind.DEFINE, !leadingComment);
        }
        if (line.comments()) {
            return new BooleanOption(name, Kind.DEFINE, !leadingComment);
        } else if (!tookWhitespace) {
            return null;
        }
        if (leadingComment) {
            return null;
        }
        String value = line.wordOrNumber();
        if (value == null) {
            return null;
        }
        tookWhitespace = line.whitespace();
        if (line.end()) {
            return null;
        } else if (!tookWhitespace) {
            if (!line.comments()) {
                return null;
            }
        } else if (!line.comments()) {
            return null;
        }
        return stringOption(Kind.DEFINE, name, line.text.trim(), value);
    }

    /** Iris StringOption.create: needs "[allowed values]" in the comment. */
    private static StringOption stringOption(Kind kind, String name, String comment, String defaultValue) {
        if (comment == null) {
            return null;
        }
        int open = comment.indexOf('[');
        if (open == -1) {
            return null;
        }
        int close = comment.indexOf(']', open);
        if (close == -1) {
            return null;
        }
        List<String> allowed = new ArrayList<>(List.of(comment.substring(open + 1, close).split(" ")));
        if (!allowed.contains(defaultValue)) {
            allowed.add(defaultValue);
        }
        return new StringOption(name, kind, defaultValue, List.copyOf(allowed));
    }

    /** Iris ParsedString, the same small cursor over one trimmed line. */
    private static final class Cursor {
        String text;

        Cursor(String text) {
            this.text = text;
        }

        boolean literal(String token) {
            if (!text.startsWith(token)) {
                return false;
            }
            text = text.substring(token.length());
            return true;
        }

        boolean whitespace() {
            if (text.isEmpty() || !Character.isWhitespace(text.charAt(0))) {
                return false;
            }
            text = text.trim();
            return true;
        }

        boolean comments() {
            if (!text.startsWith("//")) {
                return false;
            }
            text = text.substring(2);
            while (text.startsWith("/")) {
                text = text.substring(1);
            }
            return true;
        }

        boolean end() {
            return text.isEmpty();
        }

        private String take(int n) {
            String result = text.substring(0, n);
            text = text.substring(n);
            return result;
        }

        String word() {
            if (end()) {
                return null;
            }
            int position = 0;
            for (char c : text.toCharArray()) {
                if (!Character.isDigit(c) && !Character.isAlphabetic(c) && c != '_') {
                    break;
                }
                position++;
            }
            return position == 0 ? null : take(position);
        }

        String number() {
            if (end()) {
                return null;
            }
            int position = 0;
            while (position < text.length()) {
                if (position + 1 < text.length()) {
                    if (!Character.isDigit(text.charAt(position)) && !Character.isDigit(text.charAt(position + 1))) {
                        break;
                    }
                } else if (!Character.isDigit(text.charAt(position))) {
                    break;
                }
                position++;
            }
            if (position > 0 && position + 1 < text.length()
                    && (text.charAt(position) == 'f' || text.charAt(position) == 'F')) {
                position++;
            }
            try {
                Float.parseFloat(text.substring(0, position));
            } catch (Exception e) {
                return null;
            }
            return take(position);
        }

        String wordOrNumber() {
            String number = number();
            return number == null ? word() : number;
        }
    }

    // ---- Applying the values (Iris OptionAnnotatedSource.edit) -----------------------------

    /** The pack's files with the changed options written in. */
    public IncludeExpander.FileSource apply(IncludeExpander.FileSource files) {
        if (changedBooleans.isEmpty() && changedStrings.isEmpty()) {
            return files;
        }
        return path -> files.read(path).map(this::applyText);
    }

    String applyText(String text) {
        String[] lines = text.split("\\R", -1);
        StringBuilder out = new StringBuilder(text.length() + 64);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(edit(lines[i]));
        }
        return out.toString();
    }

    String edit(String line) {
        Object parsed = parse(line);
        if (parsed instanceof BooleanOption b) {
            Boolean value = changedBooleans.get(b.name());
            if (b.kind() == Kind.DEFINE) {
                boolean on = value != null ? value : b.defaultValue();
                boolean commented = line.trim().startsWith("//");
                if (commented && on) {
                    Cursor c = new Cursor(line);
                    c.whitespace();
                    c.comments();
                    return c.text;
                } else if (!on) {
                    return "//" + line;
                }
                return line;
            }
            return value == null ? line : editConst(line, Boolean.toString(b.defaultValue()), value.toString());
        }
        if (parsed instanceof StringOption s) {
            String value = changedStrings.get(s.name());
            if (value == null) {
                return line;
            }
            return s.kind() == Kind.DEFINE
                    ? "#define " + s.name() + " " + value + " // Luxium: changed option"
                    : editConst(line, s.defaultValue(), value);
        }
        return line;
    }

    private static String editConst(String line, String current, String replacement) {
        int equals = line.indexOf('=');
        return line.substring(0, equals)
                + line.substring(equals).replaceFirst(Pattern.quote(current), Matcher.quoteReplacement(replacement));
    }

    // ---- Results --------------------------------------------------------------------------

    public Map<String, BooleanOption> booleanOptions() {
        return Collections.unmodifiableMap(booleans);
    }

    public Map<String, StringOption> stringOptions() {
        return Collections.unmodifiableMap(strings);
    }

    /** Options the settings changed from their defaults, NAME -> value. */
    public Map<String, String> changed() {
        Map<String, String> out = new TreeMap<>();
        changedBooleans.forEach((k, v) -> out.put(k, v.toString()));
        out.putAll(changedStrings);
        return out;
    }

    /**
     * An on/off option's value as Iris reads it in expressions (OptionValues
     * .getBooleanValueOrDefault): "0"/"1" are literals, and a name that isn't an on/off option
     * counts as on (Iris logs "defaulting to true").
     */
    public boolean booleanValue(String name) {
        if (name.equals("0")) {
            return false;
        }
        if (name.equals("1")) {
            return true;
        }
        Boolean changed = changedBooleans.get(name);
        if (changed != null) {
            return changed;
        }
        BooleanOption option = booleans.get(name);
        return option == null || option.defaultValue();
    }

    /** Settings that weren't applied, NAME -> reason. */
    public Map<String, String> ignored() {
        return Collections.unmodifiableMap(ignored);
    }

    /** Names dropped because two files gave them different defaults. */
    public List<String> ambiguous() {
        return Collections.unmodifiableList(ambiguous);
    }

    /**
     * Defines for .properties files (Iris PropertiesPreprocessor.preprocessSource): every on/off
     * option that is on, the given game defines, then every value option's current value.
     */
    public Map<String, String> propertyDefines(Map<String, String> gameDefines) {
        Map<String, String> out = new LinkedHashMap<>();
        booleans.forEach((name, o) -> {
            if (changedBooleans.getOrDefault(name, o.defaultValue())) {
                out.put(name, "");
            }
        });
        out.putAll(gameDefines);
        strings.forEach((name, o) -> out.put(name, changedStrings.getOrDefault(name, o.defaultValue())));
        return out;
    }

    // ---- The settings file and the starting files -----------------------------------------

    /** {@code shaderpacks/<pack file name>.txt}, as Iris names it. */
    public static Path settingsFile(Path pack) {
        return pack.resolveSibling(pack.getFileName() + ".txt");
    }

    /** Reads the settings file (ISO-8859-1 properties, as OptiFine specifies); empty if missing. */
    public static Map<String, String> readSettings(Path file) throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        p.stringPropertyNames().forEach(k -> out.put(k, p.getProperty(k)));
        return out;
    }

    private static final Pattern ARRAY_PROGRAM = Pattern.compile("(setup|begin|shadowcomp|prepare|deferred|composite)([1-9][0-9]?)?");
    private static final Set<String> SINGLE_PROGRAMS = Set.of(
            "shadow", "shadow_solid", "shadow_cutout", "shadow_water", "shadow_entities", "shadow_lightning", "shadow_block",
            "gbuffers_basic", "gbuffers_line", "gbuffers_textured", "gbuffers_textured_lit", "gbuffers_skybasic",
            "gbuffers_skytextured", "gbuffers_clouds", "gbuffers_terrain", "gbuffers_terrain_solid",
            "gbuffers_terrain_cutout", "gbuffers_damagedblock", "gbuffers_block", "gbuffers_block_translucent",
            "gbuffers_beaconbeam", "gbuffers_item", "gbuffers_entities", "gbuffers_entities_translucent",
            "gbuffers_lightning", "gbuffers_particles", "gbuffers_particles_translucent", "gbuffers_entities_glowing",
            "gbuffers_armor_glint", "gbuffers_spidereyes", "gbuffers_hand", "gbuffers_weather", "gbuffers_water",
            "gbuffers_hand_water", "dh_terrain", "dh_water", "dh_generic", "dh_shadow", "final");
    private static final Pattern STAGE = Pattern.compile("(.+)\\.(vsh|tcs|tes|gsh|fsh)");
    private static final Pattern COMPUTE = Pattern.compile("(.+?)(_[a-z])?\\.csh");

    /**
     * Whether Iris starts its include graph at this file name (ShaderPackSourceNames
     * .POTENTIAL_STARTS): the programs it knows, numbered 1-99 for the arrays, compute stages
     * (with _a.._z) only for the arrays, shadow and final.
     */
    static boolean isIrisStart(String fileName) {
        Matcher stage = STAGE.matcher(fileName);
        if (stage.matches()) {
            return SINGLE_PROGRAMS.contains(stage.group(1)) || ARRAY_PROGRAM.matcher(stage.group(1)).matches();
        }
        Matcher compute = COMPUTE.matcher(fileName);
        if (compute.matches()) {
            String base = compute.group(1);
            return base.equals("shadow") || base.equals("final") || ARRAY_PROGRAM.matcher(base).matches();
        }
        return false;
    }

    /** The include graph's starting files for a pack: Iris's program names in every folder it reads. */
    public static List<String> starts(PackInfo info) {
        List<String> out = new ArrayList<>();
        info.programFiles().forEach((folder, files) -> {
            for (String file : files) {
                if (isIrisStart(file)) {
                    out.add(folder.isEmpty() ? "/" + file : "/" + folder + "/" + file);
                }
            }
        });
        return out;
    }

    /** One call for the game: read the pack's settings file, find the options, apply. */
    public static PackOptions load(Path pack) throws IOException {
        Map<String, String> settings = readSettings(settingsFile(pack));
        PackInfo info = PackReader.read(pack);
        return PackReader.open(pack, (name, root, shaders) -> {
            PackOptions options = discover(path -> PackReader.readText(shaders, path), starts(info), settings);
            options.labels = readLabels(PackReader.readText(shaders, "/lang/en_US.lang")
                    .or(() -> readQuietly(shaders, "/lang/en_us.lang")).orElse(""));
            options.switchOffComputeOptions(PackReader.readText(shaders, "/shaders.properties").orElse(""), info);
            return options;
        });
    }

    private static final Pattern PROGRAM_SWITCH = Pattern.compile(
            "^\\s*program\\.([^.\\s]+)\\.enabled\\s*=\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*$", Pattern.MULTILINE);

    /** On/off options switched off because they turn on a compute program (see below). */
    private final List<String> computeOff = new ArrayList<>();

    /**
     * Luxium can't run compute programs (.csh). Where shaders.properties switches one on with a
     * single on/off option ({@code program.world0/deferred4_a.enabled = SH_SKYLIGHT}: Photon's
     * skylight, on by default), that option is switched off, so the pack takes its own path
     * without the compute pass instead of reading data nothing wrote. Not something Iris does
     * (it runs them). The user's settings file can't turn such an option back on.
     */
    void switchOffComputeOptions(String properties, PackInfo info) {
        Matcher m = PROGRAM_SWITCH.matcher(properties);
        while (m.find()) {
            String program = m.group(1);
            String option = m.group(2);
            int slash = program.lastIndexOf('/');
            String folder = slash < 0 ? "" : program.substring(0, slash);
            String name = program.substring(slash + 1);
            boolean compute = info.programFiles().getOrDefault(folder, List.of()).contains(name + ".csh");
            if (compute && booleans.containsKey(option) && booleanValue(option) && !computeOff.contains(option)) {
                changedBooleans.put(option, false);
                computeOff.add(option);
            }
        }
    }

    /** Options {@link #switchOffComputeOptions} switched off. */
    public List<String> computeOff() {
        return Collections.unmodifiableList(computeOff);
    }

    private static Optional<String> readQuietly(Path shaders, String path) {
        try {
            return PackReader.readText(shaders, path);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    // ---- The option list written for the user ---------------------------------------------

    /** The pack's own English names for options and values (lang/en_US.lang), for the list. */
    private Map<String, String> labels = Map.of();

    private static Map<String, String> readLabels(String lang) {
        Map<String, String> out = new HashMap<>();
        for (String line : lang.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq > 0 && (line.startsWith("option.") || line.startsWith("value."))) {
                // '§' + a character is a Minecraft colour code; drop it for a plain text file.
                out.put(line.substring(0, eq).strip(), line.substring(eq + 1).replaceAll("§.", "").strip());
            }
        }
        return out;
    }

    /** Where the option list goes: next to the pack, {@code <pack file name>.luxium-options.txt}. */
    public static Path optionListFile(Path pack) {
        return pack.resolveSibling(pack.getFileName() + ".luxium-options.txt");
    }

    /** Every option with its default and allowed values, as a plain text list for the user. */
    public String describe(String packFileName) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Settings of ").append(packFileName).append(", listed by Luxium. This file is rewritten at every start.\n")
                .append("# To change a setting, put NAME=value lines in ").append(packFileName).append(".txt\n")
                .append("# (the same file Iris uses), then restart the game.\n")
                .append("# On/off settings take true or false.\n\n");
        Map<String, String> changed = changed();
        Map<String, Object> all = new TreeMap<>();
        all.putAll(booleans);
        all.putAll(strings);
        all.forEach((name, option) -> {
            String label = labels.get("option." + name);
            sb.append(name);
            if (option instanceof BooleanOption b) {
                sb.append(" = ").append(changed.getOrDefault(name, Boolean.toString(b.defaultValue())))
                        .append("   (on/off, default ").append(b.defaultValue()).append(')');
            } else if (option instanceof StringOption s) {
                sb.append(" = ").append(changed.getOrDefault(name, s.defaultValue()))
                        .append("   (default ").append(s.defaultValue()).append("; allowed:");
                for (String v : s.allowedValues()) {
                    String valueLabel = labels.get("value." + name + "." + v);
                    sb.append(' ').append(v);
                    if (valueLabel != null && !valueLabel.equals(v)) {
                        sb.append(" \"").append(valueLabel).append('"');
                    }
                }
                sb.append(')');
            }
            if (changed.containsKey(name)) {
                sb.append("   CHANGED");
            }
            if (label != null) {
                sb.append("   # ").append(label);
            }
            sb.append('\n');
        });
        return sb.toString();
    }
}
