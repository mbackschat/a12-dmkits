package io.github.mbackschat.a12.dm.interpreter

/**
 * The structured model the interpreter evaluates — the interpreter owns its types and does NOT parse
 * JSON (proposal §6/§7: a clean data contract, not a shared object model). The *consumer* builds a
 * [ModelDef] from its own source (dmtool from DM-JSON via Jackson; a multiplatform JSON loader is a
 * later, additive increment). Kernel-free, so the purity lint holds.
 */

/** Rule/finding severity — mirrors the kernel's ERROR / WARNING. */
enum class Severity { ERROR, WARNING }

/** A field and its [Kind] — the metadata bridge that drives the empty-value asymmetry + (later) formal errors. */
data class FieldDef(val path: String, val kind: Kind)

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
}

/**
 * One validation finding produced by [DmInterpreter.validateFull] — the interpreter's kernel-free twin
 * of the kernel's `IMessage`: the fired rule's [code], its [rulePath], the resolved [errorPath] (the
 * indexed offending field), [severity], the VALUE/OMISSION [type] polarity, and the rendered [text]
 * (empty when the rule carries no template for the locale).
 */
data class Message(
    val code: String,
    val rulePath: String,
    val errorPath: String,
    val severity: Severity,
    val type: MsgType,
    val text: String = "",
)

/**
 * The three-way verdict of a single-rule eval ([DmInterpreter.testRule]) — the `rule test` contract.
 * FIRED = a violation; PASSED = evaluated and clean; SUPPRESSED = never evaluated because a referenced
 * field is formally invalid (the kernel skips a rule whose operand is *unknown*).
 */
enum class Verdict { FIRED, PASSED, SUPPRESSED }

/** A field whose formally-invalid value suppressed a rule — the `rule test` `suppressedBy` entry. */
data class Suppression(val field: String, val formalErrorCode: String)

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
