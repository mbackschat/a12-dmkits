package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * A law that hands the kernel a HAND-WRITTEN condition string names which of the two reasons applies.
 *
 * <p><b>The claim this enforces was advertised in three places and enforced in none.</b> {@code KernelLaws},
 * {@code RuntimeLaws} and {@code docs/TESTING-SPEC.md} all state that a raw-String condition is legitimate for
 * exactly two reasons — the typed surface <em>erases</em> the shape under test (a wrong-kind operand the kit
 * refuses because the kernel does, where the row exists to measure that refusal), or the law's subject <em>is</em>
 * concrete syntax with no node (a brace depth, a malformed token, a literal form) — and that the row or the
 * helper building it must say which. Nothing checked it. {@link TypedConditionAuthoringRatchetTest} counts
 * RENDERERS, so a law could pass a hand-written string straight into a typed harness entry point and trip
 * nothing at all. That is the "advertise nothing you cannot point at" class, and this is the locus.
 *
 * <p><b>Why the reason is a closed vocabulary rather than prose.</b> A guard that matched free text would be
 * decoration if loose and would reject honest content if strict — the failure mode that gets guards deleted. So
 * the marker is a javadoc block tag with exactly two admissible values, {@code @rawCondition SHAPE_ERASED} and
 * {@code @rawCondition CONCRETE_SYNTAX}, and {@link #anUnknownReasonIsRejectedRatherThanIgnored} proves a
 * misspelling fails loudly instead of silently counting as unmarked.
 *
 * <p><b>Why a ratchet on call SITES rather than files.</b> The reason is a property of the row, not of the file,
 * so a file may legitimately hold marked and unmarked rows while it is being migrated; counting sites lets one
 * annotated row lower the ceiling. (Its sibling counts files because the renderer it banned was one per file.)
 */
class RawConditionReasonRatchetTest {

    /**
     * Measured 2026-09-22 and exact. It may only FALL — annotate a row and lower it in the same commit.
     *
     * <p>The quantity is CONDITION-SLOT ARGUMENTS in the law trees that do not resolve to a render of a typed
     * node and whose enclosing declaration carries no {@code @rawCondition} tag. 203 further arguments already
     * arrive typed and are not counted, because the whole point is that they need no reason.
     */
    private static final int CEILING = 638;

    /** The two admissible reasons, spelled as the tag's value. A third needs its case made in TESTING-SPEC. */
    private static final Set<String> REASONS = Set.of("SHAPE_ERASED", "CONCRETE_SYNTAX");

    private static final List<String> LAW_TREES = List.of(
            "src/test/java/io/github/mbackschat/a12/dm/rulekit/validate/laws",
            "adapter/src/test/java/io/github/mbackschat/a12/dm/adapter/laws",
            "adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/laws");

    private static final String KERNEL_LAWS =
            "src/test/java/io/github/mbackschat/a12/dm/rulekit/validate/laws/KernelLaws.java";
    private static final String RUNTIME_LAWS =
            "adapter/src/test/java/io/github/mbackschat/a12/dm/adapter/laws/RuntimeLaws.java";

    /** A parameter the harness itself calls a condition — the slot whose argument this guard classifies. */
    private static final Pattern CONDITION_PARAM =
            Pattern.compile("^\\s*(?:final\\s+)?String\\s+(condition|cond|errorCondition|operation|precondition|dsl)\\s*$");

    private static final Pattern HARNESS_METHOD = Pattern.compile(
            "\\b(?:public\\s+|private\\s+)?static\\s+[\\w.<>\\[\\],\\s]+?\\b(\\w+)\\s*\\(([^)]*)\\)", Pattern.DOTALL);

    private static final Pattern HARNESS_CALL =
            Pattern.compile("\\b(?:KernelLaws|RuntimeLaws)\\s*\\.\\s*(\\w+)\\s*\\(");

    /** The typed path: the shared harnesses' own renderers, or a typed object rendering itself. */
    private static final Pattern RENDERED =
            Pattern.compile("(?:KernelLaws|RuntimeLaws)\\s*\\.\\s*render\\s*\\(|\\.\\s*render\\s*\\(\\s*\\)|\\brender\\s*\\(");

    private static final Pattern RAW_CONDITION_TAG = Pattern.compile("@rawCondition\\s+(\\w+)");

    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    // ---- the estate ----

    @Test
    void noLawHandsTheKernelAnUnexplainedHandWrittenCondition() {
        Checkout checkout = Checkout.locate();
        Map<String, Integer> slots = conditionSlots(
                read(checkout.resolve(KERNEL_LAWS)) + "\n" + read(checkout.resolve(RUNTIME_LAWS)));
        assertTrue(slots.size() > 20,
                () -> "expected to resolve the harness condition slots, found " + slots.size()
                        + " — a signature change may have outrun this parse");

        List<String> unexplained = LAW_TREES.stream()
                .flatMap(tree -> checkout.tracked(tree).stream())
                .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt"))
                .flatMap(p -> unexplainedIn(read(p), slots).stream().map(c -> checkout.relative(p) + ": " + c))
                .toList();

        assertTrue(unexplained.size() <= CEILING, () -> "hand-written law conditions without a stated reason rose"
                + " to " + unexplained.size() + ", ceiling " + CEILING + ". Author the condition typed, or mark"
                + " the enclosing declaration with a javadoc tag naming which reason applies —"
                + " @rawCondition SHAPE_ERASED (the typed surface erases the shape the row measures) or"
                + " @rawCondition CONCRETE_SYNTAX (the law's subject is the spelling, and no AST carries it)."
                + " Every unexplained site, so diff this against the ceiling rather than reading it as new:\n  "
                + unexplained.stream().limit(40).collect(Collectors.joining("\n  ")));
    }

    /** The ceiling is a denominator, so it must be the count of a predicate that actually ran. */
    @Test
    void theTypedArgumentsAreNotCountedAgainstTheCeiling() {
        String source = """
                class L {
                    void a() { KernelLaws.acceptsOrder("/Order", "../X", KernelLaws.render(c)); }
                    void b() { KernelLaws.acceptsOrder("/Order", "../X", "FieldFilled(X)"); }
                }
                """;

        assertEquals(List.of("acceptsOrder <- \"FieldFilled(X)\""),
                unexplainedIn(source, Map.of("acceptsOrder/3", 2)),
                "a rendered typed condition needs no reason; only the hand-written one is counted");
    }

    // ---- the classifier, proven on synthetic sources so both verdicts are exercised ----

    @Test
    void aTaggedDeclarationExcusesTheHandWrittenConditionBeneathIt() {
        String source = """
                class L {
                    /** The subject is the brace depth itself. @rawCondition CONCRETE_SYNTAX */
                    @Test
                    void a() { KernelLaws.acceptsOrder("/Order", "../X", "{ [Q] ^ [Q] } ^ [Q] > 1"); }
                }
                """;

        assertEquals(List.of(), unexplainedIn(source, Map.of("acceptsOrder/3", 2)));
    }

    @Test
    void anUntaggedDeclarationDoesNot() {
        String source = """
                class L {
                    /** Nothing here says why this row is a string. */
                    @Test
                    void a() { KernelLaws.acceptsOrder("/Order", "../X", "{ [Q] ^ [Q] } ^ [Q] > 1"); }
                }
                """;

        assertEquals(1, unexplainedIn(source, Map.of("acceptsOrder/3", 2)).size(),
                "an unexplained hand-written condition is exactly what the ceiling counts");
    }

    /**
     * One marked row does not excuse the next — the cheap way this guard could have been hollow.
     *
     * <p>A "nearest preceding tag" rule reads as obviously right and would have let a single annotated method
     * cover every method under it, so the ceiling would fall without a reason ever being stated for those rows.
     */
    @Test
    void aRowsTagDoesNotLeakToTheRowBelowIt() {
        String source = """
                class L {
                    /** @rawCondition CONCRETE_SYNTAX */
                    @Test
                    void a() { KernelLaws.acceptsOrder("/Order", "../X", "{ [Q] } > 1"); }

                    @Test
                    void b() { KernelLaws.acceptsOrder("/Order", "../Y", "FieldFilled(Y)"); }
                }
                """;

        assertEquals(List.of("acceptsOrder <- \"FieldFilled(Y)\""), unexplainedIn(source, Map.of("acceptsOrder/3", 2)),
                "only the row the tag precedes is excused");
    }

    /**
     * A misspelled reason fails loudly instead of passing as "marked".
     *
     * <p>This is the row that keeps the closed vocabulary honest: without it, {@code @rawCondition SYNTAX} would
     * excuse a site while meaning nothing, and the tag would become decoration — the failure mode the design
     * note above names.
     */
    @Test
    void anUnknownReasonIsRejectedRatherThanIgnored() {
        String source = """
                class L {
                    /** @rawCondition SYNTAX */
                    void a() { KernelLaws.acceptsOrder("/Order", "../X", "FieldFilled(X)"); }
                }
                """;

        List<String> found = unexplainedIn(source, Map.of("acceptsOrder/3", 2));

        assertEquals(1, found.size(), "an unknown reason must not excuse the site");
        assertTrue(found.get(0).contains("unknown @rawCondition reason 'SYNTAX'"),
                () -> "the diagnostic must name the bad token and the admissible set, got: " + found);
    }

    /** A tag on the enclosing TYPE covers a file whose whole subject is one of the two reasons. */
    @Test
    void aTagOnTheTypeCoversEveryRowInIt() {
        String source = """
                /** Every row here measures a refusal the typed surface would erase. @rawCondition SHAPE_ERASED */
                class L {
                    void a() { KernelLaws.acceptsOrder("/Order", "../X", "FieldFilled(X)"); }
                    void b() { KernelLaws.acceptsOrder("/Order", "../Y", "FieldFilled(Y)"); }
                }
                """;

        assertEquals(List.of(), unexplainedIn(source, Map.of("acceptsOrder/3", 2)));
    }

    /** The slot map is read off the harness signature, so a renamed parameter cannot silently empty it. */
    @Test
    void theConditionSlotIsResolvedFromTheHarnessSignature() {
        String harness = """
                static void acceptsOrder(String group, String errorField, String condition) { }
                static void withRule(String json, String g, String n, String rel, String code, String condition) { }
                static String render(String group, Condition condition) { return null; }
                """;

        assertEquals(Map.of("acceptsOrder/3", 2, "withRule/6", 5), conditionSlots(harness),
                "only String-typed condition parameters define a slot; the typed render overload defines none");
    }

    // ---- implementation ----

    /** {@code name/arity} → index of the {@code String} parameter the harness itself calls a condition. */
    private static Map<String, Integer> conditionSlots(String harnessSource) {
        Map<String, Integer> slots = new LinkedHashMap<>();
        Matcher m = HARNESS_METHOD.matcher(COMMENTS.matcher(harnessSource).replaceAll(" "));
        while (m.find()) {
            String[] params = m.group(2).isBlank() ? new String[0] : m.group(2).split("\\s*,\\s*");
            for (int i = 0; i < params.length; i++) {
                if (CONDITION_PARAM.matcher(params[i]).matches()) {
                    slots.put(m.group(1) + "/" + params.length, i);
                }
            }
        }
        return slots;
    }

    /**
     * Every condition-slot argument in {@code source} that is hand-written AND carries no admissible reason.
     *
     * <p>Comments are kept, not stripped: the tag lives in one, and the enclosing declaration is found by
     * looking BACKWARDS from the call to the nearest preceding comment block, which is where javadoc sits.
     */
    private static List<String> unexplainedIn(String source, Map<String, Integer> slots) {
        List<String> found = new ArrayList<>();
        String code = COMMENTS.matcher(source).replaceAll(m -> " ".repeat(m.group().length()));
        Matcher call = HARNESS_CALL.matcher(code);
        while (call.find()) {
            int open = call.end();
            int close = matchingParen(code, open);
            if (close < 0) {
                continue;
            }
            List<String> args = splitArguments(code.substring(open, close));
            Integer slot = slots.get(call.group(1) + "/" + args.size());
            if (slot == null) {
                continue;
            }
            String argument = args.get(slot).trim();
            if (!isHandWritten(argument, code)) {
                continue;
            }
            String reason = reasonFor(source, call.start());
            if (reason != null && REASONS.contains(reason)) {
                continue;
            }
            String site = call.group(1) + " <- " + argument;
            found.add(reason == null ? site
                    : site + "  [unknown @rawCondition reason '" + reason + "'; admissible: " + REASONS + "]");
        }
        return found;
    }

    /**
     * Whether the condition reaching this slot was written by hand rather than rendered from a typed node.
     *
     * <p><b>Two resolutions, because a law rarely passes its condition inline.</b> A named argument is followed
     * to its local or field initializer, and a {@code helper(…)} call is followed into the body of that helper
     * in the same file — {@code guardedInvoice(Dsl.sum(…))} builds a typed condition and counting it as
     * hand-written would inflate the ceiling with rows that are already doing the right thing.
     *
     * <p><b>A site counts only when a string LITERAL survives that resolution.</b> Anything else is left
     * uncounted: the guard must never be the reason a correct row fails, and a ratchet that over-counts is a
     * ratchet nobody can lower.
     */
    private static boolean isHandWritten(String argument, String code) {
        String resolved = resolve(argument, code);
        // Helpers delegate to helpers — `guardedInvoice(…)` returns `cond(…)`, which is the one that renders —
        // so a single hop reports a typed row as hand-written. Three rounds reach every chain in the estate.
        for (int round = 0; round < 3 && !RENDERED.matcher(resolved).find(); round++) {
            String expanded = resolved;
            Matcher called = Pattern.compile("\\b(\\w+)\\s*\\(").matcher(resolved);
            while (called.find()) {
                String body = bodyOf(called.group(1), code);
                if (body != null) {
                    expanded = expanded + " " + body;
                }
            }
            if (expanded.equals(resolved)) {
                break;
            }
            resolved = expanded;
        }
        return !RENDERED.matcher(resolved).find() && resolved.contains("\"");
    }

    /** One hop for a name, one for a same-file helper call; the text reached is what gets classified. */
    private static String resolve(String argument, String code) {
        if (argument.matches("\\w+")) {
            Matcher local = Pattern.compile(
                    "\\b(?:final\\s+)?String\\s+" + Pattern.quote(argument) + "\\s*=\\s*([^;]+);").matcher(code);
            if (local.find()) {
                return local.group(1);
            }
            Matcher kotlin = Pattern.compile(
                    "\\bva[lr]\\s+" + Pattern.quote(argument) + "\\b[^=\\n]*=\\s*([^\\n]+)").matcher(code);
            return kotlin.find() ? kotlin.group(1) : argument;
        }
        Matcher call = Pattern.compile("^(\\w+)\\s*\\(").matcher(argument);
        if (call.find()) {
            String body = bodyOf(call.group(1), code);
            if (body != null) {
                return argument + " " + body;
            }
        }
        return argument;
    }

    /** The source text of a same-file method, so a helper that renders is recognised as the typed path. */
    private static String bodyOf(String method, String code) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\([^)]*\\)\\s*(\\{|=)").matcher(code);
        while (m.find()) {
            if (m.group(1).equals("=")) {
                int end = code.indexOf('\n', m.end());
                return code.substring(m.end(), end < 0 ? code.length() : end);
            }
            int close = matchingBrace(code, m.end());
            if (close > 0) {
                return code.substring(m.end(), close);
            }
        }
        return null;
    }

    private static int matchingBrace(String code, int afterOpen) {
        int depth = 1;
        for (int i = afterOpen; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                i = endOfLiteral(code, i);
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The reason governing the call at {@code at}: the file's type-level tag, or the enclosing row's own.
     *
     * <p><b>Scoped two ways, so a tag cannot leak down the file.</b> A tag before the {@code class} keyword is
     * the TYPE's and covers every row; a tag after it covers only the row it precedes, which is exactly "no
     * {@code @Test} between the tag and the call". Without that second clause one marked method would silently
     * excuse every method under it and the ceiling would fall for free.
     *
     * <p>Keying type-level on the {@code class} keyword rather than on the first {@code @Test} is the
     * difference between the two: a method's own javadoc also precedes the first {@code @Test}, so that reading
     * made every first-method tag type-level — which {@link #aRowsTagDoesNotLeakToTheRowBelowIt} caught.
     */
    private static String reasonFor(String source, int at) {
        Matcher type = Pattern.compile("\\b(?:class|object|interface|enum)\\s+\\w").matcher(source);
        int typeStart = type.find() ? type.start() : 0;
        Matcher tag = RAW_CONDITION_TAG.matcher(source);
        String governing = null;
        while (tag.find()) {
            if (tag.start() >= at) {
                break;
            }
            // The tag sits in the javadoc ABOVE its own @Test, so the row it governs runs from that @Test to
            // the NEXT one. Keying on the first @Test after the tag would exclude the very row it belongs to.
            int ownRow = source.indexOf("@Test", tag.end());
            int nextRow = ownRow < 0 ? -1 : source.indexOf("@Test", ownRow + 1);
            if (tag.start() < typeStart || nextRow < 0 || at < nextRow) {
                governing = tag.group(1);
            }
        }
        return governing;
    }

    private static int matchingParen(String code, int afterOpen) {
        int depth = 1;
        for (int i = afterOpen; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                i = endOfLiteral(code, i);
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int endOfLiteral(String code, int start) {
        char quote = code.charAt(start);
        for (int i = start + 1; i < code.length(); i++) {
            if (code.charAt(i) == '\\') {
                i++;
            } else if (code.charAt(i) == quote) {
                return i;
            }
        }
        return code.length() - 1;
    }

    private static List<String> splitArguments(String args) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '"' || c == '\'') {
                int end = endOfLiteral(args, i);
                current.append(args, i, end + 1);
                i = end;
            } else if (c == ',' && depth == 0) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                if (c == '(' || c == '[' || c == '{') {
                    depth++;
                } else if (c == ')' || c == ']' || c == '}') {
                    depth--;
                }
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
