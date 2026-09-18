package io.github.mbackschat.a12.dm.consistency

/**
 * The three kinds of check the checker may report, kept apart because only one of them speaks for A12.
 *
 * The separation is the point: presenting a dmkits policy or an evaluator limitation as an A12 rejection
 * would make the portable checker disagree with the Kernel it claims to reproduce, in the direction no
 * consumer can detect — a refusal the Kernel never issues is invisible precisely because the Kernel is
 * never asked (CLAUDE.md, "A client-side refusal needs a measured kernel basis").
 */
enum class FindingCategory {
    /** A measured A12 acceptance constraint. The only category that may reject a model. */
    A12_CONSTRAINT,

    /** A deliberate dmkits authoring policy — advisory, and never asserted as a Kernel rejection. */
    AUTHORING_POLICY,

    /**
     * A runtime support limitation. It never affects validity; it limits *completeness*, and only when it
     * actually prevents an authoring check from running.
     *
     * **Reserved rather than live**, and deliberately kept so: the three-way split is what makes an
     * evaluator limitation statable as something other than an A12 rejection, which is this enum's whole
     * job, and a category that only appears once a producer needs it would have to be added to a published
     * shape at exactly the wrong moment. Which producers emit one, and what a consumer does about it
     * meanwhile, is `docs/CONSISTENCY-API.md`'s to say — it is the surface a consumer reads, and a second
     * statement here would be the copy that goes stale.
     */
    EVALUATOR_SUPPORT,
}

/**
 * A finding's severity — the Kernel's own three levels, mirrored rather than imported so this module keeps
 * no project dependency (the same treatment [ModelSource] gets). `dm.core.Severity` is the Java-side peer.
 *
 * **Declaration order is load-bearing**, because A12's own enum says so: its `Severity` carries the comment
 * that "the order of the severities in this enum is important since ordinal() is used to compare two of
 * them". Comparing two of ours must mean the same thing, so the order is locked rather than incidental.
 */
enum class FindingSeverity {
    INFO,
    WARNING,
    ERROR,
}

/**
 * One thing the checker found.
 *
 * @param code the classification in its owner's vocabulary — an `MVK_*` key, a `FehlertextErrorType` name,
 *     an `RK_*` code — carried as declared rather than re-derived from a localized message.
 * @param at the declared path this concerns, when the finding has one.
 */
data class Finding(
    val category: FindingCategory,
    val severity: FindingSeverity,
    val code: String,
    val explain: String,
    val at: String? = null,
    val span: TextSpan? = null,
) {
    /**
     * Whether this finding alone makes the model invalid: a measured A12 constraint, violated at ERROR.
     *
     * Both halves are load-bearing, and the ERROR half is **our** threshold rather than the Kernel's:
     * `checkConsistency` returns `void` and emits a notification stream, so it expresses no rejection at all.
     * Every dmkits consumer draws the line at ERROR — `dm.core.ModelLoader` filters
     * `severity() == Severity.ERROR`, and `rulekit.ValidationResult.ok()` is `noneMatch(ERROR)` — and this
     * agrees with them deliberately. Whether A12 itself treats a WARNING-only model as acceptable is not
     * measured here; that would be an `:adapter` probe.
     */
    val justifiesRejection: Boolean
        get() = category == FindingCategory.A12_CONSTRAINT && severity == FindingSeverity.ERROR

    /**
     * The same defect with its location dropped — what "is this the same finding?" must compare.
     *
     * A finding's identity is the DEFECT, not where the text currently sits. Typing a character earlier in a
     * condition moves every span after it, and an attribution comparing whole findings would then report one
     * defect as resolved and an identical one as introduced on a keystroke that changed nothing about it.
     * [attribute] compares this rather than the finding, which is why [span] can be added to a published
     * report without making the edit surface unstable.
     */
    internal val sameDefect: Finding get() = copy(span = null)
}

/**
 * Which characters of which DSL member a finding is about — what an editor underlines.
 *
 * [member] is spelled as a report spells it (`errorCondition`, `computationAlternatives[0].operation`), so a
 * consumer holding several texts of one declaration knows which buffer the offsets index. The range is
 * half-open over the member's own text: `start` is the first character and `end` the one after the last, the
 * convention every editor API in reach already uses.
 *
 * **It is absent far more often than present, and absence is deliberate rather than pending.** A span is
 * published only where the family that judged the text already knew the offset — an unresolved reference,
 * unreadable characters, half-typed input, a shape the grammar refuses. A finding about a declaration's
 * STRUCTURE has no characters to point at, and a family that would have to re-derive an offset publishes
 * none rather than an approximate one: an editor underlining the wrong words is worse than one underlining
 * nothing.
 */
data class TextSpan(val member: String, val start: Int, val end: Int)

/**
 * A12's own guidance for a finding's code — what the code MEANS and how to repair it.
 *
 * Both texts are A12-facing rather than this checker's: [Finding.explain] says what this checker observed in
 * this declaration, while a corrective says what the code means wherever it is raised. They are separate
 * because only the first is about the model in front of you.
 *
 * @param fix the repair, phrased as an instruction to the author.
 * @param meaning what the code classifies, including the traps that make the fix non-obvious.
 */
data class Corrective(val fix: String, val meaning: String)

/**
 * The guidance A12's own diagnostic owners carry for this finding's code, absent when they carry none.
 *
 * **It is a projection, not a second copy.** `rulekit.validate.StructuralKernelFix` and
 * `rulekit.validate.RkCode` are the single live statement of this prose on the Java side, and the table read
 * here is generated from them over exactly the codes this checker emits
 * (`CheckerCorrectiveProjectionGuardTest`). A Kernel-free module cannot read Java source, and restating the
 * prose here would mint the second live statement the doc rules forbid.
 *
 * Absence is ordinary rather than a gap: most codes are explained by the operator catalog's own entries,
 * which are keyed by operator and not by code, so there is nothing for a code-keyed lookup to return.
 */
val Finding.corrective: Corrective?
    get() = KERNEL_CORRECTIVES[code]

/**
 * A check's summary verdict.
 *
 * **There is deliberately no `Valid`.** No required check family is implemented, so nothing can establish
 * that the applicable checks for the pinned profile actually ran; a result type able to say so would be an
 * unbacked claim rather than an unfinished one. `Valid` arrives with the internal coverage evidence that
 * earns it, and [complete] becomes derived at the same time.
 */
sealed interface ConsistencyStatus {
    /**
     * Whether every required check for this input and profile completed. Constant `false`, and it publishes
     * anyway so that a consumer switching on it keeps working when it starts carrying information.
     *
     * **What holds it false is not a shortage of families but the absence of a coverage REGISTRY.** Families
     * run in numbers (see [CheckFamily]); what nothing can yet state is that for a given model, every code
     * A12 could report is either reported by a family that ran or cannot arise from stored DM-JSON. Until
     * that holds, one reason for incompleteness cannot be told from another, and `complete` would be
     * asserting the very thing it has no basis for.
     *
     * *Corrected 2026-09-18: this read "because no required check family is implemented", which was true
     * when written and had become the kind of sentence a reader checks against the source and disbelieves.
     * The blocker is enumerated in `PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md` "the residue is now
     * DRAINED" — eleven codes, none of them closable by implementing another family.*
     */
    val complete: Boolean

    /** At least one measured A12 acceptance constraint is violated; other portions may be unexamined. */
    data object Invalid : ConsistencyStatus {
        override val complete: Boolean get() = false
    }

    /** No definite violation was established, and coverage is incomplete. */
    data object Inconclusive : ConsistencyStatus {
        override val complete: Boolean get() = false
    }
}

/** Summarize [findings]. The summary never replaces them: a consumer reads the findings, not the verdict. */
fun statusOf(findings: List<Finding>): ConsistencyStatus =
    if (findings.any { it.justifiesRejection }) ConsistencyStatus.Invalid else ConsistencyStatus.Inconclusive

/**
 * A declaration whose kind envelope is present and is not a JSON object.
 *
 * A12 binds that envelope to the kind's own type, so a non-object one is a binding failure: the source never
 * loads and no member of that declaration may be judged. Every family that reads members reports this
 * instead of reading the members as absent — which is the same defect one tier down, and the direction that
 * publishes a verdict about a model A12 never examined.
 */
internal fun unreadableEnvelope(element: DeclaredElement) =
    unreadableEnvelope(element.kind, element.path)

/** The same finding for a family that has migrated to the read barrier. */
internal fun unreadableEnvelope(element: TrackedDeclaration) =
    unreadableEnvelope(element.kind, element.path)

private fun unreadableEnvelope(kind: String, at: String) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    CK_MODEL_NOT_BUILDABLE,
    "`$kind` here is present and is not a JSON object, which A12's own reader refuses — the " +
        "source never loads, and an OMITTED envelope is what A12 default-constructs instead",
    at,
)
