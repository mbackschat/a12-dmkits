package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every A12 code stated in BOTH the Java kits and the portable checker carries a recorded disposition.
 *
 * <p>The two estates judge the same A12 laws from opposite sides, and the dangerous direction is only one:
 * the Java builders are allowed to be <em>stricter</em> than A12 where that is deliberate and written down
 * (the enum S-value domain check, the locale policy below), while the checker may never inherit such a
 * refusal — reporting where A12 accepts is the one thing this project forbids. Nothing connects the two, so a
 * divergence is invisible on both sides: the {@code vk_vk} law sat in {@code Rule.Builder} and the checker
 * while {@code Computation.Builder} lacked it for three months, and no build could have said so.
 *
 * <p><b>What this guard proves, and what it does not.</b> It proves a DECISION WAS RECORDED for every code
 * the two estates share — never that the decision is right. No scan can read a refusal's intent, so the
 * enforcement this adds is procedural: a code that newly appears on both sides fails the build until someone
 * classifies it, which is the moment to ask whether a builder refusal is about to leak into the checker. The
 * dispositions themselves were each measured by reading both implementations (2026-09-13).
 *
 * <p><b>The denominator is derived, not authored</b>, which is why this rides {@code leakGuardTest}: an
 * authored list of "laws we know are shared" can only ever check the pairs someone remembered, and the pair
 * that bites is by definition the one nobody thought of. The scan reads the kits' and the checker's main
 * sources, so a new shared code arrives in the denominator on its own.
 *
 * <p>A token match is deliberately coarse — it catches a code named in a javadoc as readily as one thrown —
 * and {@link Disposition#PROSE_ONLY} is how that shows up. The coarseness is the point: narrowing the scan to
 * "real" enforcement sites would need a heuristic for what enforcement looks like, and a heuristic that is
 * wrong in the permissive direction restores exactly the blind spot this exists to remove.
 */
class BuilderCheckerDivergenceGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /** The A12 diagnostic codes; the checker and the kits both spell them as bare tokens. */
    private static final Pattern A12_CODE = Pattern.compile("MVK_[A-Z_]+");

    /** The published, kernel-free kits — every module whose builders may pre-check an A12 law. */
    private static final List<String> KIT_MAIN_SOURCES = List.of(
            "core/src/main", "rulekit/src/main", "modelkit/src/main", "edit/src/main", "schemakit/src/main");

    private static final String CHECKER_MAIN_SOURCE = "consistency/src/commonMain";

    /** Why a code may legitimately be stated on both sides. */
    private enum Disposition {
        /** One live statement in {@code :predicates}; both sides call it. The goal state. */
        SHARED_LEAF,

        /**
         * Both sides enforce A12's law, over carriers too different to share: a kit reads its own typed
         * objects, the checker reads stored DM-JSON. Extracting the residue would leave a predicate so thin
         * that restating it is clearer than sharing it.
         */
        SAME_LAW_SEPARATE_CARRIERS,

        /**
         * The kit REFUSES what A12 admits, on purpose and with its reason written down, and the checker
         * deliberately does not. This is the row to read twice on any change: the divergence is the feature.
         */
        STRICTER_IN_JAVA,

        /** The kit only NAMES the code — a javadoc, a comment, or a diagnostic hint for a kernel verdict. */
        PROSE_ONLY,
    }

    /**
     * Every code both estates name, with the disposition measured for it on 2026-09-13.
     *
     * <p>Re-derive rather than trust a row: each was settled by reading the kit's site and the checker's
     * together, and a later edit to either can change the answer without changing this map.
     */
    private static final Map<String, Disposition> DISPOSITIONS = Map.ofEntries(
            // `isReservedElementName`, reached by Rule.Builder, Computation.Builder and the checker.
            Map.entry("MVK_RULE_NAME_INVALID", Disposition.SHARED_LEAF),

            // Computation.Builder reads List<Alternative> with Optional<Condition>; the checker reads a
            // JsonArray where an ABSENT or null member is the empty precondition. They agree on all four
            // shapes (one unguarded, two mixed, two both-unguarded, three with the middle unguarded); the
            // residue after the carrier-specific reading is `count >= 2`, which is not worth a module.
            Map.entry("MVK_ERROR_EMPTY_PRECONDITION", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // The builders require a non-empty typed message list. The checker models what A12 actually
            // collects — declared locales only, a later entry replacing an earlier one, blank text counting
            // as none — which a typed list cannot express. The builder is the weaker claim, so it can only
            // under-reject, which is safe.
            Map.entry("MVK_ERROR_MESSAGE_MISSING", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // LocalizedText.checkDeclared demands a message for every declared locale; so does the checker.
            // Same law, and again the builder cannot see blank text, so it under-rejects.
            Map.entry("MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // LocalizedText.checkDeclared THROWS on a duplicate locale. A12's DM-JSON tier admits it and
            // silently keeps one text, so the checker reports it advisory-only and never as a rejection
            // (`authoredLocalePolicyFindings`). Inheriting the builder's refusal here would be an
            // over-rejection; the plan's C-family record carries the measurement.
            Map.entry("MVK_LANGUAGE_DUPLICATE", Disposition.STRICTER_IN_JAVA),

            // rulekit names these only to EXPLAIN a kernel verdict — StructuralKernelFix hints, RkCode prose,
            // renderer javadoc, catalog gotchas — never to refuse anything itself.
            Map.entry("MVK_FIELDNAME_NOT_UNIQUE", Disposition.PROSE_ONLY),
            Map.entry("MVK_INVALID_ENTITY", Disposition.PROSE_ONLY),
            Map.entry("MVK_INVALID_COMPARE_DEC_PLACES", Disposition.PROSE_ONLY),
            Map.entry("MVK_UNEXPECTED_TOKEN", Disposition.PROSE_ONLY),
            Map.entry("MVK_ERROR_FIELD_MISSING", Disposition.PROSE_ONLY),
            Map.entry("MVK_MISSING_ERROR_FIELD", Disposition.PROSE_ONLY),
            Map.entry("MVK_INVALID_ROOT_GROUP", Disposition.PROSE_ONLY),

            // The two DateType config gates. rulekit names them only in `StructuralKernelFix`'s correctives,
            // deliberately: a client-side refusal was measured and DECLINED there, because `FieldTypeSpecReader`
            // builds `DateSpec` on the READ path and a constructor check would make an inconsistent kernel
            // model unreadable rather than merely unauthorable ([KERNEL-FINDINGS KF251]). The checker has no
            // read path to spoil — it reports on stored JSON — so it reproduces both codes, restricted to the
            // formats A12 is measured to admit.
            Map.entry("MVK_ADDITIONAL_CHECK_INVALID", Disposition.PROSE_ONLY),
            Map.entry("MVK_OPTIONAL_DATE_TYPE_INVALID", Disposition.PROSE_ONLY),

            // The enum-category pairing. `FieldTypeSpec.EnumSpec` refuses a misaligned category in its
            // constructor, over typed `EnumCategory` records, and collapses A12's two per-element codes into
            // one message; the checker reads stored JSON and reproduces both, once per unpaired element. Same
            // law, carriers too different to share — and the builder cannot express the multiplicity at all.
            Map.entry("MVK_MISSING_CATEGORY_VALUE", Disposition.SAME_LAW_SEPARATE_CARRIERS),
            Map.entry("MVK_MISSING_INTERNAL_VALUE", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // A12's pattern-admission blacklist, at its two loci. One law, two codes: a condition literal
            // draws `MVK_INVALID_PATTERN` and a declared field `MVK_PATTERN_INVALID` (measured — the two
            // words swapped). `KernelPatternPolicy` refuses the first and the checker reports the second,
            // and both now call `predicates.kernelPatternBlacklist`. They had two copies until 2026-09-13
            // and the copies disagreed in BOTH directions, which is exactly what this guard exists to stop.
            Map.entry("MVK_INVALID_PATTERN", Disposition.SHARED_LEAF),
            Map.entry("MVK_PATTERN_INVALID", Disposition.SHARED_LEAF),

            // The pattern/message co-requirement, whose two directions are two codes. One check states both
            // in each estate: `FieldTypeSpec.StringSpec` refuses either member alone in its constructor,
            // over two typed slots, and the checker reads stored JSON — where "carries a message" is a REACH
            // question the typed slots cannot pose, since an empty list, a blank text and a text for an
            // undeclared locale all leave the member present and give A12 nothing ([KERNEL-FINDINGS KF318]).
            // The kit is the weaker claim on every one of those, so it can only under-reject, and it cannot
            // author the half-translated shape at all because `DocumentModelDraft` emits its single message
            // for every declared locale.
            Map.entry("MVK_ERROR_MESSAGE_AND_PATTERN_INVALID", Disposition.SAME_LAW_SEPARATE_CARRIERS),
            Map.entry("MVK_ERROR_MESSAGE_AND_NO_PATTERN", Disposition.SAME_LAW_SEPARATE_CARRIERS));

    @Test
    void everyCodeStatedInBothEstatesCarriesADisposition() {
        Set<String> shared = new TreeSet<>(codesIn(KIT_MAIN_SOURCES));
        shared.retainAll(codesIn(List.of(CHECKER_MAIN_SOURCE)));

        Set<String> unclassified = new TreeSet<>(shared);
        unclassified.removeAll(DISPOSITIONS.keySet());
        assertEquals(Set.of(), unclassified,
                "these A12 codes are now stated in BOTH the Java kits and the portable checker with no "
                        + "recorded disposition. Read both sites and classify each: if the kit REFUSES what "
                        + "A12 admits, the checker must not inherit that refusal — over-rejection is the one "
                        + "direction this project forbids. Add the row to DISPOSITIONS with the reason.");
    }

    /**
     * A disposition is a claim about a live pair, so a code that has left one estate must lose its row.
     *
     * <p>Without this the map would only ever grow, and a stale row reads exactly like a measured one — the
     * failure mode the {@code -GAPS} ledgers avoid by deleting a shipped row rather than annotating it.
     */
    @Test
    void noDispositionOutlivesThePairItDescribes() {
        Set<String> shared = new TreeSet<>(codesIn(KIT_MAIN_SOURCES));
        shared.retainAll(codesIn(List.of(CHECKER_MAIN_SOURCE)));

        Set<String> stale = new TreeSet<>(DISPOSITIONS.keySet());
        stale.removeAll(shared);
        assertEquals(Set.of(), stale,
                "these codes carry a disposition but are no longer stated in both estates — delete the row "
                        + "rather than leaving it to read as a measured pair");
    }

    /**
     * The scan finds both estates, so an empty intersection means a broken sweep rather than a clean one.
     *
     * <p>A path typo or a moved source root would make both sets empty, and every assertion above would pass
     * on nothing at all. The floors are deliberately far below the live counts — they answer "did the scan
     * happen", not "how large is the estate", so ordinary growth never edits them.
     */
    @Test
    void theScanReachesBothEstates() {
        Set<String> kits = codesIn(KIT_MAIN_SOURCES);
        Set<String> checker = codesIn(List.of(CHECKER_MAIN_SOURCE));
        assertTrue(kits.size() > 20, () -> "the kit scan found only " + kits.size() + " A12 codes, so it "
                + "did not reach the kits and a green result above proves nothing");
        assertTrue(checker.size() > 5, () -> "the checker scan found only " + checker.size() + " A12 codes, "
                + "so it did not reach the checker and a green result above proves nothing");
    }

    private static Set<String> codesIn(List<String> relativeRoots) {
        Set<String> codes = new TreeSet<>();
        for (String relative : relativeRoots) {
            Path root = CHECKOUT.resolve(relative);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt"))
                        .forEach(p -> collectCodes(p, codes));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot scan " + root, e);
            }
        }
        return codes;
    }

    private static void collectCodes(Path file, Set<String> into) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        Matcher matcher = A12_CODE.matcher(text);
        while (matcher.find()) {
            into.add(matcher.group());
        }
    }
}
