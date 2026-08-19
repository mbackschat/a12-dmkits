package io.github.mbackschat.a12.dm.interpreter.model

import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer
import io.github.mbackschat.a12.dm.interpreter.messagePointerHouseForm

import io.github.mbackschat.a12.dm.interpreter.regex.PatternRegistry
import io.github.mbackschat.a12.dm.interpreter.regex.compilePattern

/**
 * The structured model the interpreter evaluates — the interpreter owns its types (a clean data contract,
 * not a shared object model; proposal §6/§7). A consumer can build a [ModelDef] directly, or load one from
 * expanded DM-JSON with the kernel-free, multiplatform
 * [io.github.mbackschat.a12.dm.interpreter.load.ModelLoader]. Kernel-free, so the purity lint holds.
 */

/** Field kinds the empty-value asymmetry depends on — the miniature "metadata bridge" (proposal §10). */
enum class Kind { NUMBER, STRING, BOOLEAN, CONFIRM, DATE, TIME, DATE_RANGE, DATE_TIME }

/**
 * How complete a [Kind.DATE] field's value is — the kernel's "teilbekannt" (partially-known) axis, which the
 * kernel unifies for a `DateType`'s optional day AND a `DateFragmentType`'s missing components:
 * - [FULL] — a complete `DateType` (all of year/month/day present & required).
 * - [DAY_OPTIONAL] — a `DateType` whose **day** may be omitted (entered as `00`, `00.06.2024`).
 * - [MONTH_OPTIONAL] — widens that domain so month may also be omitted, but only after day (`00.00.2024`).
 * - [YEAR_OPTIONAL] — widens it again so year may be omitted only after day and month (`00.00.0000`).
 *   Every partial value is well-formed but not a single concrete date, so a direct value resolution is
 *   `Val.Unknown`; `ValueAsDate(field, FirstDay\|LastDay)` selects its concrete boundary unless the year is
 *   unknown, which remains non-relevant.
 * - [FRAGMENT] — a `DateFragmentType`: the field's format itself carries only a proper SUBSET of the date
 *   components (`getFormatOfFragment` ∈ {`yyyy-MM`, `yyyy`, `MM`, `MM-dd`}). Unlike [DAY_OPTIONAL], a
 *   fragment DOES resolve for a direct comparison: the kernel collapses it to a plain `Date` by completing the
 *   missing components to the **earliest** concrete date (its `feldWertAlsDatum` INTERVAL_START rule — missing
 *   month → `01`, missing day → `01`, missing year → the model base year), then compares/shifts/extracts on
 *   that. The *which* components are present is carried by the field's [FieldDef.format] (the format string),
 *   so this one marker + the format drives the completion; both kernel strategies share the same completion,
 *   so the interpreter matches both. The comparability partition (`MVK_INVALID_COMPARE_TO_DATE`) and the
 *   `Add*`/extract granularity gating (`MVK_WRONG_DATE_FORMAT_FOR_OP`) are kernel authoring-time gates — a
 *   valid model carries only legal combos, so the interpreter evaluates only those and does not re-enforce
 *   the gates (KERNEL-FINDINGS §6 "a date fragment IS a DateOperand").
 */
enum class DatePrecision { FULL, DAY_OPTIONAL, MONTH_OPTIONAL, YEAR_OPTIONAL, FRAGMENT }

/**
 * A DATE_RANGE's `interpretationOfYear` (the kernel's `Jahresinterpretation`). It exists for the one shape
 * whose endpoints do not share a year: a WRAPPING yearless range such as `01.11-31.01`. [STANDARD] — the
 * default, and the only state the declaration expresses by OMITTING the property — completes both endpoints
 * with the base year, so a wrapping value is simply start-after-end. The other two say WHICH endpoint the
 * base year labels, and the opposite endpoint moves: under [FROM] the base year is the range's from-year and
 * the END rolls forward one, under [TO] it is the to-year and the START rolls back one. Only when the value
 * actually wraps.
 *
 * Measured 2026-08-19: `FROM` and `TO` are the accepted DM-JSON spellings; the tool API's
 * `STANDARD`/`PREVIOUS`/`SUBSEQUENT` are all refused by the model deserializer.
 */
enum class RangeYearInterpretation { STANDARD, FROM, TO }

/**
 * The evaluation reference clock — the "current point in time" the kernel reads for the clock constants
 * (`Today`, `Now`). It is **injected**, not read from the platform: `commonMain` stays pure (no
 * `java.time`/`Date.now()`), and a deterministic value makes evaluation testable. The consumer supplies it
 * (dmtool from the JVM clock; a JS consumer from `Date`). A model that uses a clock constant with no clock
 * (or `Now` with no [now]) cannot be evaluated — the rule is reported as unsupported, not crashed.
 *
 * @property today the current date as an ISO `yyyy-MM-dd` string (the value of `Today`).
 * @property now the current date-time as an ISO `yyyy-MM-dd'T'HH:mm:ss` string, optionally followed by an
 *   exact three-digit millisecond fraction (`.SSS`). Evaluation retains that fraction as `Now`'s instant
 *   identity while all authored/stored temporal rendering remains whole-second. Null means the consumer
 *   supplies only a date — then a `Now`-using rule is reported unsupported.
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
 * type is reported **unsupported** (fail-fast), never silently no-op'd. Checksum types — IBAN's mod-97 etc.,
 * the kernel's `PROVIDED_VALIDATION_CODE` strategy — are intentionally outside this declarative model; consumers
 * register them through the function-valued [CustomFieldType] SPI.
 */
data class PredefinedType(val minLength: Int? = null, val maxLength: Int? = null, val pattern: String? = null) {
    /** Whether [value] is a valid instance of this type — within the length bounds AND a full pattern match. */
    fun accepts(value: String): Boolean =
        accepts(value) { source, candidate -> compilePattern(source).matches(candidate) }

    internal fun accepts(value: String, patterns: PatternRegistry): Boolean =
        accepts(value, patterns::matches)

    private fun accepts(value: String, matches: (String, String) -> Boolean): Boolean =
        (minLength == null || value.length >= minLength) &&
            (maxLength == null || value.length <= maxLength) &&
            (pattern == null || matches(pattern, value))
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
 * The project-specific rejection a [CustomFieldType] returns for an invalid value — the interpreter's twin of the
 * kernel's `ICustomFieldTypeCheckError` (IF150). [code] is the **machine-observable** rejection key (the kernel's
 * `getErrorKey()`) that becomes the cell's formal error code — a consumer/downstream contract, NOT interpreter-
 * chosen wording, so it is carried verbatim. [message] is the optional consumer-supplied template: every
 * `$<fieldName>$` placeholder is replaced with the localized field label before it is emitted. The interpreter
 * falls back to its own wording when null — the German kernel bundle stays out, but a consumer's OWN message may
 * surface.
 */
data class CustomTypeRejection(val code: String, val message: String? = null)

/**
 * The context supplied to a [CustomFieldType] for one value check — the interpreter twin of the kernel's
 * `ICustomFieldTypeValidationParam` plus its separate `isDisplayValue` argument.
 *
 * [locale] is the validation call's error-message locale. [minLength] and [maxLength] are the effective bounds:
 * a declaration's raw nullable values become the kernel defaults `1` and `999` when omitted. They are validator
 * inputs, not generic string constraints.
 * [isDisplayValue] is always false at the supported stored-document runtime boundary: both kernel strategies
 * receive canonical values there, and only the lower-level presentation-validation API can make it true.
 */
data class CustomFieldValidationContext(
    val locale: String,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val isDisplayValue: Boolean = false,
)

/**
 * The consumer SPI for a CODE-BASED custom field type — the kernel's `ICustomFieldValidator` client surface, for
 * an imperative type whose validity is *logic*, not a format (a checksum: IBAN mod-97, ISIN/Luhn check digit). The
 * declarative [PredefinedType] (length/pattern) covers the format majority; this covers the imperative tail.
 *
 * **Dispatched ALONGSIDE [PredefinedType], never replacing it** (the [DmInterpreter]/`Interpreter` consult a
 * code-based type only AFTER the declarative registry misses): the declarative per-cell path stays a monomorphic,
 * JIT-inlined `accepts` call, and this polymorphic [check] is reached only for a code-backed type — a consumer
 * who registers none pays nothing. The hot (accepting) case returns `null` (no allocation); a rejection allocates
 * the [CustomTypeRejection]. This is the INTERPRETER-PERFORMANCE "hot path stays a monomorphic concrete call"
 * rule (locked by `CustomTypePerfTest`).
 */
fun interface CustomFieldType {
    /** @return null when [value] is a valid instance of this type under [context]; a [CustomTypeRejection] (carrying the
     *  project-specific error code, the kernel's `getErrorKey()`) when it is rejected — so an INVALID value fires
     *  that project code, not a fixed constant (IF150). */
    fun check(value: String, context: CustomFieldValidationContext): CustomTypeRejection?
}

/** A field's `CustomFieldType` declaration: registered validator [name] plus optional validator length hints. */
data class CustomFieldTypeDeclaration(
    val name: String,
    val minLength: Int? = null,
    val maxLength: Int? = null,
)

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
 * localized display [labels] (locale → label) — one stage in the `$field$` display-name cascade — and,
 * for a NUMBER, its [scale] (max fractional digits, the kernel's `maxFractionalDigits`), which decides the
 * decimal-places formal errors. A consumer-selected [MessageLabelProvider] precedes the model label and
 * indexed-debug fallbacks (KERNEL-FINDINGS §3/§13).
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
    /** A [Kind.DATE_RANGE] field's `interpretationOfYear` — see [RangeYearInterpretation]. */
    val rangeYearInterpretation: RangeYearInterpretation = RangeYearInterpretation.STANDARD,
    /** A [Kind.DATE] field's precision (the kernel's `DateType.datePrecision`); [DatePrecision.DAY_OPTIONAL]
     *  permits a day-omitted `00.MM.yyyy` value. Default [DatePrecision.FULL]. */
    val datePrecision: DatePrecision = DatePrecision.FULL,
    /** For an ENUMERATION field: its XML values in declaration order (the kernel's `getXMLValues`) — the
     *  positional key for `EnumCategory` and the membership domain. Empty for non-enum fields. */
    val enumValues: List<String> = emptyList(),
    /** For an ENUMERATION field: each category's positional value list (the kernel's `getCategoryValues`),
     *  keyed by category name — index `i` pairs with [enumValues]`[i]`. Drives `[Field -> Category]`. */
    val enumCategories: Map<String, List<String>> = emptyMap(),
    /** For an ENUMERATION field: the declared stored-token **S-value** (DM-JSON `sValue`, the kernel's
     *  `IEnumerationType.getsValue`) — the model-owned INDEX default an eligible clean-empty index cell
     *  receives during full validation and computation, and that partial validation silently suppresses
     *  (SPEC-2026-07-23-14/-15; [ModelDef.indexDefaults]). Null for none (and for non-enum fields). */
    val enumSValue: String? = null,
    /** For a CUSTOM-typed field: its validator name and optional bounds. The bounds are passed to
     *  [CustomFieldType.check] through [CustomFieldValidationContext]; they are not generic [Kind.STRING]
     *  constraints and therefore do not fire independently of the registered validator. [ModelDef] resolves the
     *  name through the consumer's [PredefinedTypeRegistry], so a declared
     *  custom type is the implicit per-cell form of `Valid(field, name)` (KERNEL-FINDINGS "Custom-extensibility
     *  surface"). Null for a non-custom field; the kind stays [Kind.STRING] (the value is string-shaped). */
    val customType: CustomFieldTypeDeclaration? = null,
    // ── Declared field-type CONSTRAINTS (IF20) — auto-validated against a filled value (KERNEL-FINDINGS §3). ──
    /** A STRING field's declared minimum length (`StringType.minLength`); a shorter value → `stringZuKurz`. */
    val minLength: Int? = null,
    /** A STRING field's declared maximum length (`StringType.maxLength`); a longer value → `stringZuLang`. */
    val maxLength: Int? = null,
    /** A STRING field's declared regex (`StringType.pattern`); a non-matching value → `stringFalschesMuster`. */
    val pattern: String? = null,
    /** A STRING field's `noValueValidation` — when true the kernel's `FormatDefinitionString.validiereFormat`
     *  returns `Valid` immediately, so ALL value checks (pattern / length / the default newline) are skipped. */
    val noValueValidation: Boolean = false,
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
    /** NUMBER explicit `maxIntegerDigits` — integer-part digit count above it → `zahlZuvieleVorkommastellen`. */
    val maxIntegerDigits: Int? = null,
    /** NUMBER `trait:"amount"` — an implicit integer-digit cap of `15 − scale` firing `zahlZuvieleVorkommastellen`
     *  (the SAME code as an explicit [maxIntegerDigits] — the kernel's `TypeConverter` maps both to one
     *  `maxDigitsBeforeDecimalPlace`), applied only when no explicit [maxIntegerDigits] is set. Distinct from the
     *  generic > 15-TOTAL-digit `stelligkeitZuLang` parse check, which applies to every number (IF67). */
    val amountTrait: Boolean = false,
    /** NUMBER `minLength` — value-string length below it → `zahlZuKurz` (counts sign + decimal point). */
    val numberMinLength: Int? = null,
    /** NUMBER `maxLength` — value-string length above it → `zahlZuLang`. */
    val numberMaxLength: Int? = null,
    /** The kernel's `global` field flag (`IField.isGlobal`). A global field is **auto-relevant in partial
     *  validation**: the md-runtime-service layer adds every global field (wildcarded at all reps) to the
     *  caller's relevant set before validating — so a rule whose error field is global RUNS even when the
     *  caller's relevant set omits it ([ModelDef.globalFields] → [io.github.mbackschat.a12.dm.interpreter.DmInterpreter.validatePart]). No effect on full validation. */
    val global: Boolean = false,
    /** A STRING pattern's field-owned invalid-value text (`StringType.errorMessage`), keyed by locale. This is
     *  NOT a rule template: the kernel gives it a smaller, locale-fixed token grammar (EXP-2026-07-30-01). */
    val patternMessages: Map<String, String> = emptyMap(),
    /** The field-owned empty-required text (`requirednessConfig.errorMessage`), keyed by locale. This is a
     *  separate producer from [patternMessages]: notably, its measured route preserves `$$` verbatim. */
    val requiredMessages: Map<String, String> = emptyMap(),
    /** A DateType's opt-in `youngerThan1900Check`: a concrete value before 1900-01-01 is `datumFalsch`.
     *  Distinct from the always-on Gregorian floor at 1583-10-16. */
    val rejectBefore1900: Boolean = false,
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
 * A computation alternative's tolerance band — the kernel's `toleranceRangeOp` (`Computation.TR{1,2,5,10}`,
 * serialized as `DiffersWithToleranceRange{N}`). When present, the implicit computation-validation compares the
 * stored value against the operation with `DiffersWithToleranceRange{N}` (fires iff
 * `|R19(stored) − R19(op)| > N`) instead of strict `!=`, so a stored value within ±N does not over-fire
 * (IF148/IF174).
 */
enum class ToleranceBand(val wire: String, val amount: Int) {
    TR1("DiffersWithToleranceRange1", 1),
    TR2("DiffersWithToleranceRange2", 2),
    TR5("DiffersWithToleranceRange5", 5),
    TR10("DiffersWithToleranceRange10", 10);

    companion object {
        /** Map a DM-JSON `toleranceRangeOp` value to its band. Null means the property is absent/explicitly
         *  null (strict `!=`); every present string outside the closed set is rejected loudly. */
        fun ofWire(wire: String?): ToleranceBand? {
            if (wire == null) return null
            return entries.firstOrNull { it.wire == wire }
                ?: throw IllegalArgumentException(
                    "unknown toleranceRangeOp '$wire' — expected one of ${entries.map { it.wire }}")
        }
    }
}

/**
 * One TIERED alternative of a [ComputationDef]: an optional [precondition] (DSL condition text) gating an
 * [operation] (DSL value-expression text), with an optional [tolerance] band. The kernel selects the first
 * alternative whose precondition holds (a null precondition always applies — the unconditional default),
 * KERNEL-FINDINGS §11 "tiered computations".
 */
data class ComputationAlternative(
    val precondition: String?,
    val operation: String,
    val tolerance: ToleranceBand? = null,
)

/**
 * The declared field-type constraints the kernel auto-validates against a filled value (IF20) — string length
 * + pattern, enum value-domain membership, number range, and the minimum-fractional-digits rule. Built per
 * field from the DM-JSON field type; [NONE] for an unconstrained field. Pattern compilation belongs to the
 * interpreter owner's distinct-pattern registry, not this value object.
 * (`zeroAllowed` is a runtime-only INumberType quality with no DM-JSON serialization. The integer-digit cap —
 * explicit `maxIntegerDigits` / implicit `trait:"amount"` — and the number `min/maxLength` DO serialize and are
 * modeled here; see INTERPRETER-FINDINGS IF30.)
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
    /** STRING `noValueValidation` — when true the kernel skips the WHOLE per-cell string format check (Valid
     *  immediately). A kernel-valid `noValueValidation` field forbids pattern/minLength and requires line breaks
     *  (`MVK_STRING_TYPE_*`), so the observable effect is that a declared `maxLength` is NOT enforced. */
    val noValueValidation: Boolean = false,
    /** NUMBER explicit `maxIntegerDigits` — integer-part digit count above it → `zahlZuvieleVorkommastellen`. */
    val maxIntegerDigits: Int? = null,
    /** NUMBER `trait:"amount"` integer-digit cap, precomputed as `15 − scale` (or null) — exceeded →
     *  `zahlZuvieleVorkommastellen` (the SAME code as [maxIntegerDigits]); applied only when [maxIntegerDigits]
     *  is unset. (The generic > 15-TOTAL-digit `stelligkeitZuLang` parse check is separate, IF67.) */
    val amountMaxIntegerDigits: Int? = null,
    /** NUMBER `minLength` — value-string length below it → `zahlZuKurz`. */
    val numberMinLength: Int? = null,
    /** NUMBER `maxLength` — value-string length above it → `zahlZuLang`. */
    val numberMaxLength: Int? = null,
    /** DATE `youngerThan1900Check` — values before 1900-01-01 are `datumFalsch`; default false. */
    val rejectBefore1900: Boolean = false,
) {
    /** Whether this field carries any NON-DEFAULT constraint, so it must be stored (else [NONE] — whose false
     *  booleans already encode the kernel defaults — is used). The `*Allowed`/`*NotAllowed` flags count only when
     *  `true` (the non-default); the default-forbidden newline/leading-zeros checks still run via [NONE]. */
    val any: Boolean = minLength != null || maxLength != null || pattern != null ||
        enumValues.isNotEmpty() || minValue != null || maxValue != null || (minFractionalDigits ?: 0) > 0 ||
        lineBreaksPermitted || leadingZerosAllowed || zeroNotAllowed || noValueValidation ||
        maxIntegerDigits != null || amountMaxIntegerDigits != null || numberMinLength != null ||
        numberMaxLength != null || rejectBefore1900

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
    /** The computation's NAME — the error code of its implicit validation rule (the kernel auto-generates a
     *  `FieldFilled(F) And [F] != <term>` validation per computation; IF32). Null = no implicit validation. */
    val name: String? = null,
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
    /** EVERY declared group id — field-less and non-repeatable groups included. [repeatable] and field-path
     *  prefixes under-approximate the declared tree (a structural container with no fields is invisible to
     *  both), so the Document transport validates group placements against this complete inventory. */
    val groupPaths: Set<String> = emptySet(),
    /** The model's configured base year (the kernel's `modelInfo.baseYear`), or null when none is declared —
     *  the value of the `BaseYear` point-in-time constant (a model that uses `BaseYear` without one is a
     *  kernel `MVK_NO_BASE_YEAR` error, so a valid model always carries it). */
    val baseYear: Int? = null,
    /** The kernel's `modelConfig.fieldRefByShortNameAllowed` (**default false**, matching the kernel). When
     *  true, a bare `[Name]` ref that does NOT resolve against its declaring group falls back to a model-wide
     *  UNIQUE field short name — the kernel's condition-parser resolution (`CheckIdNameRefImpl`), which the
     *  serialized condition retains (so an expanded model reaching the interpreter still carries the bare
     *  short name). Feeds [fieldsByShortName] / [entityPaths] into the parser's resolver (IF119). */
    val fieldRefByShortNameAllowed: Boolean = false,
    /** The model-declared legal charset (`content.modelConfig.supportedCharacters`), or null when the model
     *  declares none. The kernel bakes this slot into the generated code's legal codepoints and enforces it at
     *  runtime (`ZeichenNichtImZeichensatz`); [io.github.mbackschat.a12.dm.interpreter.load.ModelLoader] reads it
     *  via [LegalCharset.ofSupportedCharacters]. When present it takes precedence over the consumer-supplied
     *  charset SPI ([io.github.mbackschat.a12.dm.interpreter.DmInterpreter]); when null the SPI (default
     *  [LegalCharset.BMP]) applies (IF120). */
    val legalCharset: LegalCharset? = null,
    /** The model's configured time zone (`content.modelConfig.timeZone`, default `UTC`) — the axis the kernel
     *  applies to every DATE/DATE_TIME parse at eval time, making Europe/Berlin datetime arithmetic DST-aware
     *  ([ModelTimeZone]; KERNEL-FINDINGS §6). */
    val timeZone: ModelTimeZone = ModelTimeZone.UTC,
    /** The stored condition/computation keyword language, independent of display locales. */
    val conditionLanguage: ConditionLanguage = ConditionLanguage.EN,
) {
    val kinds: Map<String, Kind> = fields.associate { it.path to it.kind }
    val repeatable: Set<String> = repeatableGroups + declaredReps.keys

    /**
     * The effective address maximum of every BOUNDED GROUP level: a declared repeatable maximum when present,
     * otherwise 1 for a non-repeatable group. An unbounded repeatable group has no entry. Field paths are
     * deliberately absent — a field's terminal coordinate is not a runtime repetition axis, and the Kernel
     * document conversion refuses any terminal value other than 1 before validation (R2.8 placement lock).
     */
    internal val effectiveGroupRepetitionMaximums: Map<String, Int> = buildMap {
        for (group in groupPaths + declaredReps.keys) {
            when {
                group in declaredReps -> put(group, declaredReps.getValue(group))
                group !in repeatable -> put(group, 1)
            }
        }
    }

    /** Field SHORT NAME (last path segment) → the field paths carrying it — the kernel's `shortName2FullName`,
     *  **fields only** (`flattenToStream().filter(isField)`). A bare ref not found in its declaring group
     *  resolves here iff EXACTLY ONE match; >1 is a kernel `MVK_FIELDNAME_NOT_UNIQUE` reject (so no valid
     *  model reaches the interpreter with an ambiguous bare cross-group ref), 0 stays unresolved (IF119). */
    val fieldsByShortName: Map<String, List<String>> =
        fields.groupBy({ it.path.trimEnd('/').substringAfterLast('/') }, { it.path })

    /** Every declared ENTITY path (field OR group) — the set that gives the DECLARING GROUP precedence over the
     *  model-wide short-name fallback (matching the kernel's `getEntity`-first order): a bare `[Name]` whose
     *  `base/Name` candidate is already a known field/group is used as-is, never short-name-resolved. Covers
     *  fields, their ancestor groups, repeatable groups, and rule/computation anchor groups + targets (IF119). */
    val entityPaths: Set<String> = buildSet {
        fun addWithAncestors(path: String) {
            val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
            for (i in 1..parts.size) add("/" + parts.take(i).joinToString("/"))
        }
        fields.forEach { addWithAncestors(it.path) }
        repeatable.forEach { addWithAncestors(it) }
        groupPaths.forEach { addWithAncestors(it) }
        rules.forEach { addWithAncestors(it.group) }
        computations.forEach { addWithAncestors(it.group); addWithAncestors(it.targetPath) }
    }

    /** Paths of the model's `global` fields ([FieldDef.global]). In **partial** validation the kernel's
     *  md-runtime-service auto-adds every global field (all reps) to the relevant set, so a global field's
     *  instances are relevant regardless of the caller's set; full validation is unaffected. KERNEL-FINDINGS
     *  "Partial validation". */
    val globalFields: Set<String> = fields.filter { it.global }.map { it.path }.toSet()

    /** Unsigned NUMBER fields (the kernel's `hatVorzeichen == false`) — the [io.github.mbackschat.a12.dm.interpreter.eval.Document]'s
     *  directional-polarity input ([FieldDef.signed]). */
    val unsignedFields: Set<String> = fields.filter { !it.signed }.map { it.path }.toSet()

    /** NUMBER fields declaring `leadingZerosAllowed` — the one number case A12's Document-JSON converter keeps
     *  as TEXT instead of converting (`NumberUtil.createBigDecimalIfPossible` returns the original String), so
     *  the transport's read normalization must skip exactly these ([FieldDef.leadingZerosAllowed]). */
    val leadingZeroNumbers: Set<String> = fields.filter { it.leadingZerosAllowed }.map { it.path }.toSet()

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

    /** Each [Kind.DATE] field's [DatePrecision] — the partial-date suffix input for the formal check and
     *  `ValueAsDate`. */
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

    /** Each [Kind.DATE_RANGE] field's `interpretationOfYear`; [RangeYearInterpretation.STANDARD] when unset. */
    val rangeYearInterpretations: Map<String, RangeYearInterpretation> = fields
        .filter { it.kind == Kind.DATE_RANGE && it.rangeYearInterpretation != RangeYearInterpretation.STANDARD }
        .associate { it.path to it.rangeYearInterpretation }

    /** The DATE_RANGE field's `interpretationOfYear`, [RangeYearInterpretation.STANDARD] when it declares none. */
    fun rangeYearInterpretationOf(path: String): RangeYearInterpretation =
        rangeYearInterpretations[path] ?: RangeYearInterpretation.STANDARD

    /** Each NUMBER field's scale (`maxFractionalDigits`) — the formal-check input (decimal-places errors); the
     *  [io.github.mbackschat.a12.dm.interpreter.eval.Document] needs it to gate a formally-invalid value to Unknown. */
    val scales: Map<String, Int?> = fields.associate { it.path to it.scale }

    /** Each field's declared-constraint set (IF20) — the per-cell formal-check input for length/pattern/enum-
     *  domain/range/min-decimals. Only fields that declare a constraint appear; [constraintsOf] returns NONE
     *  otherwise (so the constraint pass is a no-op for an unconstrained field). */
    val fieldConstraints: Map<String, FieldConstraints> = fields
        .associate {
            it.path to FieldConstraints(
                minLength = it.minLength,
                maxLength = it.maxLength,
                pattern = it.pattern,
                enumValues = it.enumValues,
                minValue = it.minValue,
                maxValue = it.maxValue,
                minFractionalDigits = it.minFractionalDigits,
                lineBreaksPermitted = it.lineBreaksPermitted,
                leadingZerosAllowed = it.leadingZerosAllowed,
                zeroNotAllowed = it.zeroNotAllowed,
                noValueValidation = it.noValueValidation,
                maxIntegerDigits = it.maxIntegerDigits,
                // trait:"amount" → an implicit integer-digit cap of 15 − scale, but only when no explicit maxIntegerDigits.
                amountMaxIntegerDigits =
                    if (it.amountTrait && it.maxIntegerDigits == null) 15 - (it.scale ?: 0) else null,
                numberMinLength = it.numberMinLength,
                numberMaxLength = it.numberMaxLength,
                rejectBefore1900 = it.rejectBefore1900,
            )
        }
        .filterValues { it.any }

    /** The field's declared constraints (IF20), or [FieldConstraints.NONE] when it declares none. */
    fun constraintsOf(path: String): FieldConstraints = fieldConstraints[path] ?: FieldConstraints.NONE

    /** Each CUSTOM-typed field's declaration ([FieldDef.customType]) — the per-cell auto-validation
     *  input: a filled value is validated against the consumer's [PredefinedTypeRegistry] entry of that name,
     *  exactly like `Valid(field, name)` (the kernel's client-SPI custom field type). */
    val customTypes: Map<String, CustomFieldTypeDeclaration> = fields
        .filter { it.customType != null }
        .associate { it.path to it.customType!! }

    /** The field's declared custom type, or null when it is not a custom-typed field. */
    fun customTypeOf(path: String): CustomFieldTypeDeclaration? = customTypes[path]

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
    private val fieldsByPath: Map<String, FieldDef> = fields.associateBy { it.path }
    private val requiredByPath: Map<String, Requiredness> = fields.associate { it.path to it.required }

    /**
     * Index fields carrying a declared, DEFAULT-ELIGIBLE enum S-value — the model half of the kernel's
     * `IndexFieldDefaultValueUtils` eligibility (SPEC-2026-07-23-14): the field is an [indexFields] member,
     * declares [FieldDef.enumSValue], and is **not explicitly mandatory** (the auto index requiredness does
     * not count — the kernel's `isExplicitlyMandatory` is the author's check box). The per-DOCUMENT half
     * (exactly one instantiated row per parent, clean-empty cell) is
     * [io.github.mbackschat.a12.dm.interpreter.AutoChecks.eligibleIndexDefaults].
     */
    val indexDefaults: Map<String, String> = fields
        .filter { it.path in indexFields && it.enumSValue != null && it.required == Requiredness.NOT_REQUIRED }
        .associate { it.path to it.enumSValue!! }

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
     * The NUMBER field's declared `minFractionalDigits` (kernel default 0, and 0 for any non-number path) — the
     * pad floor of A12's runtime number rendering (`A12DocumentJson.runtimeNumberForm`, IF231) as well as the
     * `zahlOhneDezimalTrenner` / too-few-decimals constraint. A plain `Int` rather than the `Int?` [scaleOf]
     * returns, because the kernel's own property is a primitive `int` with no "unset" state.
     */
    internal fun minFractionalDigitsOf(path: String): Int = fieldsByPath[path]?.minFractionalDigits ?: 0

    /** The exact format default used by `$field.value$` in the admitted canonical (dot-separator) display
     *  profile when the caller value is absent or empty. No alternate presentation profile is inferred. */
    internal fun messageDefaultOf(path: String): String {
        if (kinds[path] != Kind.NUMBER) return ""
        val scale = fieldsByPath[path]?.minFractionalDigits ?: 0
        return if (scale == 0) "0" else "0.${"0".repeat(scale)}"
    }

    /** The field declaration for a message-label provider; null only for an invalid placeholder path. */
    internal fun fieldOf(path: String): FieldDef? = fieldsByPath[path]

    /** The model-authored locale label without applying a provider or field-name fallback. */
    internal fun declaredLabelOf(path: String, locale: String): String? = labelsByPath[path]?.get(locale)

    /**
     * The display string for the `$field$` placeholder: the field's [FieldDef.labels] entry for [locale]
     * when defined (and non-blank), else the field NAME — the last path segment. This mirrors the kernel's
     * `identifierToString` under an `ILabelProvider` that returns the model label or the field name (the
     * dmtool/`RuntimeLaws` provider does exactly this); with no label the kernel and the interpreter both
     * yield the name. (A blank label falls through to the name, matching the kernel's empty-text handling.)
     */
    fun labelOf(path: String, locale: String): String =
        labelsByPath[path]?.get(locale)?.takeIf { it.isNotBlank() } ?: path.trimEnd('/').substringAfterLast('/')

    /**
     * This model with an extra, non-stored [rule] appended — the interpreter's twin of the kernel's rule-inject
     * behind `model eval --condition/--field`. The appended candidate is parsed and evaluated through the SAME
     * per-row [DmInterpreter.validate] assembly as a stored rule, so a candidate over a repeated scope reports
     * one row-indexed message per firing — identical to persisting the rule and running whole-model eval
     * (G42), never a single index-less collapse. Every derived index recomputes off the copied [rules].
     */
    fun withRule(rule: RuleDef): ModelDef = copy(rules = rules + rule)
}

/**
 * One validation finding produced by [DmInterpreter.validateFull] — the interpreter's kernel-free twin of the
 * kernel's `IMessage`, carrying the same channels: the fired rule's [code], its [rulePath], the offending
 * entity [errorPointer], [severity], the VALUE/OMISSION [type] polarity, the rendered [text], the
 * [referencedPointers] operands, and (for an OMISSION) [fillToFixPointers] — the referenced fields that, if
 * filled, would clear the error (the kernel's `refOmissionErrorResponsible`).
 *
 * <b>Every address here is A12's message pointer, and it is STORED as one.</b> `IMessage` types all three of
 * those channels as `PartiallyKnownDocumentMultiPointer`, and so do these. The house-form strings below are
 * *derived* projections kept for the comparison surfaces that have always used them — the conformance corpus,
 * the `:adapter` differential and dmtool's output. The direction is not a preference: the house form omits the
 * terminal repetition, so `("/Plan/Demand", [1, -5])` renders `/Plan[1]/Demand` and the sentinel is gone. A
 * string can be computed from the pointer; the pointer cannot be recovered from the string.
 */
data class Message(
    val code: String,
    val rulePath: String,
    val errorPointer: A12PartiallyKnownMultiPointer,
    val severity: Severity,
    val type: MsgType,
    val text: String = "",
    val referencedPointers: List<A12PartiallyKnownMultiPointer> = emptyList(),
    val fillToFixPointers: List<A12PartiallyKnownMultiPointer> = emptyList(),
) {
    /** Derived comparison projection — never stored. See the class note on why the direction is one-way. */
    val errorPath: String get() = messagePointerHouseForm(errorPointer)

    /** Derived comparison projection — never stored. */
    val referenced: List<String> get() = referencedPointers.map(::messagePointerHouseForm)

    /** Derived comparison projection — never stored. */
    val fillToFix: List<String> get() = fillToFixPointers.map(::messagePointerHouseForm)
}

/**
 * The three-way verdict of a single-rule eval ([DmInterpreter.testRule]) — the `rule eval` contract.
 * FIRED = a violation; PASSED = evaluated and clean; SUPPRESSED = never evaluated because a referenced
 * field is formally invalid (the kernel skips a rule whose operand is *unknown*).
 */
enum class Verdict { FIRED, PASSED, SUPPRESSED }

/** A field whose formally-invalid value suppressed a rule — the `rule eval` `suppressedBy` entry. */
data class Suppression(val field: String, val formalErrorCode: String)

/**
 * What an [UnsupportedRule.rulePath] names — the two are different kinds of address and a consumer that
 * canonicalizes them alike gets one of them wrong.
 */
enum class UnsupportedSubject {
    /** A model ELEMENT path — a rule, a computation, or a field declaration: no repetition, no instance. */
    ELEMENT,

    /** A document CELL: an instance pointer with repetitions, e.g. an unregistered custom-typed value. */
    CELL,
}

/**
 * A construct the interpreter could not evaluate because it is unmodeled/external.
 *
 * [rulePath] is a model element path when [subject] is [UnsupportedSubject.ELEMENT] and a cell **instance pointer** when it
 * is [UnsupportedSubject.CELL]. It carried both from the start with no way to tell them apart, which left the
 * CLI unable to render the pointer half canonically without mangling the element half — so the kind is stated
 * rather than guessed. The field keeps its name for source compatibility; [subject] is what to switch on.
 */
data class UnsupportedRule(
    val rulePath: String,
    val reason: String,
    val subject: UnsupportedSubject = UnsupportedSubject.ELEMENT,
)

/**
 * One reference in a condition or computation operation that names an entity the model does not declare.
 *
 * A model carrying one is **invalid** — the kernel's consistency gate rejects it as `MVK_INVALID_ENTITY` — so
 * this is not a capability gap and never an [UnsupportedRule]: evaluating it would answer confidently about a
 * rule that cannot exist. [locus] is the rule/computation element path that carries the reference; [path] is
 * the absolute entity path the text resolved to, after the model's own binding rules (declaring-group
 * precedence, then the `fieldRefByShortNameAllowed` short-name fallback) have had their chance.
 */
data class UndeclaredReference(
    val locus: String,
    val path: String,
)

/** The result of [DmInterpreter.validate]: the firings plus the rules reported [unsupported]. */
data class ValidationOutcome(
    val messages: List<Message>,
    val unsupported: List<UnsupportedRule>,
)

/**
 * The result of [DmInterpreter.testRule] / [DmInterpreter.evalCandidate].
 *
 * <b>A [Verdict.FIRED] verdict retains the [firing] message itself, not a projection of it.</b> This used to
 * store only the rendered text and the polarity, which lost every other channel `validateFull` publishes for the
 * same firing — including the operands the rule read. The type in particular is a semantic observable, not
 * presentation: for a uniqueness predicate it is the only difference between a filtered and an unfiltered firing
 * on identical data, so a single-rule consumer that could not see it had to re-run whole-document validation to
 * measure it. [message] and [type] stay available as derived reads of the retained value.
 */
data class RuleResult(
    val rulePath: String,
    val verdict: Verdict,
    val firing: Message? = null,
    val suppressedBy: List<Suppression> = emptyList(),
) {
    /** The firing's rendered end-user text; empty when nothing fired. */
    val message: String get() = firing?.text ?: ""

    /** The firing's VALUE/OMISSION polarity; null when nothing fired. */
    val type: MsgType? get() = firing?.type
}

/**
 * One computed-field outcome of a [DmInterpreter.compute] run — the kernel-free twin of the kernel's
 * computed-field buckets: VALUE (a concrete value, in [Computed.value]) / CLEARED (computed nothing) /
 * ERRORED (the computed result is formally invalid for the target field — [Computed.value] retains the
 * ATTEMPTED rendered value, exactly as the kernel stores an errored value and flags it, IF34).
 */
enum class ComputeOutcome { VALUE, CLEARED, ERRORED }

/**
 * The [outcome] of computing one field at [path], with its [value], and the target cell's repetition
 * [indices] (one 1-based index per path part, the field included) — so a per-row computed field (one outcome
 * per repeated row) carries its exact row identity. [value] is present for **both** a [ComputeOutcome.VALUE]
 * (the computed value) **and** a [ComputeOutcome.ERRORED] (the ATTEMPTED rendered value — the kernel stores
 * it and flags the instance, so the interpreter retains it too; feedback F11a); a [ComputeOutcome.CLEARED]
 * carries none. Render the full pointer with
 * [io.github.mbackschat.a12.dm.interpreter.eval.formatPointer]`(path, indices)` — this is the per-row identity
 * the conformance corpus records (CONFORMANCE-CORPUS-SPEC §7), superseding the historical index-strip. [indices]
 * defaults empty for a hand-built [Computed] (a non-repeatable target renders bare).
 */
data class Computed(
    val path: String,
    val outcome: ComputeOutcome,
    val value: String? = null,
    val indices: List<Int> = emptyList(),
)
