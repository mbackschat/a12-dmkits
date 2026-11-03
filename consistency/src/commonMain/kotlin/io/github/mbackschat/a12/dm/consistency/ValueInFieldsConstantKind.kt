package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.OPERATOR_SPELLINGS
import io.github.mbackschat.a12.dm.consistency.syntax.OperatorId
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.categoryAccessesIn
import io.github.mbackschat.a12.dm.consistency.syntax.decodeAuthoredStringLiteral
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import io.github.mbackschat.a12.dm.consistency.syntax.splitAtIn
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize

/**
 * Which field declarations each CONSTANT KIND of `NumberOfValueInFields` admits in its list.
 *
 * `CheckAnzahlWertOpImpl.check` is one method whose body is a switch on the constant, so the class a model
 * draws is decided by what stands to the LEFT of the `In` — four arms, four classes, over the same list
 * ([KERNEL-FINDINGS KF379], `rulekit.validate.laws.ValueInFieldsConstantKindLawsTest`).
 *
 * | the constant | the declarations it admits | otherwise |
 * |---|---|---|
 * | `True` | Boolean, Confirm | `MVK_NO_TYPE_YES_OR_YESNO` |
 * | `False` | Boolean | `MVK_NO_TYPEYESNO` |
 * | a string | String, Custom, Enumeration | `MVK_NO_STRING_OR_ENUM` |
 * | a number | Number | `MVK_NO_NUMBER` |
 *
 * **The two boolean arms are not mirror images**, which is the asymmetry [KERNEL-FINDINGS KF113] already
 * locks from the other side: a Confirm declaration carries a yes-representation and no no-representation, so
 * `False` has nothing to compare and refuses it while `True` admits it.
 *
 * **A GROUP operand is expanded to every field BENEATH it, nested groups included** — measured on a group
 * declaring one Boolean directly and one text field inside a subgroup, which an expansion stopping at the
 * group's own members would have admitted.
 *
 * **Three gates run before the switch, and each leaves the call unjudged here** rather than drawing this
 * family's class on a model A12 refuses with another: a repeated entity is `MVK_DUPLICATE_PARAM1` (a nested
 * one `MVK_DUPLICATE_PARAM2`), a lone unstarred FIELD is `MVK_PARAMSIZE_INVALIDN`, and a root group is
 * `MVK_ROOT_GROUP_REFERENCED`. Naming a type class on any of those would be the wrong class on a correct
 * rejection.
 *
 * **The string arm stops at enumeration membership.** A12 walks the list field by field and a non-member constant on
 * an Enumeration draws `MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION` there and then, so the walk names that class at
 * such a field instead of continuing to a later one it could refuse. The domain is the declared tokens, in their case
 * and never a label (`adapter.laws.CheckerValueInFieldsMembershipAgreementTest`), a `tableName` beside them being read
 * past (KERNEL-FINDINGS KF654). A category read is counted against the category's tokens, which is unmeasured, so it
 * leaves the call unjudged.
 *
 * **Two classes of this construct are deliberately unreported because they cannot be reached through a
 * consistency check at all.** `MVK_DIFFERENT_YES_VALUE` and `MVK_DIFFERENT_NO_VALUE` compare the two
 * declarations' stored true (or false) representation, and the model conversion that feeds the checker
 * substitutes the literals `true`/`false` for whatever a Boolean or Confirm declaration wrote — so the two
 * are always equal and every mismatch is admitted, measured in the same grid. Reporting them would refuse a
 * model A12 accepts.
 *
 * **The string arm does not end at the type walk — two more gates follow it**, and both are reported here
 * ([KERNEL-FINDINGS KF409], `rulekit.validate.laws.ValueInFieldsDateConstantLawsTest`): a listed field that
 * waives value validation is `MVK_INVALID_RAW_TYPE`, and after that a literal A12 types as a DATE is
 * `MVK_NO_DATE_CONSTANT`. The order is the kernel's own phases rather than a per-field interleaving — the
 * type walk is the whole list's first pass, so it answers before either.
 *
 * **The last gate reads the CONSTANT and never the declarations it is counted against.** `CheckKonstanteImpl`
 * types every quoted literal by calling `DateUtils.isStringAValidDate` on it, so an ordinary list of text
 * fields is refused because of how the counted value happens to be spelled — and an enumeration that
 * DECLARES the date-shaped token is refused too, its domain check having passed. The near-misses bound it:
 * the typing switches on LENGTH first, so `"01.02"` is never tested, and `"32.01.2024"` matches its pattern
 * and fails a non-lenient parse. Both stay strings and are counted.
 *
 * **Silent on an unresolvable type definition**, whose type this reader cannot know; a resolvable
 * definition is judged as the type it defines ([KERNEL-FINDINGS KF602]).
 */
internal fun TrackedFactBase.valueInFieldsConstantKindFindings(): List<Finding> = declarations().flatMap { element ->
    valueInFieldsConstantContributions(element).take(1).map { it.finding }
}

internal fun TrackedFactBase.valueInFieldsConstantContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    val keywords = OPERATOR_SPELLINGS[conditionLanguage] ?: return emptyList()
    val carrier = keywords[OperatorId.NUMBER_OF_VALUE_IN_FIELDS] ?: return emptyList()
    val spellings = ConstantSpellings(
        keywords[OperatorId.TRUE],
        keywords[OperatorId.FALSE],
        listOfNotNull(keywords[OperatorId.TODAY], keywords[OperatorId.NOW]),
    )
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    return callContributions(element, execution) { member, call ->
        if (call.operator == carrier) constantKindFinding(call, spellings, group, element, member) else null
    }
}

/**
 * How the model's own language spells the constants this family keys on, absent where the table carries none.
 *
 * @param pointsInTime the two constants A12's switch does not handle at all. They are named rather than
 *     derived from "everything else" because everything else is a parse failure, which is a different
 *     verdict: only these two are grammatical constants the check falls through on.
 */
private data class ConstantSpellings(val yes: String?, val no: String?, val pointsInTime: List<String>)

/** One arm of A12's switch on the constant: which declarations it admits, and what it refuses with. */
private enum class ConstantArm(val code: String, val admitted: Set<FieldType>) {
    YES("MVK_NO_TYPE_YES_OR_YESNO", setOf(FieldType.BOOLEAN, FieldType.CONFIRM)),
    YES_NO("MVK_NO_TYPEYESNO", setOf(FieldType.BOOLEAN)),
    TEXT("MVK_NO_STRING_OR_ENUM", setOf(FieldType.STRING, FieldType.CUSTOM_FIELD, FieldType.ENUMERATION)),
    NUMBER("MVK_NO_NUMBER", setOf(FieldType.NUMBER)),

    /**
     * The FALL-THROUGH: a constant the switch tests for none of the four arms above.
     *
     * It refuses before reading a single declaration, so its admitted set is empty and never consulted —
     * `CheckAnzahlWertOpImpl.check` throws after its four `instanceof` tests, and the grammar's
     * `commonKonstante` carries exactly two further alternatives, the points in time.
     */
    POINT_IN_TIME("MVK_INVALID_CONST", emptySet()),
}

private fun TrackedFactBase.constantKindFinding(
    call: ConditionCall,
    spellings: ConstantSpellings,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    val (constants, entities) = call.splitAtIn() ?: return null
    // An ABSENT constant is refused exactly like a wrong one — measured, so the empty slot reports rather
    // than reading as a shape this reader could not reduce.
    val constantText = constants.singleOrNull()
        ?: return if (constants.isEmpty()) {
            unexpectedConstantToken("", element, member, call.constantSlotIn(member))
        } else {
            null
        }
    val arm = armOf(constantText, spellings)
        ?: return unexpectedConstantToken(constantText, element, member, call.constantSlotIn(member))
    val reads = entities.map { text ->
        operandReferenceIn(text, declaringGroup, element, call.inAFilterBody) {
            referencesIn(text, conditionLanguage).singleOrNull()?.let { OperandReference(it) }
        } ?: return null
    }
    val targets = addressedTargetsOf(reads, declaringGroup, element, call.inAFilterBody) ?: return null
    if (preEmptedBeforeTheSwitch(reads, targets)) {
        return null
    }
    // The fall-through arm refuses the CONSTANT and never reads a declaration, so it answers here rather
    // than through the type walk below — whose admitted set it has none of, and whose explain would name a
    // field that has nothing to do with the refusal.
    if (arm == ConstantArm.POINT_IN_TIME) {
        return Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            arm.code,
            "in `$member`: `${OperatorId.NUMBER_OF_VALUE_IN_FIELDS.catalogId}` counts `${constantText.javaTrimmed()}`, a point in time — this " +
                "operator counts a number, a text or a boolean constant and nothing else, whatever the " +
                "listed declarations are. Compare the date against a field instead of counting it",
            element.path,
            call.spanIn(member),
        )
    }
    val fields = targets.flatMap { target ->
        val declared = declarationAt(target) ?: return null
        fieldsUnder(declared) ?: return null
    }
    val categoryRead = entities.any { categoryAccessesIn(it, conditionLanguage).isNotEmpty() }
    val defect = when (val verdict = typeWalkVerdict(fields, arm, constantText, categoryRead)) {
        is ListVerdict.Offender -> ListDefect(
            arm.code,
            "counts `${constantText.javaTrimmed()}`, and `${verdict.field.path}` is declared " +
                "`${effectiveFieldType(verdict.field)}` — that constant only counts " +
                arm.admitted.joinToString { "`$it`" } +
                " declarations; drop the field from the list, or count a constant of its own kind",
        )
        is ListVerdict.NotAMember -> ListDefect(
            "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION",
            "counts `${constantText.javaTrimmed()}`, which is not a value of `${verdict.field.path}` — it declares " +
                verdict.domain.joinToString { "`$it`" } + "; count a stored value, never its label",
        )
        ListVerdict.Opaque -> return null
        ListVerdict.Clean -> stringArmTailDefect(fields, arm, constantText) ?: return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        defect.code,
        "in `$member`: `${OperatorId.NUMBER_OF_VALUE_IN_FIELDS.catalogId}` ${defect.detail}",
        element.path,
        call.spanIn(member),
    )
}

private class ListDefect(val code: String, val detail: String)

/** What the type walk found: a field this arm refuses, a class this family does not own, or neither. */
private sealed interface ListVerdict {
    data class Offender(val field: TrackedDeclaration) : ListVerdict

    /** The text constant is outside this enumeration's declared [domain], where A12's walk stops. */
    data class NotAMember(val field: TrackedDeclaration, val domain: List<String>) : ListVerdict

    /** A12 answers with a class this family does not carry, so naming one of its own would be the wrong one. */
    data object Opaque : ListVerdict

    data object Clean : ListVerdict
}

/**
 * The two gates the STRING arm runs once its type walk has cleared the whole list, in the kernel's order.
 *
 * Both are the string branch's alone — the boolean and number arms `return` before either — so an arm that
 * is not [ConstantArm.TEXT] has nothing here to fail. The raw-type gate is asked of the expanded field list
 * exactly as `checkIsValueValidation` asks it, and the date gate of the constant alone.
 */
private fun TrackedFactBase.stringArmTailDefect(
    fields: List<TrackedDeclaration>,
    arm: ConstantArm,
    constantText: String,
): ListDefect? {
    if (arm != ConstantArm.TEXT) {
        return null
    }
    fields.firstOrNull(::waivesValueValidation)?.let { raw ->
        return ListDefect(
            "MVK_INVALID_RAW_TYPE",
            "counts `${constantText.javaTrimmed()}` over `${raw.path}`, which waives value validation — A12 holds " +
                "its content unparsed and cannot compare it against a value",
        )
    }
    val constant = stringConstantOf(constantText) ?: return null
    if (!isAcceptedDateConstant(constant)) {
        return null
    }
    return ListDefect(
        "MVK_NO_DATE_CONSTANT",
        "counts `\"$constant\"`, which A12 types as a DATE from its own spelling rather than as text — the " +
            "operator counts a text, a number or a boolean, so respell the value (a leading zero more or a " +
            "separator changed takes it out of the date shapes `dd.MM.`, `HH:mm:ss`, `dd.MM.yyyy` and " +
            "`dd.MM.yyyy'T'HH:mm:ss`) or compare the fields against a date operand instead",
    )
}

/**
 * What A12's type walk over the list says: the first field this arm refuses, or why it may not be judged.
 *
 * The walk is the kernel's own: field by field in authored order, stopping at the first that fails. It stops
 * for a second reason as well — an Enumeration the string arm would refuse on MEMBERSHIP is A12's answer at
 * that position, so continuing past it would report a later field's type class where A12 reports the
 * membership one.
 *
 * [ListVerdict.Clean] is what licenses the two later gates, which is why the three answers had to be
 * separated: "no offender" and "cannot judge" were one `null` while the type class was all this family
 * reported, and reading the second as the first would name a later gate's class on a list A12 has already
 * refused.
 */
private fun TrackedFactBase.typeWalkVerdict(
    fields: List<TrackedDeclaration>,
    arm: ConstantArm,
    constantText: String,
    categoryRead: Boolean,
): ListVerdict {
    fields.forEach { field ->
        val type = effectiveFieldType(field) ?: return ListVerdict.Opaque
        // A type this reader cannot resolve has no base-type class, so no arm may judge it.
        if (type.baseTypeClass == null) {
            return ListVerdict.Opaque
        }
        if (arm == ConstantArm.TEXT && type == FieldType.ENUMERATION) {
            val body = enumerationBodyOf(field) ?: return ListVerdict.Opaque
            val domain = body.lookupDomain(null) ?: return ListVerdict.Opaque
            if (stringConstantOf(constantText) !in domain) {
                return if (categoryRead) ListVerdict.Opaque else ListVerdict.NotAMember(field, domain)
            }
            return@forEach
        }
        if (type !in arm.admitted) {
            return ListVerdict.Offender(field)
        }
    }
    return ListVerdict.Clean
}

/** Which arm of the switch this constant takes, or null for a shape none of the four covers. */
private fun armOf(constantText: String, spellings: ConstantSpellings): ConstantArm? {
    val tokens = tokenize(constantText)
    val single = tokens.singleOrNull()
    return when {
        single == null -> if (isNumberLiteral(tokens)) ConstantArm.NUMBER else null
        single.text == spellings.yes -> ConstantArm.YES
        single.text == spellings.no -> ConstantArm.YES_NO
        single.kind == TokenKind.STRING -> ConstantArm.TEXT
        single.kind == TokenKind.NUMBER -> ConstantArm.NUMBER
        single.text in spellings.pointsInTime -> ConstantArm.POINT_IN_TIME
        else -> null
    }
}

/**
 * The slot holds something A12's grammar does not admit there at all — `MVK_UNEXPECTED_TOKEN`.
 *
 * `konstante` is `commonKonstante` alone, so the slot takes one of six alternatives and nothing built from
 * them: an operation is refused, and so are PARENTHESES around a legal constant (measured 2026-09-15,
 * [KERNEL-FINDINGS KF409]). That is what makes this a position a checker may refuse, rather than only the
 * four typed arms above.
 *
 * **Placed at the slot's first token, and never silent on what the slot holds.** A12's parse stops in the
 * slot ahead of every reference phase, so a name there — declared, undeclared, or a spec that resolves and
 * whose star is illegal — draws this class and no resolution or wildcard class (measured 2026-09-15,
 * `rulekit.validate.laws.ValueInFieldsConstantKindLawsTest`). Recognition records the failed token demand
 * before any semantic completion in the slot; the editor span does not establish that ordering (R5).
 */
private fun unexpectedConstantToken(constantText: String, element: TrackedDeclaration, member: String, span: TextSpan) =
    Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_UNEXPECTED_TOKEN",
        "in `$member`: `${OperatorId.NUMBER_OF_VALUE_IN_FIELDS.catalogId}` counts " +
            (constantText.javaTrimmed().takeIf { it.isNotEmpty() }?.let { "`$it`, which is not a constant" }
                ?: "nothing — the slot before `In` is empty") +
            ". That position takes a number, a text, `True`, `False` or a point in time, and nothing " +
            "assembled from them, so even parentheses around a legal constant are refused",
        element.path,
        span,
    )

/** The constant slot: the call's first operand, which holds the `In` and everything ahead of it. */
private fun ConditionCall.constantSlotIn(member: String): TextSpan = operands.first().spanIn(member)

private fun stringConstantOf(constantText: String): String? {
    val token = tokenize(constantText).singleOrNull()?.takeIf { it.kind == TokenKind.STRING } ?: return null
    return token.text.decodeAuthoredStringLiteral()
}
