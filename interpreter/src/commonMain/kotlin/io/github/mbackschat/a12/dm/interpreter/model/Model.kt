package io.github.mbackschat.a12.dm.interpreter.model

/**
 * The structured model the interpreter evaluates — the interpreter owns its types and does NOT parse
 * JSON (proposal §6/§7: a clean data contract, not a shared object model). The *consumer* builds a
 * [ModelDef] from its own source (dmtool from DM-JSON via Jackson; a multiplatform JSON loader is a
 * later, additive increment). Kernel-free, so the purity lint holds.
 */

/** Field kinds the empty-value asymmetry depends on — the miniature "metadata bridge" (proposal §10). */
enum class Kind { NUMBER, STRING, BOOLEAN, CONFIRM, DATE, TIME, DATE_RANGE }

/**
 * The evaluation reference clock — the "current point in time" the kernel reads for the clock constants
 * (`Today`, later `Now`). It is **injected**, not read from the platform: `commonMain` stays pure (no
 * `java.time`/`Date.now()`), and a deterministic value makes evaluation testable. The consumer supplies it
 * (dmtool from the JVM clock; a JS consumer from `Date`). A model that uses `Today` with no clock cannot be
 * evaluated — the rule is reported as unsupported, not crashed.
 *
 * @property today the current date as an ISO `yyyy-MM-dd` string (the value of `Today`).
 */
data class EvalClock(val today: String)

/** Value-vs-omission polarity of a fired message (kernel `IMessage.MessageType`). */
enum class MsgType { VALUE, OMISSION }

/**
 * A field's requiredness — the kernel's `IRequirednessConfig.Mode`. A required-but-empty field raises
 * the `mandatoryField` error and becomes **"unknown"** (KERNEL-FINDINGS §4): rules referencing it see
 * `Val.Unknown`, so the 3VL propagates it (a comparison goes unknown, but `… Or <true>` still fires). The
 * two modes differ on *when* the requirement applies (verified — the gotcha):
 * - [ABSOLUTE] (`absoluteOrRelativeToNextRepAncestor`): a field with **no** repeatable ancestor is
 *   **always** required (even on an empty document); under a repeatable ancestor it is required once that
 *   ancestor's row is instantiated (`GroupFilled(<lowestRepeatableAncestor>)`).
 * - [RELATIVE_TO_PARENT]: required only when the **immediate parent group is filled** — for a
 *   non-repeatable parent that means a descendant is filled; for a repeatable parent, the row exists.
 */
enum class Requiredness { NOT_REQUIRED, ABSOLUTE, RELATIVE_TO_PARENT }

/** Rule/finding severity — mirrors the kernel's ERROR / WARNING. */
enum class Severity { ERROR, WARNING }

/** A present field instance: its [path], 1-based repetition [reps] (one per path part), and [raw] value. */
data class ValuedField(val path: String, val reps: List<Int>, val raw: String)

/**
 * A field, its [Kind] (the metadata bridge that drives the empty-value asymmetry + formal errors), its
 * localized display [labels] (locale → label) — the source for the `$field$` display-name placeholder — and,
 * for a NUMBER, its [scale] (max fractional digits, the kernel's `maxFractionalDigits`), which decides the
 * decimal-places formal errors. The kernel resolves `$field$` to the field's label for the locale, falling
 * back to the field NAME when no label is defined (a runtime `ILabelProvider` concern — see
 * [ModelDef.labelOf] / KERNEL-FINDINGS §3). Our corpus (and every shipped A12 model surveyed) carries no
 * field labels, so `$field$` resolves to the name.
 */
data class FieldDef(
    val path: String,
    val kind: Kind,
    val labels: Map<String, String> = emptyMap(),
    val scale: Int? = null,
    /** Whether a NUMBER field permits negative values (the kernel's `hatVorzeichen`; `NumberType.positivesOnly`
     *  is the unsigned trigger). Drives the directional empty-operand polarity ([ModelDef.unsignedFields] →
     *  the [io.github.mbackschat.a12.dm.interpreter.eval.Document]'s `unsignedFields` → `EvalContext.signed`). */
    val signed: Boolean = true,
    /** The field's explicit requiredness (the kernel's `requirednessConfig.mode`). Index fields are
     *  *additionally* auto-required ([ModelDef.indexFields]); see [ModelDef.requirednessOf]. */
    val required: Requiredness = Requiredness.NOT_REQUIRED,
    /** The date/datetime/range rendering format (the kernel's `DateType.format` etc., e.g. `dd.MM.yyyy`) —
     *  used to canonicalize a non-ISO date value to ISO. Null for non-date fields. */
    val format: String? = null,
    /** A [Kind.DATE_RANGE] field's two-date separator (the kernel's `DateRangeType.rangeSeparator`, e.g. `-`). */
    val rangeSeparator: String? = null,
)

/**
 * One rule: an *error* condition (it FIRES when the condition holds — the polarity law) evaluated once
 * per row of its iteration scope [group]. [errorEntityRelPath] is the offending field, relative to the
 * group (e.g. `../DeliveryDate`). [messages] maps locale → template (for rendered text).
 */
data class RuleDef(
    val path: String,
    val group: String,
    val conditionText: String,
    val errorCode: String,
    val errorEntityRelPath: String,
    val severity: Severity = Severity.ERROR,
    val messages: Map<String, String> = emptyMap(),
)

/**
 * One computation: an expression evaluated over its iteration scope [group], its result assigned to
 * [targetPath]. The kernel's empty-as-0 applies — `[BaseFee]` over an absent base fee computes 0, not
 * nothing.
 */
data class ComputationDef(
    val targetPath: String,
    val group: String,
    val expressionText: String,
)

/**
 * A model: its fields (→ kinds), its rules, its computations, the set of **repeatable** group paths (a
 * rule/computation iterates over the repetition of the deepest repeatable group its non-aggregated field
 * refs touch), and the declared max repetition count per repeatable group (drives the omitted-tail
 * aggregate polarity — absent = unbounded). `declaredReps` keys are repeatable by construction, so they
 * fold into [repeatableGroups].
 */
data class ModelDef(
    val fields: List<FieldDef>,
    val rules: List<RuleDef>,
    val computations: List<ComputationDef> = emptyList(),
    val repeatableGroups: Set<String> = emptySet(),
    val declaredReps: Map<String, Int> = emptyMap(),
    /** A repeatable group's index-field paths (the kernel's `indexFieldName`). The kernel auto-requires an
     *  index field (`relativeToParent` semantics) on top of its explicit [FieldDef.required]; [requirednessOf]
     *  folds that in. */
    val indexFields: Set<String> = emptySet(),
) {
    val kinds: Map<String, Kind> = fields.associate { it.path to it.kind }
    val repeatable: Set<String> = repeatableGroups + declaredReps.keys

    /** Unsigned NUMBER fields (the kernel's `hatVorzeichen == false`) — the [io.github.mbackschat.a12.dm.interpreter.eval.Document]'s
     *  directional-polarity input ([FieldDef.signed]). */
    val unsignedFields: Set<String> = fields.filter { !it.signed }.map { it.path }.toSet()

    /** Each [Kind.DATE_RANGE] field's `(format, rangeSeparator)` — the [io.github.mbackschat.a12.dm.interpreter.eval.Document]'s
     *  format-aware range parsing input. */
    val rangeConfig: Map<String, Pair<String, String>> = fields
        .filter { it.kind == Kind.DATE_RANGE && it.format != null && it.rangeSeparator != null }
        .associate { it.path to (it.format!! to it.rangeSeparator!!) }

    /** Each [Kind.DATE] field's rendering format (its [FieldDef.format] or the ISO default) — the
     *  [io.github.mbackschat.a12.dm.interpreter.eval.Document]'s format-aware date-canonicalization input, and
     *  the formal-check's "valid for this format?" input ([dateFormatOf]). Locale-variant by reading the format. */
    val dateFormats: Map<String, String> = fields
        .filter { it.kind == Kind.DATE }
        .associate { it.path to (it.format ?: "yyyy-MM-dd") }

    /** The DATE field's declared format (ISO default), or null for a non-date field. */
    fun dateFormatOf(path: String): String? = dateFormats[path]

    /** Each NUMBER field's scale (`maxFractionalDigits`) — the formal-check input (decimal-places errors); the
     *  [io.github.mbackschat.a12.dm.interpreter.eval.Document] needs it to gate a formally-invalid value to Unknown. */
    val scales: Map<String, Int?> = fields.associate { it.path to it.scale }
    private val labelsByPath: Map<String, Map<String, String>> = fields.associate { it.path to it.labels }
    private val requiredByPath: Map<String, Requiredness> = fields.associate { it.path to it.required }

    /**
     * The **effective** requiredness of the field at [path] — its explicit [FieldDef.required] if set, else
     * [Requiredness.RELATIVE_TO_PARENT] for an [indexFields] member (the kernel auto-generates the index
     * field's mandatory rule with `relativeTo = parent`), else [Requiredness.NOT_REQUIRED].
     */
    fun requirednessOf(path: String): Requiredness {
        val explicit = requiredByPath[path] ?: Requiredness.NOT_REQUIRED
        return if (explicit != Requiredness.NOT_REQUIRED) explicit
        else if (path in indexFields) Requiredness.RELATIVE_TO_PARENT
        else Requiredness.NOT_REQUIRED
    }

    /** Every field path that is required (explicitly or as an index field) — the `mandatoryField` candidates. */
    val requiredFieldPaths: List<String> =
        fields.map { it.path }.filter { requirednessOf(it) != Requiredness.NOT_REQUIRED }

    /** The NUMBER field's declared scale (max fractional digits), or null when unknown / not a number. */
    fun scaleOf(path: String): Int? = scales[path]

    /**
     * The display string for the `$field$` placeholder: the field's [FieldDef.labels] entry for [locale]
     * when defined (and non-blank), else the field NAME — the last path segment. This mirrors the kernel's
     * `identifierToString` under an `ILabelProvider` that returns the model label or the field name (the
     * dmtool/`RuntimeLaws` provider does exactly this); with no label the kernel and the interpreter both
     * yield the name. (A blank label falls through to the name, matching the kernel's empty-text handling.)
     */
    fun labelOf(path: String, locale: String): String =
        labelsByPath[path]?.get(locale)?.takeIf { it.isNotBlank() } ?: path.trimEnd('/').substringAfterLast('/')
}

/**
 * One validation finding produced by [DmInterpreter.validateFull] — the interpreter's kernel-free twin
 * of the kernel's `IMessage`, carrying the same channels: the fired rule's [code], its [rulePath], the
 * offending-field pointer [errorPath] (path + repetition indices, [formatPointer] form), [severity], the
 * VALUE/OMISSION [type] polarity, the rendered [text], the [referenced] operand pointers, and (for an
 * OMISSION) [fillToFix] — the referenced fields that, if filled, would clear the error (the kernel's
 * `refOmissionErrorResponsible`).
 */
data class Message(
    val code: String,
    val rulePath: String,
    val errorPath: String,
    val severity: Severity,
    val type: MsgType,
    val text: String = "",
    val referenced: List<String> = emptyList(),
    val fillToFix: List<String> = emptyList(),
)

/**
 * The three-way verdict of a single-rule eval ([DmInterpreter.testRule]) — the `rule test` contract.
 * FIRED = a violation; PASSED = evaluated and clean; SUPPRESSED = never evaluated because a referenced
 * field is formally invalid (the kernel skips a rule whose operand is *unknown*).
 */
enum class Verdict { FIRED, PASSED, SUPPRESSED }

/** A field whose formally-invalid value suppressed a rule — the `rule test` `suppressedBy` entry. */
data class Suppression(val field: String, val formalErrorCode: String)

/** A rule the interpreter could not evaluate because it uses an unmodeled/external construct. */
data class UnsupportedRule(val rulePath: String, val reason: String)

/** The result of [DmInterpreter.validate]: the firings plus the rules reported [unsupported]. */
data class ValidationOutcome(
    val messages: List<Message>,
    val unsupported: List<UnsupportedRule>,
)

/** The result of [DmInterpreter.testRule] / [DmInterpreter.evalCandidate]. */
data class RuleResult(
    val rulePath: String,
    val verdict: Verdict,
    val message: String = "",
    val type: MsgType? = null,
    val suppressedBy: List<Suppression> = emptyList(),
)

/**
 * One computed-field outcome of a [DmInterpreter.compute] run — the kernel-free twin of the kernel's
 * computed-field buckets: VALUE (a concrete value, in [value]) / CLEARED (computed nothing) / ERRORED
 * (an operand/computation error).
 */
enum class ComputeOutcome { VALUE, CLEARED, ERRORED }

/** The [outcome] of computing one field at [path], with its [value] when [ComputeOutcome.VALUE]. */
data class Computed(
    val path: String,
    val outcome: ComputeOutcome,
    val value: String? = null,
)
