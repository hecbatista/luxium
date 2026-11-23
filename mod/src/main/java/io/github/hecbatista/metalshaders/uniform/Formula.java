package io.github.hecbatista.metalshaders.uniform;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One custom-uniform formula from a pack, like {@code smooth(4, eyeBrightness.y / 240.0, 5, 5)}:
 * parsed once into a tree, then evaluated every frame.
 * <p>
 * Values are Integer, Float or Boolean, plus int[]/float[] for vector inputs, which can only be
 * read with {@code .x .y .z .w} (or {@code .r .g .b .a}). Arithmetic follows Iris's types:
 * int with int stays int (so {@code 7 / 2} is 3 and {@code %} is whole-number), anything with a
 * float becomes float. Functions and their quirks follow Iris's IrisFunctions and SmoothFloat
 * (see NOTICE); only the ones packs commonly use are here.
 * <p>
 * Our own code. The pack writes this text, so parsing is bounded: at most
 * {@link #MAX_LENGTH} characters and {@link #MAX_DEPTH} levels of nesting.
 */
public final class Formula {
    static final int MAX_LENGTH = 4096;
    static final int MAX_DEPTH = 64;

    /** Looks up a name: a game value, or another custom uniform/variable. */
    @FunctionalInterface
    public interface Names {
        /** The value, or null if the name doesn't exist. */
        Object get(String name);
    }

    public static final class FormulaException extends Exception {
        public FormulaException(String message) {
            super(message);
        }
    }

    // ---- the tree ---------------------------------------------------------------------------

    private sealed interface Node permits Num, Ref, Member, Unary, Binary, Call {
    }

    private record Num(Object value) implements Node {
    }

    private record Ref(String name) implements Node {
    }

    private record Member(Node target, int index) implements Node {
    }

    private record Unary(String op, Node operand) implements Node {
    }

    private record Binary(String op, Node left, Node right) implements Node {
    }

    /** {@code smooth} keeps its own memory between frames, one per place it appears. */
    private record Call(String name, List<Node> args, SmoothFloat smooth) implements Node {
    }

    private final String text;
    private final Node root;
    private final Set<String> names;

    private Formula(String text, Node root, Set<String> names) {
        this.text = text;
        this.root = root;
        this.names = names;
    }

    /** The names the formula reads, so callers can order formulas by what they depend on. */
    public Set<String> names() {
        return names;
    }

    @Override
    public String toString() {
        return text;
    }

    // ---- parsing ----------------------------------------------------------------------------

    public static Formula parse(String text) throws FormulaException {
        if (text.length() > MAX_LENGTH) {
            throw new FormulaException("Formula longer than " + MAX_LENGTH + " characters");
        }
        Parser p = new Parser(text);
        Node root = p.expression(0);
        p.skipSpace();
        if (p.pos < text.length()) {
            throw new FormulaException("Unexpected '" + text.charAt(p.pos) + "' at position " + p.pos);
        }
        return new Formula(text, root, Set.copyOf(p.names));
    }

    private static final class Parser {
        final String s;
        int pos;
        final Set<String> names = new LinkedHashSet<>();

        Parser(String s) {
            this.s = s;
        }

        void skipSpace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        boolean accept(String token) {
            skipSpace();
            if (s.startsWith(token, pos)) {
                pos += token.length();
                return true;
            }
            return false;
        }

        // Precedence, lowest first: ||, &&, == !=, < > <= >=, + -, * / %
        private static final String[][] LEVELS = {
                {"||"}, {"&&"}, {"==", "!="}, {"<=", ">=", "<", ">"}, {"+", "-"}, {"*", "/", "%"}};

        Node expression(int depth) throws FormulaException {
            return binary(0, depth);
        }

        Node binary(int level, int depth) throws FormulaException {
            if (level == LEVELS.length) {
                return unary(depth);
            }
            Node left = binary(level + 1, depth);
            while (true) {
                String op = null;
                for (String candidate : LEVELS[level]) {
                    // Don't read "<" out of "<=" (the longer ones are listed first).
                    if (accept(candidate)) {
                        op = candidate;
                        break;
                    }
                }
                if (op == null) {
                    return left;
                }
                left = new Binary(op, left, binary(level + 1, depth));
            }
        }

        Node unary(int depth) throws FormulaException {
            if (depth > MAX_DEPTH) {
                throw new FormulaException("Formula nested deeper than " + MAX_DEPTH);
            }
            if (accept("-")) {
                return new Unary("-", unary(depth + 1));
            }
            if (accept("!")) {
                return new Unary("!", unary(depth + 1));
            }
            return postfix(primary(depth), depth);
        }

        Node postfix(Node node, int depth) throws FormulaException {
            while (true) {
                skipSpace();
                if (pos < s.length() && s.charAt(pos) == '.' && pos + 1 < s.length() && Character.isLetter(s.charAt(pos + 1))) {
                    pos++;
                    String component = identifier();
                    int index = "xyzw".indexOf(component);
                    if (index < 0) {
                        index = "rgba".indexOf(component);
                    }
                    if (component.length() != 1 || index < 0) {
                        throw new FormulaException("Unsupported component ." + component);
                    }
                    node = new Member(node, index);
                } else {
                    return node;
                }
            }
        }

        Node primary(int depth) throws FormulaException {
            skipSpace();
            if (pos >= s.length()) {
                throw new FormulaException("Formula ends too early");
            }
            char c = s.charAt(pos);
            if (accept("(")) {
                Node inner = expression(depth + 1);
                if (!accept(")")) {
                    throw new FormulaException("Missing ')' at position " + pos);
                }
                return inner;
            }
            if (Character.isDigit(c) || (c == '.' && pos + 1 < s.length() && Character.isDigit(s.charAt(pos + 1)))) {
                return number();
            }
            if (Character.isLetter(c) || c == '_') {
                String name = identifier();
                if (accept("(")) {
                    List<Node> args = new ArrayList<>();
                    if (!accept(")")) {
                        do {
                            args.add(expression(depth + 1));
                        } while (accept(","));
                        if (!accept(")")) {
                            throw new FormulaException("Missing ')' after arguments of " + name);
                        }
                    }
                    return new Call(name, List.copyOf(args), name.equals("smooth") ? new SmoothFloat() : null);
                }
                if (name.equals("true") || name.equals("false")) {
                    return new Num(Boolean.parseBoolean(name));
                }
                names.add(name);
                return new Ref(name);
            }
            throw new FormulaException("Unexpected '" + c + "' at position " + pos);
        }

        String identifier() {
            int start = pos;
            while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
                pos++;
            }
            return s.substring(start, pos);
        }

        Node number() throws FormulaException {
            int start = pos;
            boolean isFloat = false;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                isFloat |= s.charAt(pos) == '.';
                pos++;
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            String n = s.substring(start, pos);
            if (pos < s.length() && (s.charAt(pos) == 'f' || s.charAt(pos) == 'F')) {
                isFloat = true;
                pos++;
            }
            try {
                return new Num(isFloat ? (Object) Float.parseFloat(n) : (Object) Integer.parseInt(n));
            } catch (NumberFormatException e) {
                throw new FormulaException("Bad number " + n);
            }
        }
    }

    // ---- evaluation -------------------------------------------------------------------------

    /**
     * Evaluates once. {@code frameTime} is the seconds since the last frame, for {@code smooth}.
     * Call it exactly once per frame per formula, or {@code smooth} advances too fast.
     */
    public Object evaluate(Names names, float frameTime) throws FormulaException {
        return eval(root, names, frameTime);
    }

    private static Object eval(Node node, Names names, float dt) throws FormulaException {
        return switch (node) {
            case Num n -> n.value();
            case Ref r -> {
                Object v = names.get(r.name());
                if (v == null) {
                    throw new FormulaException("Unknown name '" + r.name() + "'");
                }
                yield v;
            }
            case Member m -> {
                Object v = eval(m.target(), names, dt);
                if (v instanceof float[] f && m.index() < f.length) {
                    yield f[m.index()];
                }
                if (v instanceof int[] i && m.index() < i.length) {
                    yield i[m.index()];
                }
                throw new FormulaException("Component of something that isn't a vector");
            }
            case Unary u -> {
                Object v = eval(u.operand(), names, dt);
                if (u.op().equals("!")) {
                    yield !truth(v);
                }
                yield v instanceof Integer i ? (Object) (-i) : (Object) (-num(v));
            }
            case Binary b -> binary(b.op(), eval(b.left(), names, dt), b, names, dt);
            case Call c -> call(c, names, dt);
        };
    }

    private static Object binary(String op, Object a, Binary b, Names names, float dt) throws FormulaException {
        // && and || only evaluate the right side when needed.
        if (op.equals("&&")) {
            return truth(a) && truth(eval(b.right(), names, dt));
        }
        if (op.equals("||")) {
            return truth(a) || truth(eval(b.right(), names, dt));
        }
        Object c = eval(b.right(), names, dt);
        boolean ints = a instanceof Integer && c instanceof Integer;
        switch (op) {
            case "==" -> {
                return a instanceof Boolean || c instanceof Boolean ? truth(a) == truth(c) : num(a) == num(c);
            }
            case "!=" -> {
                return a instanceof Boolean || c instanceof Boolean ? truth(a) != truth(c) : num(a) != num(c);
            }
            case "<" -> {
                return num(a) < num(c);
            }
            case ">" -> {
                return num(a) > num(c);
            }
            case "<=" -> {
                return num(a) <= num(c);
            }
            case ">=" -> {
                return num(a) >= num(c);
            }
            default -> {
            }
        }
        if (ints) {
            int x = (Integer) a;
            int y = (Integer) c;
            return switch (op) {
                case "+" -> x + y;
                case "-" -> x - y;
                case "*" -> x * y;
                case "/" -> y == 0 ? 0 : x / y;
                case "%" -> y == 0 ? 0 : x % y;
                default -> throw new FormulaException("Unknown operator " + op);
            };
        }
        float x = num(a);
        float y = num(c);
        return switch (op) {
            case "+" -> x + y;
            case "-" -> x - y;
            case "*" -> x * y;
            case "/" -> x / y;
            case "%" -> x % y;
            default -> throw new FormulaException("Unknown operator " + op);
        };
    }

    private static Object call(Call c, Names names, float dt) throws FormulaException {
        List<Node> args = c.args();
        int n = args.size();
        switch (c.name()) {
            case "if" -> {
                // if(cond1, value1, [cond2, value2, ...] elseValue)
                if (n < 3 || n % 2 == 0) {
                    throw new FormulaException("if() needs an odd number of arguments, at least 3");
                }
                for (int i = 0; i + 1 < n; i += 2) {
                    if (truth(eval(args.get(i), names, dt))) {
                        return eval(args.get(i + 1), names, dt);
                    }
                }
                return eval(args.get(n - 1), names, dt);
            }
            case "in" -> {
                if (n < 2) {
                    throw new FormulaException("in() needs at least 2 arguments");
                }
                float x = num(eval(args.getFirst(), names, dt));
                for (int i = 1; i < n; i++) {
                    if (num(eval(args.get(i), names, dt)) == x) {
                        return true;
                    }
                }
                return false;
            }
            case "smooth" -> {
                // Iris's forms: smooth(target), smooth(target, fade), smooth(id, target, fadeUp, fadeDown).
                // The id is only there for OptiFine compatibility and is ignored.
                // Three arguments are ambiguous in Iris too; read as (target, fadeUp, fadeDown) here.
                float target;
                float up;
                float down;
                switch (n) {
                    case 1 -> {
                        target = num(eval(args.get(0), names, dt));
                        up = 1;
                        down = 1;
                    }
                    case 2 -> {
                        target = num(eval(args.get(0), names, dt));
                        up = down = num(eval(args.get(1), names, dt));
                    }
                    case 3 -> {
                        target = num(eval(args.get(0), names, dt));
                        up = num(eval(args.get(1), names, dt));
                        down = num(eval(args.get(2), names, dt));
                    }
                    case 4 -> {
                        target = num(eval(args.get(1), names, dt));
                        up = num(eval(args.get(2), names, dt));
                        down = num(eval(args.get(3), names, dt));
                    }
                    default -> throw new FormulaException("smooth() takes 1 to 4 arguments");
                }
                return c.smooth().updateAndGet(target, up, down, dt);
            }
            default -> {
            }
        }
        Object[] v = new Object[n];
        boolean allInts = true;
        for (int i = 0; i < n; i++) {
            v[i] = eval(args.get(i), names, dt);
            allInts &= v[i] instanceof Integer;
        }
        return switch (c.name()) {
            case "abs" -> one(v, c) instanceof Integer i ? (Object) Math.abs(i) : (Object) Math.abs(num(v[0]));
            case "min", "max" -> {
                if (n < 2) {
                    throw new FormulaException(c.name() + "() needs at least 2 arguments");
                }
                boolean max = c.name().equals("max");
                if (allInts) {
                    int r = (Integer) v[0];
                    for (int i = 1; i < n; i++) {
                        r = max ? Math.max(r, (Integer) v[i]) : Math.min(r, (Integer) v[i]);
                    }
                    yield r;
                }
                float r = num(v[0]);
                for (int i = 1; i < n; i++) {
                    r = max ? Math.max(r, num(v[i])) : Math.min(r, num(v[i]));
                }
                yield r;
            }
            case "clamp" -> {
                if (n != 3) {
                    throw new FormulaException("clamp() takes 3 arguments");
                }
                yield allInts ? (Object) Math.clamp((Integer) v[0], (Integer) v[1], (Integer) v[2])
                        : (Object) Math.max(num(v[1]), Math.min(num(v[2]), num(v[0])));
            }
            case "sqrt" -> (float) Math.sqrt(num(one(v, c)));
            case "sin" -> (float) Math.sin(num(one(v, c)));
            case "cos" -> (float) Math.cos(num(one(v, c)));
            case "exp" -> (float) Math.exp(num(one(v, c)));
            case "log" -> (float) Math.log(num(one(v, c)));
            case "signum" -> Math.signum(num(one(v, c)));
            case "floor" -> (float) Math.floor(num(one(v, c)));
            case "ceil" -> (float) Math.ceil(num(one(v, c)));
            case "pow" -> {
                if (n != 2) {
                    throw new FormulaException("pow() takes 2 arguments");
                }
                yield (float) Math.pow(num(v[0]), num(v[1]));
            }
            // The rest of Iris's scalar functions (net.irisshaders.iris.parsing.IrisFunctions; see NOTICE).
            case "frac" -> {
                float x = num(one(v, c));
                yield (float) (x - Math.floor(x));
            }
            case "sign" -> Math.signum(num(one(v, c)));
            case "tan" -> (float) Math.tan(num(one(v, c)));
            case "asin" -> (float) Math.asin(num(one(v, c)));
            case "acos" -> (float) Math.acos(num(one(v, c)));
            case "atan" -> n == 2 ? (float) Math.atan2(num(v[0]), num(v[1])) : (float) Math.atan(num(one(v, c)));
            case "atan2" -> (float) Math.atan2(num(two(v, c)[0]), num(v[1]));
            case "torad", "radians" -> (float) Math.toRadians(num(one(v, c)));
            case "todeg", "degrees" -> (float) Math.toDegrees(num(one(v, c)));
            case "exp2" -> (float) Math.pow(2, num(one(v, c)));
            case "exp10" -> (float) Math.pow(10, num(one(v, c)));
            case "log2" -> (float) (Math.log(num(one(v, c))) / Math.log(2));
            case "log10" -> (float) Math.log10(num(one(v, c)));
            case "fmod" -> {
                two(v, c);
                if (allInts) {
                    int b = (Integer) v[1];
                    yield b == 0 ? 0 : Math.floorMod((Integer) v[0], b);
                }
                float a = num(v[0]);
                float b = num(v[1]);
                yield (a % b + b) % b;
            }
            case "remainder" -> {
                two(v, c);
                if (allInts) {
                    int b = (Integer) v[1];
                    yield b == 0 ? 0 : (Integer) v[0] % b;
                }
                yield num(v[0]) % num(v[1]);
            }
            case "mix" -> {
                if (n != 3) {
                    throw new FormulaException("mix() takes 3 arguments");
                }
                float x = num(v[0]);
                yield x + (num(v[1]) - x) * num(v[2]);
            }
            case "edge" -> allInts ? (Object) ((Integer) two(v, c)[1] < (Integer) v[0] ? 0 : 1)
                    : (Object) (num(two(v, c)[1]) < num(v[0]) ? 0f : 1f);
            case "between" -> {
                if (n != 3) {
                    throw new FormulaException("between() takes 3 arguments");
                }
                float x = num(v[0]);
                yield x >= num(v[1]) && x <= num(v[2]);
            }
            case "equals" -> {
                if (n != 3) {
                    throw new FormulaException("equals() takes 3 arguments (a, b, epsilon)");
                }
                yield Math.abs(num(v[0]) - num(v[1])) <= num(v[2]);
            }
            default -> throw new FormulaException("Unknown function " + c.name() + "()");
        };
    }

    private static Object one(Object[] v, Call c) throws FormulaException {
        if (v.length != 1) {
            throw new FormulaException(c.name() + "() takes 1 argument");
        }
        return v[0];
    }

    private static Object[] two(Object[] v, Call c) throws FormulaException {
        if (v.length != 2) {
            throw new FormulaException(c.name() + "() takes 2 arguments");
        }
        return v;
    }

    private static float num(Object v) throws FormulaException {
        if (v instanceof Integer i) {
            return i;
        }
        if (v instanceof Float f) {
            return f;
        }
        if (v instanceof Boolean b) {
            return b ? 1f : 0f;
        }
        throw new FormulaException("A vector can't be used as a number; use .x, .y, ...");
    }

    private static boolean truth(Object v) throws FormulaException {
        return v instanceof Boolean b ? b : num(v) != 0f;
    }

    /**
     * Exponential smoothing as Iris's SmoothFloat does it, quirks included: fade times are
     * multiplied by 0.1 before use, the first value is taken as is, and a fade time of 0 means
     * "jump straight to the target".
     */
    static final class SmoothFloat {
        private static final double LN_OF_2 = Math.log(2.0);
        private float accumulator;
        private boolean hasInitialValue;
        private float cachedHalfLifeUp = Float.NaN;
        private float cachedDecayUp;
        private float cachedHalfLifeDown = Float.NaN;
        private float cachedDecayDown;

        float updateAndGet(float value, float halfLifeUp, float halfLifeDown, float frameTime) {
            if (halfLifeUp != cachedHalfLifeUp) {
                cachedHalfLifeUp = halfLifeUp;
                cachedDecayUp = halfLifeUp == 0f ? 0f : decay(halfLifeUp * 0.1f);
            }
            if (halfLifeDown != cachedHalfLifeDown) {
                cachedHalfLifeDown = halfLifeDown;
                cachedDecayDown = halfLifeDown == 0f ? 0f : decay(halfLifeDown * 0.1f);
            }
            if (!hasInitialValue) {
                accumulator = value;
                hasInitialValue = true;
                return accumulator;
            }
            float k = value > accumulator ? cachedDecayUp : cachedDecayDown;
            if (k == 0f) {
                accumulator = value;
                return accumulator;
            }
            float factor = 1f - (float) Math.exp(-k * frameTime);
            accumulator = (1 - factor) * accumulator + factor * value;
            return accumulator;
        }

        private static float decay(float halfLife) {
            return (float) (1.0f / (halfLife / LN_OF_2));
        }
    }
}
