package io.github.mbackschat.a12.dm.interpreter.model

/**
 * The structured model the interpreter evaluates — the interpreter owns its types and does NOT parse
 * JSON (proposal §6/§7: a clean data contract, not a shared object model). The *consumer* builds a
 * [ModelDef] from its own source (dmtool from DM-JSON via Jackson; a multiplatform JSON loader is a
 * later, additive increment). Kernel-free, so the purity lint holds.
 */

/** Field kinds the empty-value asymmetry depends on — the miniature "metadata bridge" (proposal §10). */
enum class Kind { NUMBER, STRING, BOOLEAN, CONFIRM, DATE, TIME, DATE_RANGE, DATE_TIME }

/**
 * A [Kind.DATE] field's precision (the kernel's `DateType.datePrecision`). [FULL] is a complete date;
 * [DAY_OPTIONAL] permits the **day** to be omitted — entered as `00` in the day position (`00.06.2024`),
 * which is well-formed (NOT a format error) but is not a single concrete date. `ValueAsDate(field, FirstDay
 * \|LastDay)` resolves such a partial value to its earliest / latest concrete date. (Only these two are
 * modeled; a finer precision would map to FULL until a fixture needs it.)
 */
enum class DatePrecision { FULL, DAY_OPTIONAL }

/**
 * The evaluation reference clock — the "current point in time" the kernel reads for the clock constants
 * (`Today`, `Now`). It is **injected**, not read from the platform: `commonMain` stays pure (no
 * `java.time`/`Date.now()`), and a deterministic value makes evaluation testable. The consumer supplies it
 * (dmtool from the JVM clock; a JS consumer from `Date`). A model that uses a clock constant with no clock
 * (or `Now` with no [now]) cannot be evaluated — the rule is reported as unsupported, not crashed.
 *
 * @property today the current date as an ISO `yyyy-MM-dd` string (the value of `Today`).
 * @property now the current date-time as an ISO `yyyy-MM-dd'T'HH:mm:ss` string (the value of `Now`), or null
 *   when the consumer supplies only a date — then a `Now`-using rule is reported unsupported.
 */
data class EvalClock(val today: String, val now: String? = null)

/** Value-vs-omission polarity of a fired message (kernel `IMessage.MessageType`). */
enum class MsgType { VALUE, OMISSION }

/**
 * A **predefined custom field type**'s declarative validation — the kernel's `IPredefinedType` with
 * `ValidationStrategy.LENGTH_AND_PATTERN_CHECK`: a value is a valid instance iff its length is within
 * `[minLength, maxLength]` (each bound applies only when set) AND it FULLY matches [pattern] (when set). This
 * is the validation domain for `Valid(field, "Name")` / `Invalid(field, "Name")` ([PredefinedTypeRegistry]).
 *
 * The kernel makes predefined types a **runtime CLIENT SPI** (project-specific; the kernel core ships none —
 * KERNEL-FINDINGS §8), so the interpreter mirrors that: the consumer SUPPLIES the type definitions (a built-in
 * set in [PredefinedTypeRegistry.DEFAULT], extensible per consumer). A `Valid`/`Invalid` over an *unregistered*
 * type is reported **unsupported** (fail-fast), never silently no-op'd. (Checksum types — IBAN's mod-97 etc.,
 * the kernel's `PROVIDED_VALIDATION_CODE` strategy — are out of this declarative model; they'd need a
 * function-valued registry entry, a future extension.)
 */
data class PredefinedType(val minLength: Int? = null, val maxLength: Int? = null, val pattern: String? = null) {
    private val regex: Regex? = pattern?.let { Regex(it) }

    /** Whether [value] is a valid instance of this type — within the length bounds AND a full pattern match. */
    fun accepts(value: String): Boolean =
        (minLength == null || value.length >= minLength) &&
            (maxLength == null || value.length <= maxLength) &&
            (regex == null || regex.matches(value))
}

/**
 * The consumer-supplied registry of [PredefinedType]s, keyed by name — the interpreter's twin of the kernel's
 * `CustomFieldTypeService` client SPI. [DEFAULT] is the built-in common set the interpreter ships (no tax/gov
 * types per the corpus rule); a consumer (dmtool, a library user) may pass its own map (built-ins + project
 * types). Unknown name at evaluation → the rule is reported unsupported.
 */
object PredefinedTypeRegistry {
    /** The built-in common predefined types — currently `EMail` (an e-commerce-relevant, public-spec type;
     *  full-match local@domain.tld, no embedded whitespace). Extend here as common declarative types are added. */
    val DEFAULT: Map<String, PredefinedType> = mapOf(
        "EMail" to PredefinedType(minLength = 3, maxLength = 254, pattern = "[^@\\s]+@[^@\\s]+\\.[^@\\s]+"),
    )
}

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

/** Rule/finding severity — mirrors the kernel's `IRule.RuleSeverityType` (ERROR / WARNING / INFO). Severity is
 *  **UI classification only**; it does not change whether a rule fires or its message is emitted (probe-verified:
 *  an INFO rule fires + emits at `validateFull` exactly like ERROR — KERNEL-FINDINGS line 987 / IF23). INFO must
 *  be modeled or the loader's `Severity.valueOf("INFO")` throws on a valid model (the IF23 loud crash). */
enum class Severity { ERROR, WARNING, INFO }

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
    /** A [Kind.DATE] field's precision (the kernel's `DateType.datePrecision`); [DatePrecision.DAY_OPTIONAL]
     *  permits a day-omitted `00.MM.yyyy` value. Default [DatePrecision.FULL]. */
    val datePrecision: DatePrecision = DatePrecision.FULL,
    /** For an ENUMERATION field: its XML values in declaration order (the kernel's `getXMLValues`) — the
     *  positional key for `EnumCategory` and the membership domain. Empty for non-enum fields. */
    val enumValues: List<String> = emptyList(),
    /** For an ENUMERATION field: each category's positional value list (the kernel's `getCategoryValues`),
     *  keyed by category name — index `i` pairs with [enumValues]`[i]`. Drives `[Field -> Category]`. */
    val enumCategories: Map<String, List<String>> = emptyMap(),
    /** For a CUSTOM-typed field (`CustomFieldType{name}`): the predefined-type NAME its filled values are
     *  validated against — the [ModelDef] resolves it via the consumer's [PredefinedTypeRegistry], so a declared
     *  custom type is the implicit per-cell form of `Valid(field, name)` (KERNEL-FINDINGS "Custom-extensibility
     *  surface"). Null for a non-custom field; the kind stays [Kind.STRING] (the value is string-shaped). */
    val customTypeName: String? = null,
    // ── Declared field-type CONSTRAINTS (IF20) — auto-validated against a filled value (KERNEL-FINDINGS §3). ──
    /** A STRING field's declared minimum length (`StringType.minLength`); a shorter value → `stringZuKurz`. */
    val minLength: Int? = null,
    /** A STRING field's declared maximum length (`StringType.maxLength`); a longer value → `stringZuLang`. */
    val maxLength: Int? = null,
    /** A STRING field's declared regex (`StringType.pattern`); a non-matching value → `stringFalschesMuster`. */
    val pattern: String? = null,
    /** A NUMBER field's declared minimum value (`NumberType.minValue`), raw text; below it → `zahlZuKlein`. */
    val minValue: String? = null,
    /** A NUMBER field's declared maximum value (`NumberType.maxValue`), raw text; above it → `zahlZuGross`. */
    val maxValue: String? = null,
    /** A NUMBER field's declared minimum fractional digits (`NumberType.minFractionalDigits`); too few →
     *  `zahlFalscheAnzahlNachkommastellen`, none at all → `zahlOhneDezimalTrenner`. */
    val minFractionalDigits: Int? = null,
    /** STRING `lineBreaksPermitted` — default false (the kernel forbids line breaks → `stringMitNewline`). */
    val lineBreaksPermitted: Boolean = false,
    /** NUMBER `leadingZerosAllowed` — default false (the kernel forbids leading zeros → `zahlHatFuehrendeNullen`). */
    val leadingZerosAllowed: Boolean = false,
    /** NUMBER `zeroNotAllowed` — default false (zero allowed); true → a zero value is `zahlIstNull`. */
    val zeroNotAllowed: Boolean = false,
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
 * One TIERED alternative of a [ComputationDef]: an optional [precondition] (DSL condition text) gating an
 * [operation] (DSL value-expression text). The kernel selects the first alternative whose precondition holds
 * (a null precondition always applies — the unconditional default), KERNEL-FINDINGS §2 "tiered computations".
 */
data class ComputationAlternative(val precondition: String?, val operation: String)

/**
 * The declared field-type constraints the kernel auto-validates against a filled value (IF20) — string length
 * + pattern, enum value-domain membership, number range, and the minimum-fractional-digits rule. Built per
 * field from the DM-JSON field type; [NONE] for an unconstrained field. The compiled [patternRegex] is cached.
 * (`zeroAllowed` / `maxNumberOfDigitsBeforeDecimalPlace` are runtime-only INumberType qualities with no DM-JSON
 * serialization — unreachable from a loadable model — so they are out of scope; see INTERPRETER-FINDINGS IF20.)
 */
data class FieldConstraints(
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    /** An ENUM field's value domain (the kernel's `getXMLValues`); a filled value outside it is `stringFalschesMuster`. */
    val enumValues: List<String> = emptyList(),
    /** The declared `minValue` / `maxValue`, raw DM-JSON text — parsed to `Dec` for an exact, scale-aware compare. */
    val minValue: String? = null,
    val maxValue: String? = null,
    val minFractionalDigits: Int? = null,
    /** STRING: whether line breaks are permitted. **Default false** = the kernel forbids them (`stringMitNewline`);
     *  `true` is the non-default that allows them. So [NONE] (false) reproduces the kernel default for a plain field. */
    val lineBreaksPermitted: Boolean = false,
    /** NUMBER: whether leading zeros are permitted. **Default false** = the kernel forbids them
     *  (`zahlHatFuehrendeNullen`); `true` allows. [NONE] (false) reproduces the kernel default. */
    val leadingZerosAllowed: Boolean = false,
    /** NUMBER: whether a zero value is forbidden (`zahlIstNull`). Default false = zero allowed. */
    val zeroNotAllowed: Boolean = false,
) {
    val patternRegex: Regex? = pattern?.let { Regex(it) }

    /** Whether this field carries any NON-DEFAULT constraint, so it must be stored (else [NONE] — whose false
     *  booleans already encode the kernel defaults — is used). The `*Allowed`/`*NotAllowed` flags count only when
     *  `true` (the non-default); the default-forbidden newline/leading-zeros checks still run via [NONE]. */
    val any: Boolean = minLength != null || maxLength != null || pattern != null ||
        enumValues.isNotEmpty() || minValue != null || maxValue != null || (minFractionalDigits ?: 0) > 0 ||
        lineBreaksPermitted || leadingZerosAllowed || zeroNotAllowed

    companion object {
        val NONE = FieldConstraints()
    }
}

/**
 * One computation: a list of [alternatives] (each an optional precondition + an operation), optionally under a
 * whole-computation [commonPrecondition], evaluated over its iteration scope [group], its result assigned to
 * [targetPath]. The kernel evaluates the alternatives **in order** and the FIRST whose precondition holds
 * supplies the value; if [commonPrecondition] is present and not satisfied, or no alternative's precondition
 * holds, the field is **cleared** (computes nothing). The kernel's empty-as-0 applies inside an operation —
 * `[BaseFee]` over an absent base fee computes 0, not nothing.
 *
 * The common case — a single unconditional operation — has the [expressionText] secondary constructor as sugar
 * (so a hand-built model or a non-tiered computation reads `ComputationDef(target, group, "[A] + [B]")`).
 */
data class ComputationDef(
    val targetPath: String,
    val group: String,
    val alternatives: List<ComputationAlternative>,
    val commonPrecondition: String? = null,
) {
    /** Sugar for the common single-unconditional-operation computation (no precondition, no common guard). */
    constructor(targetPath: String, group: String, expressionText: String) :
        this(targetPath, group, listOf(ComputationAlternative(null, expressionText)))
}

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
    /** The model's configured base year (the kernel's `modelInfo.baseYear`), or null when none is declared —
     *  the value of the `BaseYear` point-in-time constant (a model that uses `BaseYear` without one is a
     *  kernel `MVK_NO_BASE_YEAR` error, so a valid model always carries it). */
    val baseYear: Int? = null,
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

    /** Each [Kind.DATE] field's [DatePrecision] — the partial-date (`DAY_OPTIONAL`) input for the formal check
     *  and `ValueAsDate`. */
    val datePrecisions: Map<String, DatePrecision> = fields
        .filter { it.kind == Kind.DATE }
        .associate { it.path to it.datePrecision }

    /** The DATE field's precision ([DatePrecision.FULL] default), for the format/partial-date checks. */
    fun datePrecisionOf(path: String): DatePrecision = datePrecisions[path] ?: DatePrecision.FULL

    /** Each [Kind.DATE_TIME] field's rendering format (the kernel's `DateTimeType.format`, e.g.
     *  `yyyy-MM-dd'T'HH:mm:ss`) — the [io.github.mbackschat.a12.dm.interpreter.eval.Document]'s format-aware
     *  date-time-canonicalization input (ISO date-time default when unset). Locale-variant by reading the format. */
    val dateTimeFormats: Map<String, String> = fields
        .filter { it.kind == Kind.DATE_TIME }
        .associate { it.path to (it.format ?: "yyyy-MM-dd'T'HH:mm:ss") }

    /** Each [Kind.TIME] field's rendering format (the kernel's `TimeType.format`, e.g. `HH:mm:ss`) — the
     *  formal-check's "valid time for this format?" input (a malformed time → `datumFormatFalsch` + Unknown). */
    val timeFormats: Map<String, String> = fields
        .filter { it.kind == Kind.TIME }
        .associate { it.path to (it.format ?: "HH:mm:ss") }

    /** The field's date-ish rendering format for the formal check — DATE/TIME/DATE_TIME/DATE_RANGE, else null.
     *  (The single accessor the formal pass uses so a malformed value of any temporal kind is format-checked.) */
    fun dateLikeFormatOf(path: String): String? = when (kinds[path]) {
        Kind.DATE -> dateFormats[path]
        Kind.TIME -> timeFormats[path]
        Kind.DATE_TIME -> dateTimeFormats[path]
        Kind.DATE_RANGE -> rangeConfig[path]?.first
        else -> null
    }

    /** The DATE_RANGE field's separator (the formal check splits the value on it), else null. */
    fun rangeSeparatorOf(path: String): String? = rangeConfig[path]?.second

    /** Each NUMBER field's scale (`maxFractionalDigits`) — the formal-check input (decimal-places errors); the
     *  [io.github.mbackschat.a12.dm.interpreter.eval.Document] needs it to gate a formally-invalid value to Unknown. */
    val scales: Map<String, Int?> = fields.associate { it.path to it.scale }

    /** Each field's declared-constraint set (IF20) — the per-cell formal-check input for length/pattern/enum-
     *  domain/range/min-decimals. Only fields that declare a constraint appear; [constraintsOf] returns NONE
     *  otherwise (so the constraint pass is a no-op for an unconstrained field). */
    val fieldConstraints: Map<String, FieldConstraints> = fields
        .associate {
            it.path to FieldConstraints(
                it.minLength, it.maxLength, it.pattern, it.enumValues, it.minValue, it.maxValue, it.minFractionalDigits,
                it.lineBreaksPermitted, it.leadingZerosAllowed, it.zeroNotAllowed,
            )
        }
        .filterValues { it.any }

    /** The field's declared constraints (IF20), or [FieldConstraints.NONE] when it declares none. */
    fun constraintsOf(path: String): FieldConstraints = fieldConstraints[path] ?: FieldConstraints.NONE

    /** Each CUSTOM-typed field's predefined-type name ([FieldDef.customTypeName]) — the per-cell auto-validation
     *  input: a filled value is validated against the consumer's [PredefinedTypeRegistry] entry of that name,
     *  exactly like `Valid(field, name)` (the kernel's client-SPI custom field type). */
    val customTypes: Map<String, String> = fields
        .filter { it.customTypeName != null }
        .associate { it.path to it.customTypeName!! }

    /** The field's declared custom-type name, or null when it is not a custom-typed field. */
    fun customTypeOf(path: String): String? = customTypes[path]

    /** Each enumeration field's XML value list — the [io.github.mbackschat.a12.dm.interpreter.eval.Document]'s
     *  `EnumCategory` positional-map input (the index of the stored value). */
    val enumValues: Map<String, List<String>> = fields
        .filter { it.enumValues.isNotEmpty() }
        .associate { it.path to it.enumValues }

    /** Each enumeration field's category map (name → positional value list) — the
     *  [io.github.mbackschat.a12.dm.interpreter.eval.Document]'s `EnumCategory` mapping input. */
    val enumCategories: Map<String, Map<String, List<String>>> = fields
        .filter { it.enumCategories.isNotEmpty() }
        .associate { it.path to it.enumCategories }
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
