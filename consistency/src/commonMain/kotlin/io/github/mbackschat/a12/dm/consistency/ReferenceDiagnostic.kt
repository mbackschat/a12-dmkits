package io.github.mbackschat.a12.dm.consistency

/**
 * The A12 rejection a reference outcome corresponds to, or `null` when it resolved.
 *
 * **The code depends on WHERE the reference was written, not only on how it failed**, and that is measured
 * rather than tidy: the same unresolvable path is `MVK_ERROR_FIELD_MISSING` as a rule's error target,
 * `MVK_INVALID_ENTITY` inside a condition, and — as a computation's computed field — not an `MVK_*` code at
 * all, because the Kernel cannot even build the model. An earlier version reported every unresolved
 * reference as `MVK_INVALID_ENTITY`, which was right for one of the three.
 *
 * The two roles ALSO differ on climbing too far, in the opposite direction from the obvious guess. A
 * condition reference that leaves the root group draws `MVK_TOO_MANY_PARENT_GROUPS`, a rejection A12 issues
 * cleanly; a declared target that does the same makes the model UNLOADABLE — `ElementPathUtils.absPath`
 * raises before any check runs — so it joins the computed field's class rather than the error field's
 * (`ErrorFieldRelativePathLawsTest` records that row as uncoded).
 *
 * All of them carry [FindingCategory.A12_CONSTRAINT] and may reject a model: each is a measured Kernel
 * verdict on a whole model through `ModelLoader.loadAndCheck`, not checker policy.
 *
 * The [Finding.explain] states what was observed and stops there. The *corrective* for each code is owned by
 * `rulekit.validate.StructuralKernelFix`, which this Kernel-free module cannot read; giving the portable
 * report the same guidance without minting a second live copy of it is C2's remaining piece, and F's report
 * is where it lands. Restating the prose here is the one thing that must not happen meanwhile.
 *
 * @param at the declaration the reference was written in.
 * @param member the declaration MEMBER it was written in, when the declaration has several that carry DSL.
 *     A computation has three kinds, so a finding that named only the computation would leave an author
 *     reading all of them — and three references to one missing name would report three identical findings.
 */
fun Reference.asFinding(
    role: ReferenceRole,
    at: String,
    member: String? = null,
    span: TextSpan? = null,
): Finding? {
    // A condition that walks the segments before a misplaced up-step answers with THEIR outcome when they fail,
    // so the finding is theirs, reported against the text the author wrote.
    if (this is Reference.MisplacedUpStep && role.walksAPrefix) {
        walkedPrefix?.takeUnless { it is Reference.Resolved }?.let { return it.asFinding(role, at, member, span) }
    }
    val explain = when (this) {
        is Reference.Resolved -> return null
        is Reference.Undeclared ->
            "no declared element matches `$candidate`, and no model-wide field short name resolved it"
        is Reference.Ambiguous ->
            "the short name `$name` is carried by ${candidates.size} fields — " +
                "${candidates.joinToString(", ")} — so the model-wide fallback cannot choose between them"
        is Reference.AboveRoot ->
            "`$written` climbs above the root group, so it names no position in this model"
        is Reference.MisplacedUpStep ->
            "`$written` carries an up-step `..` after its first named segment, where a path has no place for one"
    }
    return a12Error(role.codeFor(this), at, member?.let { "in `$it`: $explain" } ?: explain, span)
}

/**
 * A rejection A12 issues with no code of its own, because it never reaches a verdict about the model.
 *
 * Five measured inputs reach it (2026-09-13), on two different tiers, and the code deliberately covers both
 * because a consumer acts on them identically — the model is unusable, and nothing else in the report is
 * A12's word about it. The model **is not built**: a computation whose computed field is missing (the Kernel
 * reports the bare text "The computed field \"…\" was not found." from
 * `ValidationModelConverter.getComputationStatement`, with no error type), a computation whose computed field
 * names a GROUP — A12 has a key for that one and never reaches it, the builder refusing first
 * ([KERNEL-FINDINGS KF329]) — or a rule whose error target climbs past the root
 * (`ElementPathUtils.absPath` raises an `IndexOutOfBoundsException` during deserialization). Or the source **is not read at all**: an `errorMessage` entry without a string `locale`
 * and `text`, or a locale code A12's parser refuses on either the header or the message side — each raises
 * `ModelInputException` before any rule is examined (measured 2026-09-12, `MessageSlotRawShapeLawsTest`).
 *
 * None carries an `MVK_*` key, so this is the one code the checker owns — minted only after establishing
 * that A12 defines none, and deliberately spelled outside the `MVK_*` and `RK_*` namespaces so a consumer
 * can never mistake it for one.
 */
const val CK_MODEL_NOT_BUILDABLE = "CK_MODEL_NOT_BUILDABLE"

/**
 * A12's class for a rule whose error target does not resolve, and the metamodel law restating it.
 *
 * Named once because three live sites spell them — this mapping, the blank-member emitter, and the gate that
 * decides what survives at a stopped declaration. A literal changed in one and missed in the gate would
 * delete the very finding the gate exists to preserve, with no compiler signal.
 */
internal const val ERROR_FIELD_MISSING = "MVK_ERROR_FIELD_MISSING"

internal const val MISSING_ERROR_FIELD = "MVK_MISSING_ERROR_FIELD"

/** Whether this role's A12 walk judges the segments before a misplaced up-step ([Reference.MisplacedUpStep]). */
private val ReferenceRole.walksAPrefix get() = this == ReferenceRole.CONDITION_ENTITY

private fun ReferenceRole.codeFor(outcome: Reference): String = when (this) {
    // An unresolvable error target reports `MVK_ERROR_FIELD_MISSING` together with `MVK_MISSING_ERROR_FIELD`;
    // the checker reports the first, the second being the metamodel law restating it rather than a
    // second defect. An above-root one never reaches a check at all.
    ReferenceRole.ERROR_TARGET ->
        if (outcome is Reference.AboveRoot) CK_MODEL_NOT_BUILDABLE else ERROR_FIELD_MISSING
    ReferenceRole.COMPUTED_TARGET -> CK_MODEL_NOT_BUILDABLE
    ReferenceRole.CONDITION_ENTITY -> when (outcome) {
        is Reference.Ambiguous -> "MVK_FIELDNAME_NOT_UNIQUE"
        is Reference.AboveRoot -> "MVK_TOO_MANY_PARENT_GROUPS"
        is Reference.MisplacedUpStep -> UNEXPECTED_TOKEN
        // A candidate whose ROOT SEGMENT is the one that failed is its own rejection, whatever follows it:
        // A12 resolves a path segment by segment, so the root-group class turns on WHICH segment missed and
        // not on how long the path is. Reading the separator count instead named `MVK_INVALID_ENTITY` for
        // `/NoSuchRoot/Amount`, where A12 names the root class
        // (`rulekit.validate.laws.ReferenceSegmentGateOrderLawsTest`).
        is Reference.Undeclared ->
            if (outcome.rootUnresolved) "MVK_INVALID_ROOT_GROUP" else "MVK_INVALID_ENTITY"
        else -> "MVK_INVALID_ENTITY"
    }
    // The MESSAGE surface answers with A12's unprefixed error-text vocabulary rather than the condition's,
    // outcome for outcome (measured, `MessageParameterShortNameLawsTest`). Two of the four spellings are the
    // condition code's tail and two are not, so neither stripping `MVK_` nor reusing the arm above would
    // produce this table — it has to be its own.
    ReferenceRole.MESSAGE_PARAMETER -> when (outcome) {
        is Reference.Ambiguous -> "ERROR_TEXT_FIELDNAME_NOT_UNIQUE"
        is Reference.AboveRoot -> "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS"
        // The message surface spells the condition's grammar code here, not an `ERROR_TEXT_` one
        // (`rulekit.validate.laws.PathSegmentWalkLawsTest`).
        is Reference.MisplacedUpStep -> UNEXPECTED_TOKEN
        // Same law, measured separately on this surface rather than transferred: a group parameter naming an
        // undeclared root draws the root class whether or not a child follows it
        // (`rulekit.validate.laws.RuleMessageGroupParameterLawsTest`).
        is Reference.Undeclared ->
            if (outcome.rootUnresolved) "INVALID_ROOT_GROUP" else "INVALID_ENTITY"
        else -> "INVALID_ENTITY"
    }
}

private fun a12Error(code: String, at: String, explain: String, span: TextSpan? = null) =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, at, span)
