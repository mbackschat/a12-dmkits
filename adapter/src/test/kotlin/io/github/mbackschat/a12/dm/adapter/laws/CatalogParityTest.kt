package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.perf.PerfCatalog
import io.github.mbackschat.a12.dm.interpreter.eval.ConstantOp
import io.github.mbackschat.a12.dm.interpreter.eval.FunctionOp
import io.github.mbackschat.a12.dm.interpreter.eval.PredicateOp
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The CATALOG-PARITY METER (harness C — SEED-GENERATOR-FUZZ-SPEC.md "the probe-first loop"). It turns
 * **"which operators are left"** from a hand-maintained checklist into a DERIVED build guard over three
 * always-current sources:
 *
 *  - the **catalog** ([OperatorCatalog] / `operators.json` — every kernel construct, the universe),
 *  - what the interpreter **HANDLES** (the live [PredicateOp] / [ConstantOp] / [FunctionOp] enums — drift-free,
 *    read at runtime — plus the small set of AST-level constructs the parser dispatches directly), and
 *  - what the differential **FUZZ NET** exercises ([PerfCatalog.fuzzConditions]).
 *
 * It fails when a catalog construct is **unclassified** (the kernel added an operator, or one was implemented
 * without being recorded here) and when a handled callable is **un-fuzzed** without an explicit deferral —
 * the two ways operator coverage silently rots. [DEFERRED] IS the operator backlog (derived-checked, never
 * stale); [EXCLUDED] is the not-a-pure-engine-op set. Same "mine constraints proactively, CI-locked"
 * discipline as the MVK / RT-semantics / grammar ledgers (CLAUDE.md "Anti-whack-a-mole").
 */
class CatalogParityTest {

    private val catalog = OperatorCatalog.load()
    private val catalogIds: Set<String> = catalog.entries().map { it.id() }.toSet()

    /** Constructs dispatched through the closed enum registries (drift-free — read from the live enums). */
    private val enumHandled: Set<String> =
        (PredicateOp.entries.map { it.kernelName } +
            ConstantOp.entries.map { it.kernelName } +
            FunctionOp.entries.map { it.kernelName }).toSet()

    /**
     * Constructs the parser/evaluator handles as AST NODES (not enum-dispatched) — boolean combinators,
     * comparisons, the arithmetic umbrella, the pattern tests, and the boolean literals.
     */
    private val astHandled: Set<String> = setOf(
        "And", "Or",                                                        // Cond combinators
        "LessThan", "LessThanOrEqual", "GreaterThan", "GreaterThanOrEqual", // Cond.Cmp / CmpOp
        "Equal", "NotEqual",
        "Arithmetic",                                                       // Expr.Arith / ArithOp (+ - * / ^)
        "PatternMatched", "PatternViolated",                               // Cond.Pattern
        "DiffersWithToleranceRange1", "DiffersWithToleranceRange2",        // Cond.Tolerance (|a−b| > N)
        "DiffersWithToleranceRange5", "DiffersWithToleranceRange10",
        "AtLeastOneFieldValueIncludedInValueList", "NoFieldValueIncludedInValueList",  // Cond.ValueListQuant (shape B)
        "NotAllFieldValuesIncludedInValueList",
        "True", "False",                                                   // Expr.BoolLit
    )

    private val handled: Set<String> = enumHandled + astHandled

    /** Callables (predicates + functions) the interpreter handles — the set the fuzz net should reach. */
    private val handledCallables: Set<String> =
        (PredicateOp.entries.map { it.kernelName } + FunctionOp.entries.map { it.kernelName }).toSet()

    /** The operator backlog — constructs not yet handled, each with a reason. This IS "what's left to build". */
    private val deferred: Map<String, String> = buildMap {
        group(
            "date/time family — needs the date-time/partial-date foundation extended past the Now constant; the " +
                "Feb-28 leap time-clearing must be replicated when AddYears/AddMonths gain DATE_TIME operands",
            "BaseYear", "Date", "Time", "DateTime",
            "AddHours", "AddMinutes", "AddSeconds",
            "DifferenceInHours", "DifferenceInMinutes", "DifferenceInSeconds",
            "DateRange", "ValueAsDate", "FirstDay", "LastDay",
        )
        group(
            "repetition uniqueness — across-row key uniqueness of a repetition (distinct from the handled " +
                "scalar FieldValuesNotUnique and the index-field uniqueIndex check, IF15)",
            "RepetitionNotUnique", "FromScope",
        )
        group(
            "path-ops / scope — operate on the path/repetition selection, not as a standalone predicate/function",
            "EnumCategory", "Having", "SemanticIndex",
        )
        group("date-construction validity predicates — pair with the Date/Time constructors above", "Valid", "Invalid")
        group("predefined-type validity predicates — need the model's predefined-type registry", "ValidType", "InvalidType")
    }

    /** Constructs that are NOT pure-engine operators (out of scope by nature), each with its re-open trigger. */
    private val excluded: Map<String, String> = mapOf(
        "CustomCondition" to "delegates the fire/don't-fire decision to host code — not deterministically " +
            "evaluable by a pure engine; revisit only if a host-callback seam is added to the interpreter",
    )

    /**
     * Handled callables NOT yet in the differential fuzz net, each with a reason. Each is `*DiffTest`-locked
     * (curated edge cases); this list is the **random-combination** coverage gap. It shrinks as fuzz rules are
     * added, and the guard FAILS if a newly-handled callable ships without either a fuzz rule or an entry
     * here — the forcing function that keeps the net growing with the operator set.
     */
    private val fuzzPending: Map<String, String> = buildMap {
        group(
            "field/group-list quantifier siblings — same 3VL (t,f) tally as the fuzzed AllFieldsFilled (AF rule); DiffTest-locked",
            "FieldNotFilled", "NoFieldFilled", "AtLeastOneFieldFilled", "MoreThanOneFieldFilled",
            "NotAllFieldsFilled", "NotExactlyOneFieldFilled", "FieldsNotCollectivelyFilled",
            "GroupFilled", "GroupNotFilled", "AllGroupsFilled", "NoGroupFilled",
            "AtLeastOneGroupFilled", "NotAllGroupsFilled", "GroupsNotCollectivelyFilled",
        )
        group(
            "value-list scalar predicates — FieldValueIncludedInValueList / its negation; ValueListDiffTest-locked",
            "FieldValueIncludedInValueList", "FieldValueNotIncludedInValueList",
        )
        group("date-range overlap predicates — DateRangeDiffTest-locked; need date-range fields in the fuzz model",
            "DateRangesOverlap", "AtLeastOneDateRangeOverlaps")
        group(
            "numeric aggregates not yet in the net — Sum is fuzzed (TP); each is AggregateDiffTest-locked",
            "Min", "Max", "MaxValue", "MinValue", "NumberOfDifferentValues",
            "NumberOfFilledFields", "NumberOfFilledGroups",
        )
        group(
            "date extractors/arithmetic — YearFromDate is fuzzed (YO/YD); the family is mechanically identical, each DiffTest-locked",
            "MonthFromDate", "DayFromDate", "QuarterFromDate",
            "HoursFromTime", "MinutesFromTime", "SecondsFromTime",
            "AddDays", "AddMonths", "AddYears",
            "DifferenceInDays", "DifferenceInMonths", "DifferenceInYears",
            "StartOfDateRange", "EndOfDateRange",
        )
        group("date-time extractors — DateTimeExtractDiffTest-locked; need a DATE_TIME field in the fuzz model",
            "DateFromDateTime", "TimeFromDateTime")
        group(
            "round/abs *Value-field + coercion siblings — RoundDown/RoundAccounting/RoundUpValue + Abs are fuzzed; each DiffTest-locked",
            "RoundUp", "RoundDownValue", "RoundAccountingValue", "AbsValue", "FieldValueAsNumber",
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * EVERY catalog construct lands in EXACTLY ONE bucket — handled, deferred, or excluded. The high-value
     * guard: an unclassified id means the kernel added an operator (or one shipped unrecorded); a construct
     * in two buckets means a classification is stale (a handled op still marked deferred — the rot the owner
     * rules warn about).
     */
    @Test
    fun everyConstructIsClassifiedExactlyOnce() {
        val unclassified = catalogIds - handled - deferred.keys - excluded.keys
        assertTrue(unclassified.isEmpty()) {
            "Catalog constructs with no classification — implement them, or add to DEFERRED/EXCLUDED with a reason:\n  " +
                unclassified.sorted().joinToString("\n  ")
        }
        val handledAndDeferred = handled intersect deferred.keys
        val handledAndExcluded = handled intersect excluded.keys
        val deferredAndExcluded = deferred.keys intersect excluded.keys
        assertTrue(handledAndDeferred.isEmpty() && handledAndExcluded.isEmpty() && deferredAndExcluded.isEmpty()) {
            "Constructs classified in two buckets (a handled op still marked deferred/excluded is STALE — promote it):\n" +
                "  handled∩deferred: ${handledAndDeferred.sorted()}\n" +
                "  handled∩excluded: ${handledAndExcluded.sorted()}\n" +
                "  deferred∩excluded: ${deferredAndExcluded.sorted()}"
        }
    }

    /**
     * Every name we classify must be a REAL catalog id — catches a typo, or a kernel-renamed/removed op still
     * referenced by an enum or an allowlist.
     */
    @Test
    fun everyClassifiedNameIsARealConstruct() {
        val orphans = mapOf(
            "enum kernelName" to (enumHandled - catalogIds),
            "astHandled" to (astHandled - catalogIds),
            "deferred" to (deferred.keys - catalogIds),
            "excluded" to (excluded.keys - catalogIds),
            "fuzzPending" to (fuzzPending.keys - catalogIds),
        ).filterValues { it.isNotEmpty() }
        assertTrue(orphans.isEmpty()) {
            "Names classified that are not catalog ids (a typo, or the kernel renamed/removed them):\n" +
                orphans.entries.joinToString("\n") { "  ${it.key}: ${it.value.sorted()}" }
        }
    }

    /**
     * Every handled callable is either in the fuzz net OR explicitly fuzz-pending — so a newly-handled op
     * cannot ship without random-combination coverage or a recorded reason.
     */
    @Test
    fun everyHandledCallableIsFuzzedOrPending() {
        val missing = handledCallables - fuzzedConstructs() - fuzzPending.keys
        assertTrue(missing.isEmpty()) {
            "Handled operators not in the fuzz net — add a rule to PerfCatalog.fuzz(), or list in fuzzPending with a reason:\n  " +
                missing.sorted().joinToString("\n  ")
        }
    }

    /** A fuzz-pending entry that is NOW fuzzed is stale — remove it (mirrors the no-stale-classification rule). */
    @Test
    fun fuzzPendingIsNotStale() {
        val nowFuzzed = fuzzPending.keys intersect fuzzedConstructs()
        assertTrue(nowFuzzed.isEmpty()) {
            "Operators listed as fuzz-pending are actually in the fuzz net now — remove them from fuzzPending:\n  " +
                nowFuzzed.sorted().joinToString("\n  ")
        }
    }

    /** The fuzz net must reference only HANDLED constructs — a fuzz rule using an unhandled op would crash. */
    @Test
    fun fuzzNetReferencesOnlyHandledConstructs() {
        val unhandledInFuzz = fuzzedConstructs() - handled
        assertTrue(unhandledInFuzz.isEmpty()) {
            "Fuzz conditions reference constructs the interpreter does not handle:\n  " +
                unhandledInFuzz.sorted().joinToString("\n  ")
        }
    }

    /** Prints the coverage meter — the "what's left" snapshot, derived not hand-counted. */
    @Test
    fun coverageMeter() {
        val handledInCatalog = handled intersect catalogIds
        val fuzzed = handledCallables intersect fuzzedConstructs()
        println(
            "[catalog-parity] ${catalogIds.size} constructs: " +
                "${handledInCatalog.size} handled, ${deferred.size} deferred, ${excluded.size} excluded",
        )
        println(
            "[catalog-parity] fuzz net: ${fuzzed.size}/${handledCallables.size} handled callables in the " +
                "random-combination net (${fuzzPending.size} pending)",
        )
        assertTrue(handledInCatalog.size + deferred.size + excluded.size == catalogIds.size) {
            "the three buckets must exactly partition the catalog (see everyConstructIsClassifiedExactlyOnce)"
        }
    }

    /**
     * Catalog callables (a `<name>(…)` form) the fuzz conditions actually use. Matched on `name(` with a
     * **left word-boundary** so neither a longer prefix (`Sum(` is not in `SumOfProducts(`) nor a longer
     * suffix (`Date(` is not in `YearFromDate(`) can false-match.
     */
    private fun fuzzedConstructs(): Set<String> {
        val conditions = PerfCatalog.fuzzConditions()
        return catalogIds.filter { id ->
            val call = Regex("(?<![A-Za-z0-9_])" + Regex.escape(id) + "\\(")
            conditions.any { call.containsMatchIn(it) }
        }.toSet()
    }

    private companion object {
        /** Put each [ids] → the shared [reason] — one grouped, reasoned allowlist entry. */
        fun MutableMap<String, String>.group(reason: String, vararg ids: String) {
            ids.forEach { put(it, reason) }
        }
    }
}
