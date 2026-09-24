package io.github.mbackschat.a12.dm.consistency

/**
 * The `.*` path suffix, which A12's grammar admits so it can refuse it with a readable message.
 *
 * The suffix on `Claims`, then a step to `Amount`, is H-model spelling; in an A12 model A12 answers `MVK_POINT_IN_A12_MODEL`
 * ([KERNEL-FINDINGS KF356], `rulekit.validate.laws.ReferenceSegmentGateOrderLawsTest`). The typed renderer
 * never emits it, which is why the ledger carried the code as erased — but a STORED condition is authored
 * text, and `rule check` and `modify --condition` both accept raw strings, so a model can carry it.
 *
 * **It is reported only where the SUFFIXED segment resolves**, because A12 runs resolution first within each
 * segment: the same suffix on a name nothing declares draws `MVK_INVALID_ENTITY` and this family must leave it
 * to the reference family. A miss AFTER the suffixed segment is another matter, since the walk answers at the
 * suffix and never reaches it, so `Claims.*` then `Nope` is this family's too (measured 2026-09-24,
 * `rulekit.validate.laws.GroupOperandAndKeyResolverClassLawsTest`), and so is `Claims.*` then a misplaced `..`
 * (`rulekit.validate.laws.PathSegmentWalkLawsTest`). The reader strips the suffix before binding,
 * so a resolved outcome says nothing about whether it was written — which is why this reads the AUTHORED text
 * rather than the bound path.
 *
 * **It reports ahead of the wildcard's landing site, measured**: the suffix on a non-repeatable group draws
 * this class from A12 and not `MVK_INVALID_WILDCARD`, so a consumer told only the landing-site class would be
 * sent to the wrong repair. The checker names both there, because the landing-site defect survives fixing the
 * suffix — `Insured*` still stars a group that does not repeat.
 */
internal fun TrackedFactBase.hModelPathSuffixFindings(): List<Finding> = conditionReferences()
    .filter { it.written.carriesTheSuffix() && (it.target is Reference.Resolved || answersAtTheSuffix(it)) }
    .map { reference ->
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_POINT_IN_A12_MODEL",
            "in `${reference.member}`: `${reference.written}` carries the `$SUFFIX` repetition suffix, " +
                "which is H-model spelling — an A12 model stars the segment instead, `*`",
            reference.declaredAt,
        )
    }

private const val SUFFIX = ".*"

/**
 * Whether a [reference] A12 refuses further along is answered at its suffix first: the suffix is a
 * per-segment gate, so `Claims.*` answers ahead of a `Nope` or a misplaced `..` behind it ([earlierSegmentGate]).
 */
private fun TrackedFactBase.answersAtTheSuffix(reference: ConditionReference): Boolean {
    if (!reference.target.isMetAfterTheSegmentsAhead) {
        return false
    }
    val group = reference.declaredAt.substringBeforeLast('/', missingDelimiterValue = "")
    val origin = declarationAt(reference.declaredAt)?.origin ?: DeclarationOrigin("")
    return earlierSegmentGate(
        reference.written,
        resolves = { prefix -> resolveEntity(group, prefix, origin) },
        isRepeatableGroup = { path -> declarationAt(path)?.isRepeatableGroup == true },
    ) == EarlierSegmentGate.H_MODEL_SUFFIX
}

/**
 * Whether the text carries the suffix on a NAME segment.
 *
 * A starred UP-STEP contains the same two characters and is a different construct entirely — A12 answers it
 * with `MVK_INVALID_WILDCARD_REL` — so the test is that the dot does not itself follow a dot. A plain
 * substring search reported that shape on two of `RepetitionWildcard`'s own rows.
 */
private fun String.carriesTheSuffix(): Boolean {
    var at = indexOf(SUFFIX)
    while (at >= 0) {
        if (getOrNull(at - 1) != '.') {
            return true
        }
        at = indexOf(SUFFIX, at + 1)
    }
    return false
}
