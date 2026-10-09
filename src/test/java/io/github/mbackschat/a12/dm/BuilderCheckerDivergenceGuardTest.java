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
    private static final Pattern A12_CODE = Pattern.compile("MVK_[A-Z0-9_]+");

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

            // Both estates enforce A12's confirm gate on `FirstFilledValue`, over carriers that cannot share
            // a reader: `FirstFilledValue`'s constructor refuses a CONFIRM operand handed to the typed
            // facade (`FirstFilledValueDomainTest`), while `FieldListOperationOperands` walks the expanded
            // field list of a stored condition. Neither refuses more than A12 does — the boolean sibling
            // clears the gate on both sides, which is the cell the code's name gets wrong
            // (`FieldListOperandKindLawsTest`).
            Map.entry("MVK_NO_BOOLY_ALLOWED", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // The kits only NAME these two — `PatternCondition`'s javadoc lists the constant gate among what
            // the kernel arbitrates, and `Corrective`/`SpanResolver`/`TextConditionCorrective` name the
            // ordering one as a class the typed path cannot produce. Neither refuses on either; the checker
            // owns the only enforcement, and it is the narrower of the two, typing only a bare reference or
            // a literal and leaving every computed side alone.
            Map.entry("MVK_INVALID_COMPARE_TO_CONST", Disposition.PROSE_ONLY),
            Map.entry("MVK_INVALID_TYPE_FOR_COMPARISON", Disposition.PROSE_ONLY),

            // The kits only NAME it: `StructuralKernelFix` carries A12's corrective for the class and refuses
            // nothing, leaving the rule to the kernel. `ErrorFieldIterationBinding` owns the checker's
            // enforcement (KF417).
            Map.entry("MVK_ERROR_FIELD_AND_ITERATION_INVALID", Disposition.PROSE_ONLY),

            // The kits only NAME it: `DateConstruction`, `Dsl.date(day, month)` and `BoundModel.dateOf(day, month)`
            // say the two-component form needs a declared Base Year and leave the refusal to the kernel, because
            // the declaration is model data the record does not see (IG163). `BaseYearRequirement` owns the
            // checker's enforcement (KF403).
            Map.entry("MVK_MISSING_YEAR", Disposition.PROSE_ONLY),

            // Neither estate enforces it, and both say so where a reader would otherwise assume they do:
            // `Rule.Builder` names it at the check it REMOVED (the refusal had no measured basis — A12
            // accepts an absent `severity`, AF150), and `DeclarationSlots` names it to state that the
            // checker reports nothing for an absent member. Prose on both sides, deliberately.
            Map.entry("MVK_SEVERITY_MISSING", Disposition.PROSE_ONLY),

            // Same shape, and the checker's side is an UNDER-report it discloses rather than a gap: A12's
            // admitted domain here is whatever tz database the running JVM carries (632 ids on the pin), so
            // a portable check cannot be made safe and the checker reports nothing. `StructuralKernelFix`
            // enriches the code when the KERNEL emits it, which is enrichment rather than enforcement, and
            // `Outcome.kt` names it as the measured witness for why `ALL_CHECKS_PASSED` is scoped to the
            // checker's own coverage and never to A12 acceptance. Prose on both sides, deliberately.
            // Entered this map on 2026-09-18 — the checker had never named the code until that KDoc.
            Map.entry("MVK_INVALID_TIME_ZONE", Disposition.PROSE_ONLY),

            // Both estates enforce A12's boolean-kind law, on carriers that cannot share a reader:
            // `Computation` refuses `False` into a CONFIRM computed field and the wrong constant into a
            // BOOLEAN one, each measured (`ConfirmComputationSourceKindLawsTest`), while
            // `BooleanComparisonAgreement` reads the COMPARISON carrier out of stored DM-JSON. The kit's
            // comparison side is a type-level erasure rather than a refusal — `ConfirmRef` offers only
            // `confirmed`/`notConfirmed` — and the checker deliberately does NOT inherit it: A12 admits two
            // confirms under `!=`, which the erasure cannot author, so a checker mirroring the handle's
            // shape would refuse a model A12 accepts.
            Map.entry("MVK_INVALID_COMPARE_TO_YES", Disposition.SAME_LAW_SEPARATE_CARRIERS),
            Map.entry("MVK_INVALID_COMPARE_TO_YESNO", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // The kit only NAMES it: `Computation`'s javadoc cites it as the class a kind mismatch draws on
            // the COMPUTATION carrier, and the builder's own kind pre-check reports its own message rather
            // than this code. The checker owns the only statement of it that carries the code, on the
            // COMPARISON carrier, and is the narrower of the two — a `CustomFieldType` side is left unjudged
            // because its base type is unmeasured there.
            Map.entry("MVK_INVALID_COMPARE_TO_ENUM_OR_STRING", Disposition.PROSE_ONLY),

            // The kit only NAMES it, and says so: `FieldValueAsNumber`'s javadoc cites the code and states
            // outright that the domain question is "its call (F4/N7), not pre-checked here". The checker owns
            // the only enforcement, and is deliberately narrower than the kernel on the enumeration arm — it
            // refuses a declared value only where a character outside the numeric alphabet makes it certain,
            // so a spelling the kernel's own parser rejects for its grammar is left to A12.
            Map.entry("MVK_NO_CONVERT_IN_NUMBER", Disposition.PROSE_ONLY),

            // Both estates enforce `NumberOfDifferentValues`' leader-decides-the-arm law, over carriers that
            // cannot share a reader: `CountAggregate.checkValueComparable` partitions typed operands by
            // `ValueKind`, the checker walks stored DM-JSON. Neither refuses anything A12 admits — the kit
            // is the laxer of the two on the temporal arm, deliberately leaving format compatibility to F4.
            //
            // One divergence that is NOT a refusal difference and belongs to the kit: its admissible-kind
            // loop cites MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED for a boolean, confirm or date-range
            // operand in ANY position, while A12 draws that code only from the LEADER and answers a
            // non-leading one with the homogeneity class (KF399, measured). The kit still refuses the same
            // models; only the code named in its message is wrong off the leader. Left to the kit's own
            // slice rather than folded in here, and recorded so the row is not read as full agreement.
            // Both estates enforce A12's date-argument kind gate, over carriers that cannot share a reader:
            // `TemporalOperands.refuseMeasuredNonPointKind` refuses a typed `Calculation` by its `ValueKind`,
            // while `DateArgumentSlot` reads the declared field type out of stored DM-JSON. Each denominator
            // was measured against the kernel separately and they name the same seven kinds — number, string,
            // enumeration, boolean, confirm, date range, custom — so neither refuses anything A12 admits, and
            // both leave the unresolved declarations (the kit's UNKNOWN, the checker's TypeDef/Unspecified)
            // kernel-arbitrated. Their REACH differs and is meant to: the kit gates the two extraction
            // carriers it can see from a bare `Calculation`, the checker gates every operator that takes the
            // slot (KF400).
            // The kits only NAME it, and say whose call it is: `BaseYear`'s javadoc cites the code and
            // states outright that the declaration is "the kernel's call", `Dsl.startOfDateRange` and
            // `DocumentModelDraft.baseYear` repeat the requirement in prose, and `KernelMessageOperands`
            // cites it only as a message template with no `%s`. Nothing on that side refuses; the checker
            // owns the only enforcement (KF403).
            Map.entry("MVK_NO_BASE_YEAR", Disposition.PROSE_ONLY),

            Map.entry("MVK_NO_DATE", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            Map.entry("MVK_NUMBER_AND_NON_NUMBER", Disposition.SAME_LAW_SEPARATE_CARRIERS),
            Map.entry("MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // Corrected 2026-09-14: this sat with the prose-only group, whose comment says rulekit "never
            // refuses anything itself" — and `NumberExpr.requireCompatibleScale` does exactly that, through
            // `NumericScaleSummary.requireEqualityCompatible`. Both estates now enforce A12's scale gate over
            // carriers that cannot share a reader: the kit knows each operand's derived scale and its
            // multiplicative-constant capability from the typed expression, the checker reads a declaration's
            // `maxFractionalDigits` and a literal's written digits. The checker is the narrower of the two —
            // it types only a bare reference or a literal, where the capability question has one answer — so
            // it inherits no refusal.
            Map.entry("MVK_INVALID_COMPARE_DEC_PLACES", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // The checker only NAMES it, in a KDoc saying which arm answers a date partner ahead of the
            // number one; the kit erases the shape, `DateRef` being the only handle that compares as a date.
            // This is the mirror direction of the usual prose-only row.
            Map.entry("MVK_INVALID_COMPARE_TO_DATE", Disposition.PROSE_ONLY),

            // The kit states the remaining two pattern classes only in prose and erases their shapes at
            // the type level: the left operand must be a field cell, and the right a Java String. The
            // checker reports those classes over stored DM-JSON, where both invalid shapes can exist.
            // DG71's indexed-cell factory leaves kind validation to the kernel, so the kind-class pair
            // no longer belongs in this disposition map.
            Map.entry("MVK_INVALID_PARAMETER_FOR_PATTERN_COMPARISON", Disposition.PROSE_ONLY),
            Map.entry("MVK_MISSING_CONST_IN_PATTERN_COMP", Disposition.PROSE_ONLY),

            // Both enforce A12's group-count law over carriers that cannot share a reader: `CountAggregate`
            // refuses the count from its own typed operands, `EntityListArity` from stored paths and the
            // declared kind each resolves to. Neither refuses what A12 admits.
            Map.entry("MVK_PARAMSIZE_INVALIDGN", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // The checker's only statement of it is inside the PROJECTED corrective for
            // MVK_PARAMSIZE_INVALIDGN, whose owner prose names it as what the kernel's own suggested fix can
            // draw instead. Prose on both sides: the enricher owns the hint, `Linter` names it in a javadoc,
            // and no family reports it.
            Map.entry("MVK_NEG_CONDITION_IN_ITERATION", Disposition.PROSE_ONLY),

            // Both enforce A12's base-kind law over carriers that cannot share a reader: `Dsl`'s entity-list
            // quantifier walks typed operands and knows each one's kind from its handle, while the checker
            // resolves a stored path to a declaration and reads its `fieldType`. The kit's own arm is the
            // narrower of the two — it steps over a GROUP scope rather than re-implement the expansion —
            // so neither refuses what A12 admits.
            Map.entry("MVK_VARYING_TYPES_NOT_ALLOWED", Disposition.SAME_LAW_SEPARATE_CARRIERS),
            Map.entry("MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // `Dsl.checkedStringList` refuses an enum-kind repetition list in the literal-values overload
            // and cites the code as what the kernel would otherwise answer; the deferral is the kit's, not
            // A12's. The checker reports the law itself — a literal value list opposite a NUMBER field list
            // — and says nothing about enum-kind lists, so it does not inherit the deferral.
            Map.entry("MVK_INVALID_TYPES_FOR_COMPARISON", Disposition.STRICTER_IN_JAVA),

            // The kit only names it, as the enricher's rendering example. The checker reports it for one
            // carrier, a number extractor handed a quoted date (`TemporalExtractorOperands`, KF529), and names
            // it again in a KDoc listing the date-range quantifier's own arm, which it leaves alone.
            Map.entry("MVK_NO_CONST", Disposition.PROSE_ONLY),

            // Both enforce A12's arity law over carriers that cannot share a reader: the typed aggregates
            // (`CountAggregate`, `ListAggregate`, `FirstFilledValue`, `DateListAggregate`) refuse the count
            // at construction from their own operand objects, while `EntityListArity` resolves stored paths
            // and reads each declaration's kind. Neither refuses what A12 admits — the checker's own arm
            // stands down wherever an earlier gate answers.
            Map.entry("MVK_PARAMSIZE_INVALIDN", Disposition.SAME_LAW_SEPARATE_CARRIERS),

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

            // `Rule.build()` refuses an error field outside the rule's group (`RK_ERROR_FIELD_OUTSIDE_GROUP`),
            // and that refusal is measured OVER-REJECTING (KF212): A12 admits the pair whenever the rule does
            // not iterate, because the code gates ITERATION rather than placement. The checker deliberately
            // does not inherit it — `operandScopeContributions` names the code only to say which shapes
            // it stays silent on, that gate running ahead of every class it does report. The divergence is a
            // known kit defect rather than a chosen strictness, and this row is where it stays visible.
            Map.entry("MVK_ERROR_FIELD_NOT_IN_RULEGROUP", Disposition.STRICTER_IN_JAVA),

            // The kit names it only in the operator catalog — a gotcha on the date-fragment arithmetic
            // operators and a corrective on the extractors — and refuses on none of them. The checker
            // reports it on three carriers: a `Date`/`Time` construction slot, where the demand on a constant
            // is digits, a range and a width, and on a field component its declared width or bound
            // (`DateConstructionSlotLawsTest`, `TemporalComponentDeclarationLawsTest`); a shift's date
            // (`DateArithmeticOperandLawsTest`); and an extractor's operand (KF529).
            Map.entry("MVK_WRONG_DATE_FORMAT_FOR_OP", Disposition.PROSE_ONLY),

            // `DateConstruction`'s javadoc names it while pointing the gate at the model, and the typed
            // surface refuses nothing: a component's declared pattern is not knowable from the handle. The
            // checker reads that declaration from stored DM-JSON.
            Map.entry("MVK_NO_DIGIT_PATTERN", Disposition.PROSE_ONLY),

            // The kit names it in the operator catalog and in `GroupPresenceCondition`'s javadoc, both
            // teaching the two-gate distinction, and `having` refuses no filter body on this ground — the
            // gate needs the model's repeatability, which a typed handle does not carry. The checker asks it
            // from stored DM-JSON (`FILTER_BODY_GATES`).
            Map.entry("MVK_NO_ITERATION_FOR_WILDCARD", Disposition.PROSE_ONLY),

            // The usual direction: the checker reports it (`OUTER_MARKER_POSITION`), and `FieldValue`'s
            // javadoc names it to say WHY the marker is only rendered inside a filter. The kit refuses
            // nothing on this ground — it cannot, the marker being a rendering choice its own builder makes
            // — so the two estates are not enforcing the same law over different carriers.
            Map.entry("MVK_INVALID_OUTER_ITERATION", Disposition.PROSE_ONLY),

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

            // Computation.Builder refuses a non-EXACT RoundingMode beside any alternative whose Optional band is
            // empty; `computationRoundingFindings` asks the same of every stored alternative, after binding the
            // two String members to A12's spellings — a step the typed enums make unnecessary. Same measured
            // pair (validate.laws.ComputationRoundingModeLawsTest), two carriers with no shared reader.
            Map.entry("MVK_ERROR_WRONG_ROUNDING_MODE", Disposition.SAME_LAW_SEPARATE_CARRIERS),

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
            Map.entry("MVK_UNEXPECTED_TOKEN", Disposition.PROSE_ONLY),
            Map.entry("MVK_ERROR_FIELD_MISSING", Disposition.PROSE_ONLY),
            Map.entry("MVK_MISSING_ERROR_FIELD", Disposition.PROSE_ONLY),
            // Both estates enforce it now, over carriers that cannot share: FieldListRef.having refuses all
            // three constructs on a typed handle, while the checker reads a stored filter body and can only
            // reach the gate once the four gates before it pass. Changed from PROSE_ONLY 2026-09-15, when
            // the checker gained the gate; it reports RepetitionNotUnique alone, the other two being
            // unreachable from its own parse (FilterBodyGates).
            Map.entry("MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION", Disposition.SAME_LAW_SEPARATE_CARRIERS),
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

            // The REVERSE direction and a code neither estate disputes: `FieldListRef.having` refuses a
            // semantic-index filter with this code named in the message, while the checker names it only in
            // the post-parse phase list — the set of classes a parse-time defect erases. Membership of that
            // list is a fact about A12's ORDER, not a report, which is why it is prose here.
            Map.entry("MVK_SEMANTIC_INDEX_NOT_ALLOWED", Disposition.PROSE_ONLY),

            // The usual direction: `StructuralKernelFix` carries a corrective for A12's verdict and refuses
            // nothing, while `REPETITION_KEY_CONTAINMENT` reports the class off stored DM-JSON. *(Amended
            // 2026-09-14: the checker's mention was a KDoc naming what it did NOT report until that family
            // landed.)* Its two sibling classes reach no kit `.java`/`.kt` file at all — `MVK_NOT_IN_GROUP`
            // is named only in the operator catalog, which this scan does not read — so neither belongs
            // here, and a row for either would fail as a disposition with no pair.
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

            // The entity-list aggregate's temporal chain, added 2026-09-14 with TEMPORAL_AGGREGATE_COMPONENTS.
            // Only the first runs the usual way round: a `CountAggregate` javadoc names it and
            // `temporalAggregateComponentFindings` reports it. The other three are the MIRROR — the kits
            // enforce them (`CountAggregate`'s homogeneity partition raises the second,
            // `DateListAggregate`/`ListAggregate` the third and fourth) and the checker only names them, in a
            // KDoc table saying which gate answers ahead of the one it does report. None of the four is a
            // client-side refusal the checker could inherit: each rests on the operand kinds a typed builder
            // holds, which is why the component-set gate itself is delegated on that side.
            Map.entry("MVK_DATEFORMATS_NOT_COMPATIBLE", Disposition.PROSE_ONLY),
            Map.entry("MVK_DATE_AND_NONDATE", Disposition.PROSE_ONLY),
            Map.entry("MVK_NOT_SORTABLE", Disposition.PROSE_ONLY),
            // Named by the temporal family only to say which class a NON-temporal leading operand draws on
            // the count carrier, where the extrema draw MVK_NOT_SORTABLE. Naming the neighbouring class is
            // what keeps the family's own class off a list A12 refuses for a different reason.
            Map.entry("MVK_STRING_ENUM_AND_NON_STRING_ENUM", Disposition.PROSE_ONLY),
            Map.entry("MVK_NO_NUMBER", Disposition.PROSE_ONLY),
            // The MIRROR shape, and the naming side is not a check at all: `KernelMessageOperands` quotes
            // this code's German text as an example of a message whose operands are fixed prose, while the
            // checker REPORTS it — `NumberOfValueInFields` counting a point in time, the arm A12's switch
            // falls through on (measured 2026-09-15, KF409). The kit could not inherit a refusal here even
            // if it wanted one: its overloads take a number, a string or a boolean, so the shape is
            // unwritable through them and reaches a reader only as stored text.
            Map.entry("MVK_INVALID_CONST", Disposition.PROSE_ONLY),

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

            // Measured 2026-09-24, when the kits began citing them (2026-09-22/23). A referenced group must
            // hold a field somewhere in its subtree (KF215). `BoundModel`'s entity-list read and the
            // `FirstFilledOperand` constructor refuse a typed group operand whose `descendantFields` is empty,
            // the recursive set A12 expands, and the checker's `EmptyGroupOperand` reports a stored operand by
            // the same test. The kit refuses only an operand every rule refuses, and its CLASS is withheld where
            // an earlier read stage can answer (KF458), so no class leaks either way.
            Map.entry("MVK_GROUP_IS_EMPTY", Disposition.SAME_LAW_SEPARATE_CARRIERS),
            Map.entry("MVK_ROOT_GROUP_IS_EMPTY", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // `CountAggregate` refuses a root beside other operands on `NumberOfFilledGroups` over typed
            // groups; the checker's `GroupListOperands` reports the same beside-others shape in stored text.
            // Both are the rows `EntityListOperandReadLawsTest` and `GroupListRootOperandLawsTest` measure, and
            // neither refuses the sole root A12 admits.
            Map.entry("MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // `RoundingPlaces` refuses a typed places count above 14, the checker's `RoundingOperands` a
            // written digit run above it. One ceiling, measured on both constructs
            // (`RoundingPlacesOverflowLawsTest`); a negative count is a different class on each side, the
            // kit citing the token A12 cannot read and the checker leaving a non-digit run to that gate.
            Map.entry("MVK_INVALID_DIGIT_FOR_ROUNDING", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // `Dsl.validType`/`invalidType` refuse a field whose kind is neither STRING nor ENUMERATION, and
            // the checker's `ValidityConditionOperands` a declared type outside the code-bearing set. Same law
            // (`ValidityConditionOperandLawsTest`). The kit also refuses an UNKNOWN kind, a dangling type
            // reference, where the checker stays silent; a model carrying one is refused by A12 anyway.
            Map.entry("MVK_NO_STRING_OR_ENUM_OR_EXT_ENUM", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // The kit only NAMES it: `IndexedGroupPresenceCondition` says why it never renders a one-operand
            // `AllGroupsFilled`, which A12 refuses (KF351). The checker's `EntityListArity` enforces it.
            Map.entry("MVK_PARAMSIZE_INVALID2", Disposition.PROSE_ONLY),

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

            // The two substring records refuse `start < 1 || end < start` in their canonical constructors,
            // which is `CheckFeldOpMitParamsImpl`'s `begin < 1 || begin > ende` character for character, and
            // the checker reads the same law off a stored condition text where no constructor ran. Neither
            // is stricter than A12, so the kit's refusal is safe on its read path too: a model carrying such
            // an interval is one A12 itself rejects.
            Map.entry("MVK_INVALID_INTERVAL", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // *(Amended 2026-09-15: this read PROSE_ONLY while the checker only named the code. It now
            // reports it, and the two sides are not one law restated — `Computation.build()` pre-checks a
            // read of the computed field ITSELF off typed handles, where the checker reads stored DM-JSON
            // and carries the containment half A12 actually applies: the field's own group and every group
            // above it, minus the two exemptions ([KF416]). The builder's narrower predicate is not an
            // over-rejection, so there is nothing for the checker to inherit either way.)*
            Map.entry("MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // The PROJECTED half of the same ban, and the same split: `Computation.build()` names it off
            // `ConditionReferences.projectedFieldPaths` over typed handles, while the checker reads the
            // projection out of a stored condition text and applies A12's containment predicate to what it
            // finds. Neither is stricter than A12.
            Map.entry("MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD",
                    Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // --- the temporal-declaration gates promoted into the typed surface on 2026-09-20 -------------
            //
            // These eight arrived together and share one disposition, so they are grouped rather than
            // restated eight times. The law each enforces is the same on both sides and reads a DECLARATION
            // the operand itself does not carry — a temporal field's declared format, its date precision, a
            // date-range's endpoint agreement — so the kit resolves it through `BoundModel` off typed
            // handles while the checker resolves it out of stored DM-JSON. Neither carrier can read the
            // other's input, which is what `SAME_LAW_SEPARATE_CARRIERS` names.
            //
            // The direction that would matter is a kit refusing what A12 admits, because the checker must
            // not inherit that. It is not the case here and it is not an argument: every one of these gates
            // has its accept/reject pair measured at the kernel — the extraction format gate in
            // `validate.laws.TemporalExtractionFormatGateLawsTest` and `DerivedTemporalOperandFormatLawsTest`
            // (KERNEL-FINDINGS kf426, kf432, kf442), the construction slots in
            // `DateConstructionComponentDeclarationLawsTest` and `TemporalExtractorComponentLawsTest`, the
            // date-range pair in `DateRangeComparabilityLawsTest` and `DateRangeOverlapParameterLawsTest`,
            // the precision pair in `ValueAsDateOperandPrecisionLawsTest` and `PartialDateAggregateLawsTest`
            // — and `UnbackedClientRefusalLawsTest` independently fails on any refusal the kernel does not
            // make. Re-read both sites before trusting this block: a later edit to either can change the
            // answer without touching this map.
            Map.entry("MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED", Disposition.SAME_LAW_SEPARATE_CARRIERS),
            Map.entry("MVK_EXTRACT_OP_NOT_ALLOWED_4_DATE_CONSTRUCT_WITH_4_PARAMS",
                    Disposition.SAME_LAW_SEPARATE_CARRIERS),
            Map.entry("MVK_INVALID_DATE_OR_TIME_EXTRACT_OP", Disposition.SAME_LAW_SEPARATE_CARRIERS),
            Map.entry("MVK_INVALID_COMPARE_TO_DATE_RANGE", Disposition.SAME_LAW_SEPARATE_CARRIERS),
            Map.entry("MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON",
                    Disposition.SAME_LAW_SEPARATE_CARRIERS),
            Map.entry("MVK_INVALID_DATE_TYPE", Disposition.SAME_LAW_SEPARATE_CARRIERS),
            Map.entry("MVK_INVALID_DATE_TYPE_FOR_OP", Disposition.SAME_LAW_SEPARATE_CARRIERS),
            Map.entry("MVK_PARTIAL_DATE_NOT_ALLOWED", Disposition.SAME_LAW_SEPARATE_CARRIERS),

            // Both estates judge `errorCodesToSuppress` against A12's one suppressible code, and they part on
            // exactly one spelling: the supported code padded with whitespace, which A12 admits because its
            // lexer discards the padding. `Computation.Builder` refuses it so the member keeps one spelling;
            // `SuppressedWarnings` classifies the rendered pragma with the checker's tokenizer and admits it,
            // which is the row that found the checker's old character test reporting a false error (KF298).
            // Elsewhere the builder cites this code only for a lone `MVK_`-shaped entry and withholds it.
            Map.entry("MVK_INVALID_SUPPRESSED_WARNING", Disposition.STRICTER_IN_JAVA));

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

    /**
     * The VOCABULARY, which names every A12 code by construction and so states none of them.
     *
     * <p>This guard's question is whether the two estates both ACT on a code, and it answers it by where the
     * code's text appears. {@code KernelCode} broke that proxy the day it landed: as one enum constant per
     * code it made all 306 "stated in the Java kits", and the intersection with the checker jumped from a
     * handful of real divergences to 143 rows of nothing. The same exclusion, for the same reason, is in
     * {@code ClientRefusalKernelBasisCensusTest}.
     */
    private static final String VOCABULARY = "core/src/main/java/io/github/mbackschat/a12/dm/core/KernelCode.java";

    private static Set<String> codesIn(List<String> relativeRoots) {
        Set<String> codes = new TreeSet<>();
        Path vocabulary = CHECKOUT.resolve(VOCABULARY);
        for (String relative : relativeRoots) {
            Path root = CHECKOUT.resolve(relative);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> !p.equals(vocabulary))
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
