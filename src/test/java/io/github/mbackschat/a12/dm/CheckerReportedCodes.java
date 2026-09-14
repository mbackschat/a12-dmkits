package io.github.mbackschat.a12.dm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which A12 codes the portable checker REPORTS — one live statement, read by every guard that asks it.
 *
 * <p><b>Why this is shared rather than restated.</b> Three guards asked this question with three scans, and
 * two of them were wrong in opposite directions at once: {@code CheckerLedgerDispositionGuardTest} followed
 * only a helper literally named {@code finding(…)} and saw 99 of the 193 codes, while
 * {@code CheckerCodeBasisGuardTest} took every string literal and so counted the four codes that exist in
 * this checker only as SET MEMBERS. Each scan passed — the narrow one by never looking, the coarse one by
 * luck, since every other set member happened to be named in some laws test. A question with one right
 * answer gets one reader (measured 2026-09-15).
 *
 * <p><b>A code with INTERIOR digits is a code.</b> The three scans shared the pattern
 * {@code MVK_[A-Z_]+[0-9]*}, whose KDoc reasoned — correctly, and beside the point — that trailing digits
 * belong to the code because {@code MVK_DUPLICATE_PARAM1} and {@code MVK_DUPLICATE_PARAM2} are two laws.
 * Digits also appear in the MIDDLE, and twelve ledger codes spell them there:
 * {@code MVK_NAME_STARTS_WITH_XML_A12_DV}, {@code MVK_ERROR_FIELDGROUP_ITERATION4_INVALID} and the rest of
 * the {@code _A12_DV} and {@code IN_A12_MODEL} families. All twelve were invisible to all three guards.
 *
 * <p><b>What counts: a code literal the checker STATES, anywhere outside a membership set.</b> Codes reach a
 * {@code Finding} through intermediate defect records in this estate — {@code ListDefect("MVK_…", …)},
 * {@code PartialDateDefect}, a {@code String?} returned up two frames — so narrowing to a {@code Finding}
 * construction misses 86 genuine reports and cannot be repaired by following helper names. The one shape
 * that is NOT a report is a code parked in a declared {@code setOf}: those are the suppression and phase
 * lists ({@code POST_PARSE_CONDITION_CODES} and its two siblings in
 * {@code Check.kt}), which the checker compares against and never emits. Comments are stripped for the same
 * reason — this estate's KDoc routinely names the neighbouring class a family deliberately leaves alone,
 * which is the opposite claim.
 *
 * <p><b>One direction of error remains, and it is the safe one.</b> A literal stated outside a set but never
 * actually emitted reads as reported here. That over-counts, and over-counting only ever demands a truer
 * ledger row or an earlier measurement; the under-count it replaces hid nine stale rows, two of them still
 * saying {@code kernel-delegated} for codes a family had been reporting for two days.
 */
public final class CheckerReportedCodes {

    private static final Checkout CHECKOUT = Checkout.locate();

    private static final String CHECKER_MAIN_SOURCE = "consistency/src/commonMain";

    /** The generated corrective projection restates every code and reports none of them. */
    private static final String GENERATED = "KernelCorrectives.kt";

    /** An A12 code as the checker spells it, digits wherever they fall. */
    private static final Pattern CODE = Pattern.compile("\"(MVK_[A-Z0-9_]+)\"");

    /**
     * The head of a declared code SET — membership the checker compares against, never an answer.
     *
     * <p><b>A {@code setOf} only.</b> A {@code mapOf} keyed by code is the opposite shape: three families
     * hold their explain fragments as {@code code to "…"} and look the key up while building the finding
     * ({@code SemanticIndexStructure.ADVICE} and its two siblings), so every key there is reported. Excluding
     * maps hid four such codes when this was first written.
     *
     * <p><b>The type annotation may not cross a line.</b> With {@code [^=]*} it does, and a constructor
     * parameter — {@code enum class ConstantArm(val code: String, val admitted: Set<String>)} — then scanned
     * on to the next file-level {@code = setOf(} and cut the whole enum out, taking three reported codes with
     * it. That is the same unanchored-span defect this reader exists to correct, found in it before it
     * shipped (2026-09-15).
     */
    private static final Pattern CODE_SET =
            Pattern.compile("val\\s+[A-Za-z_]\\w*\\s*(?::[^=\\n]*)?=\\s*setOf\\s*\\(");

    private CheckerReportedCodes() {
    }

    /** Every code the checker reports, mapped to the sources that state it. */
    public static Map<String, Set<String>> bySource() {
        Map<String, Set<String>> reported = new TreeMap<>();
        for (Path source : checkerSources()) {
            for (String code : codesIn(read(source))) {
                reported.computeIfAbsent(code, key -> new TreeSet<>()).add(CHECKOUT.relative(source));
            }
        }
        return reported;
    }

    /** Every code the checker reports. */
    public static Set<String> all() {
        return bySource().keySet();
    }

    /** The reader itself, over one Kotlin source — the seam {@link CheckerReportedCodesTest} drives. */
    static Set<String> codesIn(String kotlinSource) {
        Set<String> codes = new TreeSet<>();
        Matcher code = CODE.matcher(withoutCodeSets(withoutComments(kotlinSource)));
        while (code.find()) {
            codes.add(code.group(1));
        }
        return codes;
    }

    /** The source with every declared {@code setOf}/{@code listOf}/{@code mapOf} initializer cut out. */
    private static String withoutCodeSets(String text) {
        StringBuilder kept = new StringBuilder();
        Matcher set = CODE_SET.matcher(text);
        int at = 0;
        while (set.find()) {
            if (set.start() < at) {
                continue;
            }
            kept.append(text, at, set.start());
            at = afterBalancingParenthesis(text, set.end());
        }
        return kept.append(text.substring(at)).toString();
    }

    private static int afterBalancingParenthesis(String text, int from) {
        int depth = 1;
        int at = from;
        while (at < text.length() && depth > 0) {
            char character = text.charAt(at++);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
            }
        }
        return at;
    }

    private static String withoutComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    private static List<Path> checkerSources() {
        return CHECKOUT.tracked(CHECKER_MAIN_SOURCE).stream()
                .filter(path -> path.getFileName().toString().endsWith(".kt"))
                .filter(path -> !path.getFileName().toString().equals(GENERATED))
                .toList();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
    }
}
