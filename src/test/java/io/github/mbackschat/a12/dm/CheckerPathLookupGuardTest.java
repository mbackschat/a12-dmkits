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
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * A lookup BY PATH goes through {@code declarationAt}, never through a scan of every declaration.
 *
 * <p><b>The defect class this closes, and why a guard rather than a review.</b> {@code allDeclarations()}
 * hands back every declaration the model holds, and the families are dispatched PER DECLARATION — so a
 * {@code firstOrNull} over that list, asked once per declaration, is quadratic in declarations. Three such
 * sites were found by a CPU profile on 2026-09-18 and closed by memoized indexes; a source sweep the same day
 * then found <b>29 more of the identical shape</b> that the profile had not named, because a profile names
 * only its hottest frames and this cost is spread thin across 63 call sites. That is the whole argument for
 * scanning: the class is invisible to the instrument that found its first members, and a new family writing
 * the natural-looking idiom re-opens it silently.
 *
 * <p><b>Why the rewrite is exactly equivalent, which is what makes a guard fair.</b> {@code declarationAt}
 * reads {@code cursorsByPath}, built over the same {@code cursors} list with {@code getOrPut} — so FIRST
 * occurrence wins, which is precisely what {@code firstOrNull} returns. Declaration paths CAN collide (a
 * model declaring two members of one parent under one name draws {@code CK_MODEL_NOT_BUILDABLE}, and the
 * checker keeps judging it), and the two forms agree even there. Both accessors charge
 * {@code FactRead.DeclarationSetShape}, so no cell key moves either.
 *
 * <p><b>What it deliberately does NOT flag: a COMPOUND predicate.</b>
 * {@code firstOrNull } with {@code it.path == p && it.declaredKind == FIELD} is <i>not</i>
 * {@code declarationAt(p)} filtered by kind — on a collided path whose first member is a group and second a
 * field, the scan finds the field while the lookup finds the group and rejects it. Those sites are a separate
 * question with a reachability answer owed first, so this guard holds the line only where equivalence is
 * provable. Flagging them would push a session into a rewrite that changes verdicts.
 *
 * <p><b>Why the lambda is brace-balanced in code instead of matched by one regex.</b> The first version of
 * this guard used a character class to end the predicate, and a Kotlin string-template operand
 * ({@code it.path == } a template interpolating a path) closed it early — so a compound predicate was
 * reported as a bare one, which is precisely the false positive that would drive the verdict-changing
 * rewrite. Balancing the braces cannot make that mistake, and the controls below now include a template
 * operand because the ones that passed while the regex was wrong did not.
 */
class CheckerPathLookupGuardTest {

    private static final Path CHECKER = Path.of(
            "consistency/src/commonMain/kotlin/io/github/mbackschat/a12/dm/consistency");

    /** The call whose lambda is then read by balancing braces rather than by a character class. */
    private static final Pattern SCANNING_CALL = Pattern.compile(
            "allDeclarations\\(\\)\\s*\\.\\s*(?:firstOrNull|find)\\s*\\{");

    /** Below this the scan is not reading the checker, whatever it reports. */
    private static final int SOURCE_FLOOR = 100;

    @Test
    void aLookupByPathUsesTheMemoizedIndexRatherThanScanningEveryDeclaration() {
        var sources = checkerSources();
        assertTrue(
                sources.size() >= SOURCE_FLOOR,
                "read only " + sources.size() + " checker sources from " + CHECKER.toAbsolutePath()
                        + ", so this scan proves nothing — a floor refuses an empty read that reports clean");

        var offenders = new ArrayList<String>();
        var sawTheAccessor = false;
        for (var source : sources) {
            var text = read(source);
            if (text.contains("allDeclarations()")) {
                sawTheAccessor = true;
            }
            for (var body : scanningLambdas(text)) {
                if (isBarePathEquality(body.predicate())) {
                    offenders.add(source.getFileName() + ":" + lineOf(text, body.at()) + "  { "
                            + body.predicate() + " }");
                }
            }
        }

        // Independent of the offender list: a scan whose subject token is absent is a scan of the wrong tree,
        // and it would report clean. This is the half the offender assertion cannot check.
        assertTrue(sawTheAccessor, "no checker source mentions allDeclarations(), so this scanned the wrong tree");

        assertEquals(
                List.of(),
                offenders,
                offenders.size() + " lookup(s) by path scan every declaration instead of using the memoized "
                        + "index. Replace `allDeclarations().firstOrNull { it.path == X }` with "
                        + "`declarationAt(X)` — same answer on every input including a collided path, same "
                        + "DeclarationSetShape charge, and a map lookup rather than a scan the dispatcher "
                        + "repeats once per declaration:\n  " + String.join("\n  ", offenders));
    }

    /**
     * The reader itself, on fabricated text — because a reader that finds nothing reports every tree clean,
     * and that failure is indistinguishable from success on the live sources.
     */
    @Test
    void theReaderFindsTheBareShapeAndSparesTheCompoundOne() {
        assertTrue(
                isBareIn("val t = allDeclarations().firstOrNull { it.path == target }"),
                "the reader does not find the shape it exists to find, so a clean report means nothing");
        assertTrue(
                isBareIn("allDeclarations().firstOrNull { it.path == targets.first() }"),
                "a call-expression operand is the same bare shape and must be caught");
        assertTrue(
                isBareIn("allDeclarations().firstOrNull {\n        it.path == wrapped\n    }"),
                "a wrapped lambda is the same shape and must be caught");
        assertFalse(
                isBareIn("allDeclarations().firstOrNull { it.path == p && it.declaredKind == FIELD }"),
                "a compound predicate is NOT equivalent to declarationAt and must not be flagged");
        assertFalse(
                isBareIn("allDeclarations().firstOrNull { it.path == \"${group.path}/$name\""
                        + " && it.declaredKind == FIELD }"),
                "a string-template operand closed the FIRST version of this reader early, turning a compound "
                        + "predicate into a reported bare one — the false positive this control exists for");
        assertTrue(
                isBareIn("allDeclarations().firstOrNull { it.path == \"${group.path}/$name\" }"),
                "a template operand on a genuinely bare predicate must still be caught");
        assertFalse(
                isBareIn("allDeclarations().firstOrNull { it.path.startsWith(prefix) }"),
                "a prefix test is not a lookup by path and has no declarationAt equivalent");
        assertFalse(
                isBareIn("declarationAt(target)"),
                "the replacement must not match, or the guard could never go green");
    }

    private static boolean isBareIn(String source) {
        return scanningLambdas(source).stream().anyMatch(found -> isBarePathEquality(found.predicate()));
    }

    /** One {@code allDeclarations()} search call: where it starts, and its whole predicate on one line. */
    private record ScanningLambda(int at, String predicate) {}

    private static List<ScanningLambda> scanningLambdas(String text) {
        var found = new ArrayList<ScanningLambda>();
        var matcher = SCANNING_CALL.matcher(text);
        while (matcher.find()) {
            int open = matcher.end() - 1;
            int close = closingBrace(text, open);
            if (close > open) {
                found.add(new ScanningLambda(
                        matcher.start(), text.substring(open + 1, close).trim().replaceAll("\\s+", " ")));
            }
        }
        return found;
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

    /** A path equality and nothing else — no second conjunct, which is where equivalence would end. */
    private static boolean isBarePathEquality(String predicate) {
        return predicate.startsWith("it.path ==") && !predicate.contains("&&") && !predicate.contains("||");
    }

    private static List<Path> checkerSources() {
        try (Stream<Path> tree = Files.walk(CHECKER)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".kt"))
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read the checker sources under " + CHECKER, failure);
        }
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read " + source, failure);
        }
    }

    private static int lineOf(String text, int offset) {
        return (int) text.substring(0, offset).chars().filter(character -> character == '\n').count() + 1;
    }
}
