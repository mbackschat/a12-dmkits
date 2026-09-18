package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * A lookup BY PATH goes through {@code declarationAt}, never through a scan of every declaration.
 *
 * <p><b>The defect class this closes, and why a guard rather than a review.</b> {@code allDeclarations()}
 * hands back every declaration the model holds, and the families are dispatched PER DECLARATION — so a
 * {@code firstOrNull} over that list, asked once per declaration, is quadratic in declarations. Five such
 * frames were found by a CPU profile on 2026-09-18 and closed by three memoized indexes; a source sweep the
 * same day then found <b>29 more of the identical shape</b> that the profile had not named, because a profile
 * ranks frames by self time and this cost is spread thin across 63 call sites. That is the whole argument for
 * scanning: the class is invisible to the instrument that found its first members, and a new family writing
 * the natural-looking idiom re-opens it silently.
 *
 * <p><b>Why the rewrite is exactly equivalent, which is what makes a guard fair.</b> {@code declarationAt}
 * reads {@code cursorsByPath}, built over the same {@code cursors} list with {@code getOrPut} — so FIRST
 * occurrence wins, which is precisely what {@code firstOrNull} returns. Declaration paths CAN collide (a
 * model declaring two members of one parent under one name draws {@code CK_MODEL_NOT_BUILDABLE}, and the
 * checker keeps judging it), and the two forms agree even there — locked by
 * {@code DeclarationAtContractTest}, because the first-wins rule is a choice a later refactor could reverse
 * without any test noticing, as a cold review proved by mutation.
 *
 * <p><b>What it deliberately does NOT flag: a COMPOUND predicate.</b>
 * {@code firstOrNull } with {@code it.path == p && it.declaredKind == FIELD} is <i>not</i>
 * {@code declarationAt(p)} filtered by kind — on a collided path whose first member is a group and second a
 * field, the scan finds the field while the lookup finds the group and rejects it. Those sites are a separate
 * question, and this guard holds the line only where equivalence is provable. Flagging them would push a
 * session into a rewrite that changes verdicts.
 *
 * <p><b>Nor does it flag a scan of the UNTRACKED element list.</b> {@code declarationAt} is a member of the
 * read barrier, so it is not available to code holding a {@code PreparedModel} — {@code CheckDraft} scans
 * {@code elements} by path legitimately. The subject here is the barrier's accessor, which is why a match
 * must have {@code allDeclarations()} in the same statement.
 *
 * <p><b>Three spellings a cold review defeated the first version with, all now covered.</b> It matched only
 * an implicit {@code it}, only {@code it.path} on the left, and only a scan textually adjacent to the
 * accessor — so a named lambda parameter, reversed operands, and an interposed {@code let} each re-opened the
 * class with the guard green. The named-parameter form is not contrived: four predicates in the checker
 * already spell {@code candidate ->}. The first version also matched the shape inside a KDoc, which matters
 * because this repository documents the rejected idiom next to the accessor it belongs to; comments are
 * blanked through {@link SourceText#uncommented(Path)} before anything is matched.
 */
class CheckerPathLookupGuardTest {

    /**
     * The checker's source, walked across EVERY production source set rather than {@code commonMain} alone.
     *
     * A cold review pointed out that {@code jsMain} sits in the same package and was outside the scan. It
     * was near-empty then, and the choice was still to walk everything rather than leave a note saying the
     * hole was empty, "which would have to be re-checked to stay true".
     *
     * <p><b>That reasoning has since been vindicated rather than merely preserved.</b> {@code jsMain} grew
     * the draft, impact and snapshot surfaces on 2026-09-18, so a scan scoped to {@code commonMain} plus a
     * note would now be silently wrong about the source set carrying the most recently added public code.
     */
    private static final Path CHECKER_SOURCE = Path.of("consistency/src");

    /** The barrier accessor whose result must not be searched by path. */
    private static final String ACCESSOR = "allDeclarations()";

    /**
     * Every operator that answers "the one where …" — not just the two the first version knew. A scan spelled
     * with any of them is the same cost and the same rewrite.
     */
    private static final Pattern SCANNING_CALL = Pattern.compile(
            "\\.\\s*(?:firstOrNull|find|first|single|singleOrNull|last|lastOrNull|indexOfFirst)\\s*\\{");

    /** {@code name ->}, when the lambda names its parameter instead of taking {@code it}. */
    private static final Pattern NAMED_PARAMETER = Pattern.compile("^([A-Za-z_]\\w*)\\s*->\\s*(.*)$");

    /**
     * How far back the accessor may sit for the scan to count as searching ITS result.
     *
     * A bounded window rather than a parse: the scan can be inside a lambda whose receiver is the accessor
     * ({@code allDeclarations().let { all -> all.firstOrNull { … } }}), which no backward walk of the
     * receiver chain finds, because the scan's own receiver is a local. Both directions are controlled below
     * — the interposed form must be caught, and a scan of the untracked {@code elements} must be spared.
     */
    private static final int STATEMENT_WINDOW = 240;

    /** Below this the scan is not reading the checker, whatever it reports. */
    private static final int SOURCE_FLOOR = 100;

    /**
     * The barrier's set-and-lookup accessors, counted TOGETHER as this guard's tripwire.
     *
     * <p><b>Why the family and not {@code allDeclarations()} alone — a floor must not shrink as the guard
     * succeeds.</b> A scan that saw no call site is a scan of the wrong tree and would report clean, so the
     * tripwire is real; but keyed to the rejected accessor it counts down as sites are repaired, and it
     * failed this guard twice in one day for that reason (20 against a live 34, then 10 against a live 19,
     * as eleven repeatability questions and fifteen kind-qualified lookups moved off it — five remain).
     * Editing the constant each time would make it track the corpus, which is exactly what its own note
     * forbids. These three accessors are CONSERVED under the rewrite: a repaired site leaves the first and
     * arrives at one of the others, so the sum answers "did the scan reach the families" and stays put.
     */
    private static final List<String> ACCESSOR_FAMILY =
            List.of("allDeclarations()", "declarationAt(", "repeatableGroups()");

    /**
     * Below this the scan is not reading the accessors' CALL sites, which is the subject rather than the
     * tree. A declaring line is deliberately not one of them, because a walk that reached only the file
     * declaring them would otherwise report clean.
     *
     * <p>The live count is <b>67 call sites of the family in the production source sets with comments
     * blanked</b> (2026-09-18) — the quantity this floor governs, and not what a plain {@code grep} returns,
     * which also counts {@code commonTest} and prose. Deliberately well under it: it answers "did the scan
     * happen", never "how big is the corpus".
     */
    private static final int CALL_SITE_FLOOR = 40;

    /**
     * Every operator that takes a predicate over the whole result — wider than {@link #SCANNING_CALL},
     * because a repeatability question is spelled with {@code filter}, {@code count} or {@code none} as
     * readily as with {@code any}, and all of them cost the same walk.
     */
    private static final Pattern PREDICATE_CALL = Pattern.compile(
            "\\.\\s*(?:filter|filterNot|filterIndexed|any|none|all|count|partition|sumOf|maxByOrNull"
                    + "|minByOrNull|firstOrNull|find|first|single|singleOrNull|last|lastOrNull"
                    + "|indexOfFirst)\\s*\\{");

    /**
     * The operators for which a lookup is equivalent — "the one where", and existence.
     *
     * Narrower than {@link #PREDICATE_CALL} on purpose: see {@link #kindQualifiedScans}.
     */
    private static final Pattern SINGLE_ANSWER_CALL = Pattern.compile(
            "\\.\\s*(?:firstOrNull|find|first|single|singleOrNull|any|none)\\s*\\{");

    /** The body member whose whole-model answer is a charged fact of its own. */
    private static final String REPEATABILITY_MEMBER = "isRepeatableGroup";

    /** The accessor that answers it from the memoized view, charging one model-wide fact. */
    private static final String REPEATABILITY_ACCESSOR = "repeatableGroups()";

    @Test
    void aLookupByPathUsesTheMemoizedIndexRatherThanScanningEveryDeclaration() {
        var sources = checkerSources();
        assertTrue(
                sources.size() >= SOURCE_FLOOR,
                "read only " + sources.size() + " checker sources from " + CHECKER_SOURCE.toAbsolutePath()
                        + ", so this scan proves nothing — a floor refuses an empty read that reports clean");

        var offenders = new ArrayList<String>();
        var callSites = 0;
        for (var source : sources) {
            var text = uncommented(source);
            callSites += callSitesIn(text);
            for (var found : barePathScans(text)) {
                offenders.add(source.getFileName() + ":" + lineOf(text, found.at()) + "  { "
                        + found.predicate() + " }");
            }
        }

        // Independent of the offender list, and about the SUBJECT rather than the tree: a scan that saw no
        // call site is a scan of the wrong thing, and it would report clean. Counting the declaration would
        // defeat the point, so it is excluded.
        assertTrue(
                callSites >= CALL_SITE_FLOOR,
                "found only " + callSites + " call sites of " + ACCESSOR_FAMILY + " outside their own "
                        + "declarations, so this did not read the families it exists to scan");

        assertEquals(
                List.of(),
                offenders,
                offenders.size() + " lookup(s) by path scan every declaration instead of using the memoized "
                        + "index. Replace `" + ACCESSOR + ".firstOrNull { it.path == X }` with "
                        + "`declarationAt(X)` — same answer on every input including a collided path, same "
                        + "charge, and a map lookup rather than a scan the dispatcher repeats once per "
                        + "declaration:\n  " + String.join("\n  ", offenders));
    }

    /**
     * The reader itself, on fabricated text — because a reader that finds nothing reports every tree clean,
     * and that failure is indistinguishable from success on the live sources.
     *
     * <p>Every row below is a spelling that was live at some point in this guard's own history, or a shape it
     * must spare. The three it once missed and the two false positives it once produced are marked.
     */
    @Test
    void theReaderFindsEverySpellingOfTheBareShapeAndSparesTheRest() {
        // The canonical form.
        assertTrue(bareScanIn("val t = allDeclarations().firstOrNull { it.path == target }"));
        assertTrue(bareScanIn("allDeclarations().firstOrNull { it.path == targets.first() }"),
                "a call-expression operand is the same bare shape");
        assertTrue(bareScanIn("allDeclarations().firstOrNull {\n        it.path == wrapped\n    }"),
                "a wrapped lambda is the same shape");
        assertTrue(bareScanIn("allDeclarations().firstOrNull { it.path == \"${group.path}/$name\" }"),
                "a template operand on a genuinely bare predicate must still be caught");

        // MISSED by the first version — a cold review's three surviving mutants.
        assertTrue(bareScanIn("allDeclarations().firstOrNull { candidate -> candidate.path == path }"),
                "a named lambda parameter is the same shape, and four checker predicates already spell it");
        assertTrue(bareScanIn("allDeclarations().firstOrNull { path == it.path }"),
                "reversed equality operands are the same shape");
        assertTrue(bareScanIn("allDeclarations().let { all -> all.firstOrNull { it.path == path } }"),
                "an interposed let still searches the accessor's result");
        assertTrue(bareScanIn("allDeclarations().single { it.path == path }"),
                "the operator set must cover more than firstOrNull and find");

        // Must be SPARED by THIS reader — the kind-qualified one below owns them.
        assertFalse(bareScanIn("allDeclarations().firstOrNull { it.path == p && it.declaredKind == FIELD }"),
                "a kind-qualified predicate is not the one-argument lookup's shape; it belongs to the "
                        + "two-argument reader, which reports a different rewrite");
        assertFalse(
                bareScanIn("allDeclarations().firstOrNull { it.path == \"${group.path}/$name\""
                        + " && it.declaredKind == FIELD }"),
                "a string-template operand closed the FIRST version's character class early, turning a "
                        + "compound predicate into a reported bare one");
        assertFalse(bareScanIn("val element = elements.firstOrNull { it.path == at }"),
                "a scan of the UNTRACKED element list has no declarationAt to reach — CheckDraft does this");
        assertFalse(bareScanIn("allDeclarations().firstOrNull { it.path.startsWith(prefix) }"),
                "a prefix test is not a lookup by path");
        assertFalse(bareScanIn("declarationAt(target)"),
                "the replacement must not match, or the guard could never go green");
    }

    /** Comments are blanked before matching, so prose about the idiom is not a call site. */
    @Test
    void theShapeInsideACommentIsNotACallSite() {
        var prose = "/** Never allDeclarations().firstOrNull { it.path == p } here. */\nval x = 1\n";

        // The positive half first, or the row below passes on a reader that finds nothing anywhere.
        assertTrue(bareScanIn(prose), "the raw text does contain the shape, or this row proves nothing");

        assertEquals(
                List.of(),
                barePathScans(SourceText.uncommentedFragment(prose, true)).stream().map(Found::predicate)
                        .toList(),
                "blanking comments must leave nothing to flag, because this repository documents the "
                        + "rejected idiom next to the accessor it belongs to");
    }

    /**
     * A repeatability question reads the fact that answers it, not every declaration's body.
     *
     * <p><b>This is a COST guard, and deliberately not a soundness one.</b> The rejected idiom
     * {@code allDeclarations().filter { it.isRepeatableGroup && … }} answers "which groups repeat" by
     * touching every declaration's body JSON, where {@code repeatableGroups()} answers it from a memoized
     * list — O(repeatable) with no body access — and charges the single model-wide fact designed for it.
     *
     * <p><b>What a green here does NOT mean.</b> Moving the eleven live call sites changed the transcript by
     * exactly nothing: charge sets were measured identical before and after, 199 {@code DeclarationContent}
     * charges either way across 38 model-wide producers on a seven-declaration fixture (2026-09-18). A
     * model-wide family already charges every body through {@code declaredReferences()}, which is the
     * design's own documented position rather than a defect, so reuse precision at that granularity comes
     * from keying a cell per declaration and never from this rewrite. Read this guard as "the walk is
     * gone", never as "the cell narrowed".
     */
    @Test
    void aRepeatabilityQuestionReadsTheChargedFactRatherThanEveryBody() {
        var offenders = checkerSources().stream()
                .filter(source -> readsRepeatabilityFromTheSet(uncommented(source)))
                .map(Path::toString)
                .sorted()
                .toList();

        assertEquals(
                List.of(),
                offenders,
                "these sources answer a repeatability question by scanning " + ACCESSOR + " and reading "
                        + REPEATABILITY_MEMBER + " on every declaration; " + REPEATABILITY_ACCESSOR
                        + " answers it from the memoized view with no body access");
    }

    @Test
    void theRepeatabilityReaderCoversTheCheckersSpellingsAndSparesTheRest() {
        // The four shapes the eleven migrated call sites actually used.
        assertTrue(readsRepeatabilityFromTheSet(
                "allDeclarations().filter { it.isRepeatableGroup && path.startsWith(\"${it.path}/\") }"));
        assertTrue(
                readsRepeatabilityFromTheSet(
                        "allDeclarations().any { c -> c.isRepeatableGroup && k.startsWith(\"${c.path}/\") }"),
                "a named lambda parameter and any() are the same scan");
        assertTrue(readsRepeatabilityFromTheSet("allDeclarations().count {\n  it.isRepeatableGroup\n }"),
                "count over a wrapped lambda is the same scan");
        assertTrue(
                readsRepeatabilityFromTheSet(
                        "allDeclarations().firstOrNull { it.path == p && it.isRepeatableGroup }"),
                "the conjunct may sit on either side of the predicate");

        // Must be SPARED.
        assertFalse(
                readsRepeatabilityFromTheSet(
                        "repeatableGroups().filter { path.startsWith(\"${it.path}/\") }"),
                "the replacement must not match, or the guard could never go green");
        assertFalse(readsRepeatabilityFromTheSet("elements.filter { it.isRepeatableGroup }"),
                "Preparation computes the view itself from the UNTRACKED element list");
        assertFalse(
                readsRepeatabilityFromTheSet(
                        "cursors.filterIndexed { i, _ -> model.elements[i].isRepeatableGroup }"),
                "the view's own computation reads the untracked model on purpose — that is the fix, not "
                        + "the idiom, and it must survive an operator set wide enough to include it");
        assertFalse(readsRepeatabilityFromTheSet("allDeclarations().filter { it.declaredKind == FIELD }"),
                "a whole-set scan that never asks about repeatability is a different question");
    }

    /**
     * A kind-qualified lookup uses the two-argument index rather than scanning every declaration.
     *
     * <p>Fifteen gates asked {@code it.path == X && it.declaredKind == K} over the whole set, once per
     * operand. The shape was recorded here as deliberately SPARED, on the reasoning that a compound
     * predicate has no equivalent lookup — true only while the sole index was keyed by path, which made the
     * collided-path behaviour look like a specification question about what a gate should report. Keyed by
     * (path, kind) the question does not arise: every gate keeps the answer its scan gave, including on the
     * twinned paths this checker exists to diagnose.
     */
    @Test
    void aKindQualifiedLookupUsesTheTwoArgumentIndexRatherThanScanning() {
        var offenders = checkerSources().stream()
                .flatMap(source -> kindQualifiedScans(uncommented(source)).stream()
                        .map(found -> source + ": " + found.predicate()))
                .sorted()
                .toList();

        assertEquals(
                List.of(),
                offenders,
                "these scans answer a kind-qualified lookup by walking every declaration; "
                        + "declarationAt(path, kind) answers it from the second index, with the same "
                        + "first-wins rule and the same charge");
    }

    @Test
    void theKindQualifiedReaderCoversTheGatesSpellingsAndSparesTheRest() {
        // The spellings the fifteen migrated gates used.
        assertTrue(kindScanIn(
                "allDeclarations().firstOrNull { it.path == target && it.declaredKind == DeclaredKind.FIELD }"));
        assertTrue(kindScanIn("allDeclarations().any { it.path == path && it.declaredKind == kind }"),
                "existence through any(), and a kind held in a variable, are the same lookup");
        assertTrue(
                kindScanIn("allDeclarations().firstOrNull { it.path == \"${group.path}/$short\""
                        + " && it.declaredKind == DeclaredKind.FIELD }"),
                "a string-template path operand is the same lookup — and this is the shape whose inner "
                        + "brace once closed a character class early");
        assertTrue(
                kindScanIn("allDeclarations().firstOrNull { c -> c.path == p && c.declaredKind == FIELD }"),
                "a named lambda parameter must be followed through both conjuncts, not just the first");

        // Must be SPARED.
        assertFalse(kindScanIn("declarationAt(target, DeclaredKind.FIELD)"),
                "the replacement must not match, or the guard could never go green");
        assertFalse(
                kindScanIn("allDeclarations().filter { it.path == p && it.declaredKind == FIELD }"),
                "filter is NOT equivalent: two declarations can share a path and a kind, so the scan can "
                        + "return two where the index returns the first");
        assertFalse(
                kindScanIn("allDeclarations().firstOrNull { it.path == p && it.declaredKind == FIELD "
                        + "&& it.origin == here }"),
                "a THIRD conjunct is where equivalence ends again, exactly as it does for the bare shape");
        assertFalse(kindScanIn("allDeclarations().firstOrNull { it.path == p }"),
                "the bare shape belongs to the other reader, which reports the other rewrite");
    }

    /** Whether [text] answers a repeatability question by scanning the accessor's whole result. */
    private static boolean readsRepeatabilityFromTheSet(String text) {
        var matcher = PREDICATE_CALL.matcher(text);
        while (matcher.find()) {
            int open = matcher.end() - 1;
            int close = closingBrace(text, open);
            if (close > open
                    && searchesTheAccessor(text, matcher.start())
                    && text.substring(open + 1, close).contains(REPEATABILITY_MEMBER)) {
                return true;
            }
        }
        return false;
    }

    private static boolean bareScanIn(String source) {
        return !barePathScans(source).isEmpty();
    }

    private static boolean kindScanIn(String source) {
        return !kindQualifiedScans(source).isEmpty();
    }

    /** One offending scan: where it starts, and its whole predicate on one line. */
    private record Found(int at, String predicate) {}

    private static List<Found> barePathScans(String text) {
        return scansOf(text, SCANNING_CALL, CheckerPathLookupGuardTest::isBarePathEquality);
    }

    /**
     * Kind-qualified scans, over the SINGLE-ANSWER operators only.
     *
     * {@code filter} and {@code count} are deliberately excluded although {@link #PREDICATE_CALL} knows
     * them: two declarations can share a path AND a kind, so a filter over that predicate can return two
     * where the index returns the first. The rewrite is equivalent for "the one where" and for existence,
     * and not for "all of them".
     */
    private static List<Found> kindQualifiedScans(String text) {
        return scansOf(text, SINGLE_ANSWER_CALL, CheckerPathLookupGuardTest::isKindQualifiedPathEquality);
    }

    private static List<Found> scansOf(String text, Pattern call, Predicate<String> offends) {
        var found = new ArrayList<Found>();
        var matcher = call.matcher(text);
        while (matcher.find()) {
            int open = matcher.end() - 1;
            int close = closingBrace(text, open);
            if (close <= open || !searchesTheAccessor(text, matcher.start())) {
                continue;
            }
            var predicate = text.substring(open + 1, close).trim().replaceAll("\\s+", " ");
            if (offends.test(predicate)) {
                found.add(new Found(matcher.start(), predicate));
            }
        }
        return found;
    }

    /** Whether the accessor sits close enough before [at] for this scan to be searching its result. */
    private static boolean searchesTheAccessor(String text, int at) {
        return text.substring(Math.max(0, at - STATEMENT_WINDOW), at).contains(ACCESSOR);
    }

    /**
     * The brace that closes the one at [open], counting nesting — which is what makes a {@code ${…}} template
     * inside the predicate transparent instead of terminating it.
     */
    private static int closingBrace(String text, int open) {
        int depth = 0;
        for (int index = open; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    /**
     * A path equality and nothing else, whichever side the path sits on and whatever the parameter is called.
     *
     * A second conjunct is where equivalence ends, so {@code &&} and {@code ||} disqualify outright rather
     * than being parsed.
     */
    private static boolean isBarePathEquality(String predicate) {
        if (predicate.contains("&&") || predicate.contains("||")) {
            return false;
        }
        var parameter = "it";
        var body = predicate;
        Matcher named = NAMED_PARAMETER.matcher(predicate);
        if (named.matches()) {
            parameter = named.group(1);
            body = named.group(2);
        }
        var path = parameter + ".path";
        int equals = body.indexOf("==");
        if (equals < 0) {
            return false;
        }
        var left = body.substring(0, equals).trim();
        var right = body.substring(equals + 2).trim();
        return (left.equals(path) && !right.isEmpty()) || (right.equals(path) && !left.isEmpty());
    }

    /**
     * A path equality qualified by declared KIND and nothing else — the two-argument lookup's shape.
     *
     * Split off from {@link #isBarePathEquality} rather than folded into it, because the two idioms have
     * different replacements: a bare path equality becomes {@code declarationAt(path)} and this becomes
     * {@code declarationAt(path, kind)}. Keying by path alone cannot answer this one, which is why the
     * second index exists.
     */
    private static boolean isKindQualifiedPathEquality(String predicate) {
        var parameter = "it";
        var body = predicate;
        Matcher named = NAMED_PARAMETER.matcher(predicate);
        if (named.matches()) {
            parameter = named.group(1);
            body = named.group(2);
        }
        var conjunction = " && " + parameter + ".declaredKind == ";
        int split = body.lastIndexOf(conjunction);
        if (split < 0) {
            return false;
        }
        var kind = body.substring(split + conjunction.length()).trim();
        if (kind.isEmpty() || kind.contains("&&") || kind.contains("||")) {
            return false;
        }
        var left = body.substring(0, split).trim();
        return isBarePathEquality(parameter.equals("it") ? left : parameter + " -> " + left);
    }

    /** Call sites of the whole {@link #ACCESSOR_FAMILY}, excluding the lines that declare them. */
    private static int callSitesIn(String text) {
        var sites = 0;
        for (var accessor : ACCESSOR_FAMILY) {
            int from = 0;
            while (true) {
                int at = text.indexOf(accessor, from);
                if (at < 0) {
                    break;
                }
                int lineStart = text.lastIndexOf('\n', at) + 1;
                if (!text.substring(lineStart, at).contains("fun ")) {
                    sites++;
                }
                from = at + accessor.length();
            }
        }
        return sites;
    }

    /**
     * A {@code *Main} source set — production families, not the tests that legitimately discuss the idiom.
     *
     * Scoping by source set rather than by excluding {@code commonTest} by name: a source set added later is
     * then covered or excluded by what it IS, without anyone remembering to extend a list.
     */
    private static boolean isProductionSourceSet(Path source) {
        for (Path segment : source) {
            if (segment.toString().endsWith("Main")) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> checkerSources() {
        try (Stream<Path> tree = Files.walk(CHECKER_SOURCE)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".kt"))
                    .filter(CheckerPathLookupGuardTest::isProductionSourceSet)
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read the checker sources under " + CHECKER_SOURCE, failure);
        }
    }

    private static String uncommented(Path source) {
        try {
            return SourceText.uncommented(source);
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read " + source, failure);
        }
    }

    private static int lineOf(String text, int offset) {
        return (int) text.substring(0, offset).chars().filter(character -> character == '\n').count() + 1;
    }
}
