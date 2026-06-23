package io.github.mbackschat.a12.dm.interpreter

/**
 * The structured model the interpreter evaluates — the interpreter owns its types and does NOT parse
 * JSON (proposal §6/§7: a clean data contract, not a shared object model). The *consumer* builds a
 * [ModelDef] from its own source (dmtool from DM-JSON via Jackson; a multiplatform JSON loader is a
 * later, additive increment). Kernel-free, so the purity lint holds.
 */

/** Rule/finding severity — mirrors the kernel's ERROR / WARNING. */
enum class Severity { ERROR, WARNING }

/**
 * A field, its [Kind] (the metadata bridge that drives the empty-value asymmetry + formal errors), and its
 * localized display [labels] (locale → label) — the source for the `$field$` display-name placeholder. The
 * kernel resolves `$field$` to the field's label for the locale, falling back to the field NAME when no
 * label is defined (a runtime `ILabelProvider` concern — see [ModelDef.labelOf] / KERNEL-FINDINGS §3). Our
 * corpus (and every shipped A12 model surveyed) carries no field labels, so `$field$` resolves to the name.
 */
data class FieldDef(val path: String, val kind: Kind, val labels: Map<String, String> = emptyMap())

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
) {
    val kinds: Map<String, Kind> = fields.associate { it.path to it.kind }
    val repeatable: Set<String> = repeatableGroups + declaredReps.keys
    private val labelsByPath: Map<String, Map<String, String>> = fields.associate { it.path to it.labels }

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
