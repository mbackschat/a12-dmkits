package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import io.github.mbackschat.a12.dm.consistency.syntax.splitAtIn
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
 * **The string arm yields to enumeration membership for the same reason.** A12 walks the list field by field
 * and a non-member constant on an Enumeration draws `MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION` there and
 * then, so the walk stops at such a field instead of continuing to a later one it could refuse. That class
 * belongs to [scalarValueListGateFindings], whose own carriers are a different kernel method's — so this
 * construct's domain class is nobody's yet.
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
 * **Silent on a `TypeDefType` or `UnspecifiedType` declaration**, whose effective type this reader does not
 * resolve.
 */
internal fun TrackedFactBase.valueInFieldsConstantKindFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val carrier = keywords[VALUE_IN_FIELDS] ?: return emptyList()
    val spellings = ConstantSpellings(
        keywords[TRUE_CONSTANT],
        keywords[FALSE_CONSTANT],
        listOfNotNull(keywords[TODAY_CONSTANT], keywords[NOW_CONSTANT]),
    )
    return declarations().mapNotNull { element ->
        val body = element.body ?: return@mapNotNull null
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .filter { it.operator == carrier }
                .firstNotNullOfOrNull { call -> constantKindFinding(call, spellings, group, element, member) }
        }
    }
}

private const val VALUE_IN_FIELDS = "NumberOfValueInFields"

private const val TRUE_CONSTANT = "True"

private const val FALSE_CONSTANT = "False"

private const val TODAY_CONSTANT = "Today"

private const val NOW_CONSTANT = "Now"

/**
 * How the model's own language spells the constants this family keys on, absent where the table carries none.
 *
 * @param pointsInTime the two constants A12's switch does not handle at all. They are named rather than
 *     derived from "everything else" because everything else is a parse failure, which is a different
 *     verdict: only these two are grammatical constants the check falls through on.
 */
private data class ConstantSpellings(val yes: String?, val no: String?, val pointsInTime: List<String>)

/** One arm of A12's switch on the constant: which declarations it admits, and what it refuses with. */
private enum class ConstantArm(val code: String, val admitted: Set<String>) {
    YES("MVK_NO_TYPE_YES_OR_YESNO", setOf("BooleanType", "ConfirmType")),
    YES_NO("MVK_NO_TYPEYESNO", setOf("BooleanType")),
    TEXT("MVK_NO_STRING_OR_ENUM", setOf("StringType", "CustomFieldType", "EnumerationType")),
    NUMBER("MVK_NO_NUMBER", setOf("NumberType")),

    /**
     * The FALL-THROUGH: a constant the switch tests for none of the four arms above.
     *
     * It refuses before reading a single declaration, so its admitted set is empty and never consulted —
     * `CheckAnzahlWertOpImpl.check` throws after its four `instanceof` tests, and the grammar's
     * `commonKonstante` carries exactly two further alternatives, the points in time.
     */
    POINT_IN_TIME("MVK_INVALID_CONST", emptySet()),
}

/** The declarations whose effective type this reader does not resolve, so no arm may judge them. */
private val UNRESOLVED_TYPES = setOf("TypeDefType", "UnspecifiedType")

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
            unexpectedConstantToken("", declaringGroup, element, member)
        } else {
            null
        }
    val arm = armOf(constantText, spellings)
        ?: return unexpectedConstantToken(constantText, declaringGroup, element, member)
    val written = entities.map { referencesIn(it, conditionLanguage).singleOrNull() ?: return null }
    val targets = written.map { addressedTargetOf(it, declaringGroup, element) ?: return null }
    if (preEmptedBeforeTheSwitch(written, targets)) {
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
            "in `$member`: `$VALUE_IN_FIELDS` counts `${constantText.trim()}`, a point in time — this " +
                "operator counts a number, a text or a boolean constant and nothing else, whatever the " +
                "listed declarations are. Compare the date against a field instead of counting it",
            element.path,
        )
    }
    val fields = targets.flatMap { target ->
        val declared = allDeclarations().firstOrNull { it.path == target } ?: return null
        when (declared.declaredKind) {
            DeclaredKind.FIELD -> listOf(declared)
            DeclaredKind.GROUP ->
                allDeclarations().filter { it.declaredKind == DeclaredKind.FIELD && it.path.startsWith("$target/") }
            else -> return null
        }
    }
    val defect = when (val verdict = typeWalkVerdict(fields, arm, constantText)) {
        is ListVerdict.Offender -> ListDefect(
            arm.code,
            "counts `${constantText.trim()}`, and `${verdict.field.path}` is declared " +
                "`${verdict.field.declaredFieldType()}` — that constant only counts " +
                arm.admitted.joinToString { "`$it`" } +
                " declarations; drop the field from the list, or count a constant of its own kind",
        )
        ListVerdict.Opaque -> return null
        ListVerdict.Clean -> stringArmTailDefect(fields, arm, constantText) ?: return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        defect.code,
        "in `$member`: `$VALUE_IN_FIELDS` ${defect.detail}",
        element.path,
    )
}

private class ListDefect(val code: String, val detail: String)

/** What the type walk found: a field this arm refuses, a class this family does not own, or neither. */
private sealed interface ListVerdict {
    data class Offender(val field: TrackedDeclaration) : ListVerdict

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
private fun stringArmTailDefect(
    fields: List<TrackedDeclaration>,
    arm: ConstantArm,
    constantText: String,
): ListDefect? {
    if (arm != ConstantArm.TEXT) {
        return null
    }
    fields.firstOrNull { it.waivesValueValidation() }?.let { raw ->
        return ListDefect(
            "MVK_INVALID_RAW_TYPE",
            "counts `${constantText.trim()}` over `${raw.path}`, which waives value validation — A12 holds " +
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
private fun typeWalkVerdict(
    fields: List<TrackedDeclaration>,
    arm: ConstantArm,
    constantText: String,
): ListVerdict {
    fields.forEach { field ->
        val type = field.declaredFieldType() ?: return ListVerdict.Opaque
        if (type in UNRESOLVED_TYPES) {
            return ListVerdict.Opaque
        }
        if (arm == ConstantArm.TEXT && type == "EnumerationType") {
            val domain = field.enumerationBody()?.declaredEnumValues()?.toSet() ?: return ListVerdict.Opaque
            if (stringConstantOf(constantText) !in domain) {
                return ListVerdict.Opaque
            }
            return@forEach
        }
        if (type !in arm.admitted) {
            return ListVerdict.Offender(field)
        }
    }
    return ListVerdict.Clean
}

/**
 * Whether one of the three gates ahead of the constant switch answers this list instead.
 *
 * Two of them are the shared pre-gates every entity list meets ([entityListPreGateAnswers]); the third is
 * this operator's own arity gate, which takes more than one field or one group.
 */
private fun TrackedFactBase.preEmptedBeforeTheSwitch(written: List<String>, targets: List<String>): Boolean {
    val loneField = targets.size == 1 && '*' !in written.first() &&
        allDeclarations().firstOrNull { it.path == targets.first() }?.declaredKind == DeclaredKind.FIELD
    return entityListPreGateAnswers(written, targets) || loneField
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
 * **Silent when the slot's own text carries a reference this model cannot ADDRESS.** A12 reports the first
 * defect in the text and nothing after it, and the reference families already speak about that token — so
 * reporting here as well would hand the author two classes where A12 draws one.
 *
 * Addressing is deliberately wider than resolution, and only the wider half is load-bearing: an UNRESOLVABLE
 * name is already withheld estate-wide by the gate `CONDITION_TEXT_CODES` drives, so standing down on it
 * restates that gate. What no other gate covers is a spec that RESOLVES and whose repetition is unaddressed —
 * an illegal star, or a repeating level neither starred nor iterated — because the declaration then carries
 * no unresolvable name at all. A12 answers the PARSE class there, ahead of every reference phase
 * (`rulekit.validate.laws.ValueInFieldsConstantKindLawsTest`, measured 2026-09-15), so the wildcard class
 * this estate draws instead is one class either way rather than two.
 *
 * The divergence that stands is the resolvable-name one, recorded in this family's mirror: on an UNDECLARED
 * name the author sees a resolution class where A12 reports the token. Closing it needs the reader to stop
 * resolving the slot at all, which needs offsets `ConditionCall` does not carry.
 */
private fun TrackedFactBase.unexpectedConstantToken(
    constantText: String,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    val unresolvable = referencesIn(constantText, conditionLanguage)
        .any { addressedTargetOf(it, declaringGroup, element) == null }
    if (unresolvable) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_UNEXPECTED_TOKEN",
        "in `$member`: `$VALUE_IN_FIELDS` counts " +
            (constantText.trim().takeIf { it.isNotEmpty() }?.let { "`$it`, which is not a constant" }
                ?: "nothing — the slot before `In` is empty") +
            ". That position takes a number, a text, `True`, `False` or a point in time, and nothing " +
            "assembled from them, so even parentheses around a legal constant are refused",
        element.path,
    )
}

/** The string constant this text is, or null when it is anything else. */
private fun stringConstantOf(constantText: String): String? {
    val token = tokenize(constantText).singleOrNull()?.takeIf { it.kind == TokenKind.STRING } ?: return null
    return token.text.removeSurrounding("\"").replace("\"\"", "\"")
}
