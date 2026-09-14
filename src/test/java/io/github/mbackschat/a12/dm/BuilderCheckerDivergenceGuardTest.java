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

    /**
     * The A12 diagnostic codes; the checker and the kits both spell them as bare tokens.
     *
     * <p>The trailing digits are part of the code and not a suffix to strip: {@code MVK_DUPLICATE_PARAM1}
     * and {@code MVK_DUPLICATE_PARAM2} are two different laws, and a pattern that stopped at the underscore
     * run collapsed them into one key that names no A12 code at all.
     */
    private static final Pattern A12_CODE = Pattern.compile("MVK_[A-Z_]+[0-9]*");

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

        /**
         * One estate only NAMES the code — a javadoc, a comment, or a diagnostic hint for a kernel verdict —
         * while the other enforces it. Usually that is the kit naming and the checker reporting; the mirror
         * happens too, and each row says which way round it is, because the scan cannot.
         */
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

            // Both sides read the raw-type `Length` SHAPE, over carriers that cannot share a reader:
            // `RawLengthDiagnostic` matches a regex against the authored condition string to decide whether
            // to attach the integer-domain corrective, while the checker matches a token stream to decide
            // whether the model is legal at all. They agreed on every layout but one — the regex refused the
            // redundant parentheses A12 admits, so a parenthesised condition silently lost its corrective;
            // fixed 2026-09-13 in the same change that measured the row. Neither refuses anything A12 admits.
            Map.entry("MVK_INTERNAL_ERROR", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // `StructuralKernelFix` carries a Hint keyed on the code — prose for a verdict the Kernel
            // reached, enforcing nothing. The checker owns the only enforcement of either law.
            Map.entry("MVK_INVALID_LENGTH_OF_RAW_TYPE", Disposition.PROSE_ONLY),

            // The same, and the checker's mention is a KDoc naming what it deliberately does NOT report:
            // deciding a value POSITION needs the per-operator catalog this checker does not reproduce.
            Map.entry("MVK_INVALID_RAW_TYPE", Disposition.PROSE_ONLY),

            // `StructuralKernelFix` carries a Hint and `DslRenderer`/`GroupRef` name the code in javadoc;
            // nothing in the kits refuses on it, the typed surface simply having no `fieldFilled(GroupRef)`
            // to call. A type-level absence states no code, so the checker owns the only enforcement —
            // and it is the narrower of the two, staying silent wherever a wildcard or the root answers
            // first (`FieldOperandKindLawsTest`).
            Map.entry("MVK_NO_GROUPS_ALLOWED", Disposition.PROSE_ONLY),

            // `FieldListRef`'s javadoc names it as satisfied structurally — only a starred list offers
            // `having` — and the catalog states it as prose. Neither refuses on it. The checker's family is
            // narrower than the code's name: an unstarred filtered field is `MVK_NO_WILDCARD` to A12, so
            // the family reports only the semantic-index shape (`FilteredOperandGateOrderLawsTest`).
            Map.entry("MVK_WILDCARDS_REQUIRED", Disposition.PROSE_ONLY),

            // `EnumRef.category(name)` fails fast on an undeclared category and `StructuralKernelFix` has no
            // hint for it; the kit's refusal is on a typed handle built from the model, so it cannot be
            // wrong about the declared set. The checker reads the same declaration from stored DM-JSON.
            Map.entry("MVK_CATEGORY_UNKOWN", Disposition.SAME_LAW_SEPARATE_CARRIERS),


            // The MIRROR direction of PROSE_ONLY: `EnumRef.is` / `CategoryRef.is` validate the literal
            // against the model's domain, so the KIT enforces, and the checker only names the code in a KDoc
            // saying the category family deliberately does not reproduce it — that is a law about the
            // comparison's constant, not about the arrow (`CategoryReferenceGateOrderLawsTest`).
            Map.entry("MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON", Disposition.PROSE_ONLY),

            // `EnumRef.in/notIn` check every literal against the enum's declared domain, built from the
            // model — so the kit cannot be wrong about the set, and the checker reads the same declaration
            // from stored DM-JSON. Two carriers, no shared reader: typed value objects against a token run.
            Map.entry("MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // The multi-member quantifiers' union class, likewise named only in that KDoc on the checker
            // side while `Dsl`'s value-list quantifiers pre-check the union of the members' domains.
            Map.entry("MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY", Disposition.PROSE_ONLY),

            // Computation.Builder reads List<Alternative> with Optional<Condition>; the checker reads a
            // JsonArray where an ABSENT or null member is the empty precondition. They agree on all four
            // shapes (one unguarded, two mixed, two both-unguarded, three with the middle unguarded); the
            // residue after the carrier-specific reading is `count >= 2`, which is not worth a module.
            // `Computation.Builder.requireNoCustomCondition` walks the typed condition AST and `ComputationLinter`
            // scans a draft's precondition strings; the checker tokenizes a stored precondition member. Same law,
            // three carriers with nothing in common to extract.
            Map.entry("MVK_ERROR_INVALID_LANGUAGE_FEATURE", Disposition.SAME_LAW_SEPARATE_CARRIERS),
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
            // The kit ENFORCES this one (FieldListRef.having refuses the three constructs) and the
            // checker only names it, to say why it stops counting RepetitionNotUnique calls inside a
            // filter — the reverse of the usual direction, which is why the row says so.
            Map.entry("MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION", Disposition.PROSE_ONLY),
            // The usual direction: the checker reports it, the kit names it only as a diagnostic hint
            // in StructuralKernelFix for the kernel's own verdict.
            Map.entry("MVK_INVALID_COMBINATION_OF_REPETITON_NOT_UNIQUE", Disposition.PROSE_ONLY),
            Map.entry("MVK_INVALID_ROOT_GROUP", Disposition.PROSE_ONLY),

            // The RepetitionNotUnique key-placement pair, added 2026-09-14 with that family. rulekit names
            // both to EXPLAIN a kernel verdict and refuses neither: the javadoc on the condition marks the
            // class "position-dependent — kernel-delegated", `StructuralKernelFix` carries its corrective,
            // and `CurrentRepetition`/`AggregateOperand` name the wildcard class the same way. The builder's
            // one throw nearby is about the `@From` reference group, a different demand, and the checker
            // declines the `@From` form outright — so the two never judge one shape. *(Amended 2026-09-14:
            // the checker no longer declines that form, but the separation stands for a sharper reason — the
            // `@From` demand is now `REPETITION_SCOPE_GROUP`, which reports a different code, the row below.)*
            Map.entry("MVK_REPEATABLE_GROUP_MISSING", Disposition.PROSE_ONLY),

            // The `@From` demand itself, enforced on BOTH sides now: `RepetitionNotUniqueCondition.from`
            // throws on a non-repeating `GroupRef` it holds typed, and `REPETITION_SCOPE_GROUP` reports the
            // same law off stored DM-JSON. Nothing thinner than "is this group repeatable" is left to share,
            // and each side asks it of a different representation.
            Map.entry("MVK_GROUP_NOT_REPEATABLE", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // Prose on BOTH sides, which the enum's wording does not anticipate and this row states rather
            // than glosses: rulekit carries only a `StructuralKernelFix` corrective for A12's own verdict,
            // and the checker only names the class in a KDoc saying it is one of the containment classes
            // `REPETITION_SCOPE_GROUP` stays silent on. Neither estate decides it.
            Map.entry("MVK_NOT_IN_SAME_PATH", Disposition.PROSE_ONLY),

            // …and the checker's own mention of this one is a KDoc naming what it deliberately does NOT
            // report: it is the class A12 draws where a key outside the rule group pre-empts the placement
            // class, and no family owns the pinning law that decides it ([KF347]).
            Map.entry("MVK_NO_WILDCARD", Disposition.PROSE_ONLY),

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
            Map.entry("MVK_ERROR_MESSAGE_AND_NO_PATTERN", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // `StringSpec` refuses `noValueValidation` without line breaks in its constructor, over two
            // booleans; the checker reads stored JSON, where the same pair can also be spelled with a
            // non-boolean member the typed slots cannot represent. Same law, and the kit's carrier cannot be
            // wrong about it — a boolean is a boolean ([KERNEL-FINDINGS KF319] is the basis for both).
            Map.entry("MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // The fractional-digit pair. Both sides now state A12's actual law — an absent member reads as
            // 0, a negative maximum takes the pair out of the check ([KERNEL-FINDINGS KF320]) — over two
            // nullable Integers on one side and stored JSON on the other. The residue after each carrier's
            // own reading is one comparison, which is not worth a module. They disagreed in BOTH directions
            // until that grid was measured, which is why the pair is worth this row rather than a shrug.
            Map.entry("MVK_FRACT_DIGITS_INVALID", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // The enum's label laws. `DocumentModelDraft` refuses a partially-labelled enumeration at
            // author time, over a typed `Map<locale, text>` per value; the checker reads stored JSON, where
            // the same defect can also be spelled with a blank text or an empty label array that a typed map
            // cannot hold. Same pair of laws, and the grid that measured them ([KERNEL-FINDINGS KF321])
            // CLEARED the kit's refusal rather than correcting it.
            Map.entry("MVK_COUNT_OF_VALUES_INVALID", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // …and its sibling, which rulekit only NAMES: `StructuralKernelFix` carries its corrective, and
            // the checker projects that very entry onto its own findings.
            Map.entry("MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES", Disposition.PROSE_ONLY),

            // modelkit's `FieldKind` simply has no UNSPECIFIED constant, and names this code in the javadoc
            // saying why. That is a type-level erasure rather than a refusal, which is the harder shape to
            // audit — no runtime error, just a missing factory — so it is worth stating that the erasure is
            // now MEASURED and not merely reasoned: A12 refuses every `UnspecifiedType` declaration, with a
            // body and without, on a root and a nested field, per declaration
            // (`validate.laws.FieldTypeBindingLawsTest`). The checker reads stored JSON, where the shape is
            // authorable by other tools, so it reports the code rather than erasing it.
            Map.entry("MVK_UNSPECIFIED_TYPE_NO_CODEGEN", Disposition.PROSE_ONLY),

            // Both estates enforce the same measured law and reached it independently: `covers` is an exact
            // field match OR strictly-inside a referenced group, and the checker's is the target or an
            // ancestor of it. The carriers are a typed `Condition` tree and scanned condition TEXT, which is
            // also why they part on what they can see — rulekit recognises the `RuleGroup` self-reference as
            // satisfying the gate, the checker reaches the same silence because `RuleGroup` is a keyword
            // there and names no entity. rulekit's is ADVISORY (a PRECHECK warning, never a refusal) and the
            // checker's is a finding; both under-report the `Having` row rather than guessing (KF324).
            Map.entry("MVK_ERROR_FIELD_NOT_REFERENCED", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // Different CARRIERS of one law, and the split is in the operators rather than in the code.
            // `FirstFilledValue.checkOwnerDuplicates` pre-checks its own typed operand list — identity
            // includes the category and semantic-index modifiers, which only a typed operand carries — while
            // the checker reads the six GROUP-LIST quantifiers out of stored condition TEXT, where those
            // modifiers make an operand it declines to compare. Both skip a wildcarded operand, which is
            // A12's own skip on each carrier (KF325 measures it on the group list). Neither refusal reaches
            // the other's shapes, so nothing here is inherited.
            Map.entry("MVK_DUPLICATE_PARAM1", Disposition.SAME_LAW_SEPARATE_CARRIERS),
            Map.entry("MVK_DUPLICATE_PARAM2", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // Again two carriers of one law. `BoundModel.firstFilledGroupScope` refuses a root group as a
            // `FirstFilledValue` scope; the checker reports it on the six group-list quantifiers, where the
            // class is per carrier — three refuse a root outright and three admit a SOLE one (KF326). Each
            // side's rule is false of the other's operators, so neither can be the other's source.
            Map.entry("MVK_ROOT_GROUP_REFERENCED", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // `SumOfProducts`' four operand laws, added 2026-09-14 with the family that reports them. The
            // record's constructor enforces all four over typed operands and QUOTES these codes in its
            // refusal messages; the checker enforces the same four over stored DM-JSON. Neither is broader
            // than A12: the kit's shape is exactly what `SumOfProductsLawsTest` measured, and the checker
            // additionally stays silent wherever the reference reader answers first, which the typed
            // operands cannot express (KF370).
            Map.entry("MVK_DIFFERENT_GROUPS", Disposition.SAME_LAW_SEPARATE_CARRIERS),
            Map.entry("MVK_REPEATABLE_LEVEL_REQUIRED", Disposition.SAME_LAW_SEPARATE_CARRIERS),
            Map.entry("MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED", Disposition.SAME_LAW_SEPARATE_CARRIERS),
            Map.entry("MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // The semantic index's own structure, added 2026-09-14 with the family that reports it.
            // `BoundModel.firstFilledSemanticIndex` and `FirstFilledOperand` refuse a starred enclosing
            // group with this code; the checker reports the same law off stored DM-JSON, and stays the
            // narrower of the two — it is silent wherever an earlier gate of A12's chain answers (KF351).
            Map.entry("MVK_SEMANTIC_INDEX_AND_WILDCARD", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // The mirror shape: the kits only NAME it — a dozen javadoc references, the catalog's prose and
            // `DiagnosticEnricher`'s sole-raise-site hint — because rulekit's index does not expose
            // `indexFieldName` (DEV-API-GAPS DG15). The checker owns the only enforcement.
            Map.entry("MVK_NO_INDEX_FIELD", Disposition.PROSE_ONLY),

            // The row this guard exists for. The kits ERASE the shape — a group predicate's operand is a
            // `GroupRef`, so a field cannot be handed to one — and A12 admits a field operand on FIVE of the
            // eight carriers (KF327). The checker deliberately does NOT inherit that erasure: it reports the
            // code on the three carriers measured to draw it and stays silent on the five that do not. The
            // Java strictness stands because it bites on the authoring path alone, which is what makes it a
            // documented over-strictness rather than a defect.
            Map.entry("MVK_NO_FIELDS_ALLOWED", Disposition.STRICTER_IN_JAVA),

            // One law, two readings of a path. `BoundModel.flatten`/`flattenGroup` refuse a star on a
            // non-repeatable group as the typed ref is built; the checker walks each SEGMENT of a stored
            // reference and asks the same question of what the segment names. Neither is stricter than A12:
            // the class is measured to fire in every position, ahead of each slot's own wildcard gate and
            // ahead of the entity gate (KF328).
            Map.entry("MVK_INVALID_WILDCARD", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // The same law's other class for a NAME-like segment. `erased` on the Java side — a starred
            // rule-group reference is unrepresentable once the rule group is a typed `GroupRef` — and
            // authorable in stored DM-JSON, which is the estate the checker reads. Reporting it under the
            // class above would be a wrong finding on a correct rejection. (Its sibling
            // `MVK_INVALID_WILDCARD_REL` needs no row: no kit main source names it, so there is no pair.)
            Map.entry("MVK_INVALID_WILDCARD_FOR_RULEGROUP", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // The kits erase the shape at type level — a scalar slot takes `FieldRef`, never a starred list —
            // and the checker reports the code off stored DM-JSON in the one position it has measured: inside
            // a `Having` filter (`FILTER_WILDCARD`). *(Amended 2026-09-14: this row read PROSE_ONLY while the
            // checker only NAMED the code. The erasure and the family are not the same law restated — the
            // filter is a fourth position, and position is what picks the class there.)*
            Map.entry("MVK_NO_WILDCARDS_ALLOWED", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // Prose on the CHECKER side again: `Computation.build()` genuinely pre-checks a self-read, while
            // the checker only NAMES the code, in the cycle family's doc comment explaining why a one-node
            // circle is left to A12. The checker deliberately does not carry the law — its two pre-emptions
            // need derived-scale inference — so there is nothing here for the kit's refusal to leak into.
            Map.entry("MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD", Disposition.PROSE_ONLY));

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
