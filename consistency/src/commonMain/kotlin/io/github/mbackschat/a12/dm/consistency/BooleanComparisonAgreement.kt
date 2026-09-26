package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionComparison
import io.github.mbackschat.a12.dm.consistency.syntax.FALSE_CONSTANT
import io.github.mbackschat.a12.dm.consistency.syntax.TRUE_CONSTANT
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize

/**
 * What a CONFIRM or BOOLEAN declaration may be compared to, and with which equality operator.
 *
 * `CheckVergleichsBedingungImpl.checkYesAndYesNoOperations` runs after the operator gates
 * ([comparisonOperatorOperandFindings]) and asks a different question: not whether the operator suits the
 * sides, but whether a side that is one of A12's two boolean KINDS has a partner it can mean anything
 * against. The two kinds are not interchangeable and each answers with a class of its own
 * ([KERNEL-FINDINGS KF383], `rulekit.validate.laws.BooleanComparisonAgreementLawsTest`).
 *
 * | the declaration | what it may stand against | otherwise |
 * |---|---|---|
 * | `ConfirmType` | `True`, or another confirm under `!=` | `MVK_INVALID_COMPARE_TO_YES` |
 * | `BooleanType` | `True`, `False`, or another boolean | `MVK_INVALID_COMPARE_TO_YESNO` |
 *
 * **The asymmetry is the law.** A confirm carries a yes-representation and no no-representation, so `False`
 * and a boolean partner are both refused; a boolean carries both and admits either constant. The confirm arm
 * is narrower again under EQUALITY, where two confirms decide nothing because each can only ever be true —
 * the one place the OPERATOR moves the answer, which is why every row is measured in both spellings
 * ([KF270] had read the arm as refusing every field on the right, its grid holding `==` fixed).
 *
 * **The confirm arm is asked first**, so a confirm compared with a boolean draws `MVK_INVALID_COMPARE_TO_YES`
 * rather than the boolean class — measured, because the reverse order reads just as naturally from the two
 * predicates alone.
 *
 * **Only `==` and `!=` are judged.** A12 refuses a confirm under any other comparator too, but the operator
 * gates answer first on every spelling that reaches a measurement — ordering with `MVK_INVALID_TYPE_FOR_COMPARISON`,
 * tolerance with `MVK_INVALID_TYPE_FOR_TOLERANCE_OP` — leaving the pattern arm as the one comparator whose
 * order against this walk is unmeasured. Judging it would name a class on a shape no row covers.
 *
 * **Silent on the two value-equality branches of the same method.** `MVK_INVALID_COMPARE_TO_YES` and
 * `MVK_INVALID_COMPARE_TO_YESNO` are also thrown where two declarations spell their true (or false)
 * representation differently, and the model conversion that feeds the checker substitutes the literals
 * `true`/`false` for whatever either declared, so no such mismatch survives to be compared — the mechanism
 * [valueInFieldsConstantKindFindings] records for `MVK_DIFFERENT_YES_VALUE`, measured there.
 *
 * **Only a bare reference or a written-out constant is judged**, for the reason [bareReferenceField] carries:
 * a side that is arithmetic, a call or an aggregate has a result type A12 computes and this reader does not.
 */
internal fun TrackedFactBase.booleanComparisonAgreementFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val spellings = BooleanConstants(
        yes = keywords[TRUE_CONSTANT],
        no = keywords[FALSE_CONSTANT],
        temporal = TEMPORAL_CONSTANTS.mapNotNull { keywords[it] }.toSet(),
    )
    return declarations().mapNotNull { element ->
        val body = element.body ?: return@mapNotNull null
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
            comparisonsOf(element, body, member, text)
                .firstNotNullOfOrNull { comparison ->
                    agreementFinding(comparison, spellings, group, element, member)
                }
        }
    }
}

/** The two constants carrying A12's DATETIME base type; neither boolean kind admits one. */
private val TEMPORAL_CONSTANTS = listOf("Today", "Now")

private const val EQUAL = "=="

private const val NOT_EQUAL = "!="

private const val YES = "MVK_INVALID_COMPARE_TO_YES"

private const val YES_NO = "MVK_INVALID_COMPARE_TO_YESNO"

/** How the model's own language spells the constants this walk has to recognize by name. */
private data class BooleanConstants(val yes: String?, val no: String?, val temporal: Set<String>)

/** What one side of the comparison is, as far as the two agreement gates need to know. */
private enum class BooleanSide(val spoken: String) {
    TRUE_CONSTANT("`True`"),
    FALSE_CONSTANT("`False`"),
    OTHER_CONSTANT("a written-out value of another kind"),
    CONFIRM_FIELD("another `confirm` declaration"),
    BOOLEAN_FIELD("another `boolean` declaration"),
    OTHER_FIELD("a declaration of another kind"),
}

private fun TrackedFactBase.agreementFinding(
    comparison: ConditionComparison,
    spellings: BooleanConstants,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    if (comparison.operator != EQUAL && comparison.operator != NOT_EQUAL) {
        return null
    }
    val left = sideOf(comparison.left, spellings, declaringGroup, element, comparison.inAFilterBody) ?: return null
    val right = sideOf(comparison.right, spellings, declaringGroup, element, comparison.inAFilterBody) ?: return null
    val equality = comparison.operator == EQUAL
    val code = when {
        left == BooleanSide.CONFIRM_FIELD || right == BooleanSide.CONFIRM_FIELD ->
            confirmDefect(left, right, equality)
        left == BooleanSide.BOOLEAN_FIELD || right == BooleanSide.BOOLEAN_FIELD ->
            booleanDefect(left, right)
        else -> null
    } ?: return null
    val declared = if (code == YES) BooleanSide.CONFIRM_FIELD else BooleanSide.BOOLEAN_FIELD
    val partner = if (left == declared) comparison.right else comparison.left
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        code,
        when {
            code == YES && left == right ->
                "in `$member`: `${comparison.left}` and `${comparison.right}` are both `confirm` " +
                    "declarations, which can only ever be true, so `$EQUAL` between them decides nothing — " +
                    "write `$NOT_EQUAL` to ask whether exactly one of them is confirmed"
            code == YES ->
                "in `$member`: a `confirm` declaration records a yes and has no spelling for a no, so it " +
                    "stands only against `True` or another `confirm` — `$partner` is " +
                    "${(if (left == declared) right else left).spoken}; write `$NOT_EQUAL True` for the " +
                    "unconfirmed side"
            else ->
                "in `$member`: a `boolean` declaration stands only against `True`, `False` or another " +
                    "`boolean` — `$partner` is ${(if (left == declared) right else left).spoken}"
        },
        element.path,
        comparison.spanIn(member),
    )
}

/**
 * The confirm arm's class, or null where the pairing is admitted.
 *
 * Two confirms are the one pairing the OPERATOR decides: each can only ever be true, so `==` between them
 * carries no information and is refused where `!=` is not.
 */
private fun confirmDefect(left: BooleanSide, right: BooleanSide, equality: Boolean): String? {
    val both = left == BooleanSide.CONFIRM_FIELD && right == BooleanSide.CONFIRM_FIELD
    if (both) {
        return if (equality) YES else null
    }
    val partner = if (left == BooleanSide.CONFIRM_FIELD) right else left
    return if (partner == BooleanSide.TRUE_CONSTANT) null else YES
}

/** The boolean arm's class, or null where the pairing is admitted. */
private fun booleanDefect(left: BooleanSide, right: BooleanSide): String? {
    val partner = if (left == BooleanSide.BOOLEAN_FIELD) right else left
    return if (partner in BOOLEAN_PARTNERS) null else YES_NO
}

private val BOOLEAN_PARTNERS = setOf(
    BooleanSide.BOOLEAN_FIELD,
    BooleanSide.TRUE_CONSTANT,
    BooleanSide.FALSE_CONSTANT,
)

/** What this side is, or null wherever this reader will not say — see [bareReferenceField]. */
private fun TrackedFactBase.sideOf(
    side: String,
    spellings: BooleanConstants,
    declaringGroup: String,
    element: TrackedDeclaration,
    inAFilterBody: Boolean,
): BooleanSide? {
    val tokens = tokenize(side)
    val single = tokens.singleOrNull()
    when {
        single == null -> if (isNumberLiteral(tokens)) return BooleanSide.OTHER_CONSTANT
        single.text == spellings.yes -> return BooleanSide.TRUE_CONSTANT
        single.text == spellings.no -> return BooleanSide.FALSE_CONSTANT
        single.kind == TokenKind.STRING || single.kind == TokenKind.NUMBER ->
            return BooleanSide.OTHER_CONSTANT
        single.kind == TokenKind.NAME && single.text in spellings.temporal ->
            return BooleanSide.OTHER_CONSTANT
    }
    val declared = bareReferenceField(side, declaringGroup, element, inAFilterBody) ?: return null
    return when (declared.baseTypeClass()) {
        BaseTypeClass.YES -> BooleanSide.CONFIRM_FIELD
        BaseTypeClass.YES_NO -> BooleanSide.BOOLEAN_FIELD
        null -> null
        else -> BooleanSide.OTHER_FIELD
    }
}
