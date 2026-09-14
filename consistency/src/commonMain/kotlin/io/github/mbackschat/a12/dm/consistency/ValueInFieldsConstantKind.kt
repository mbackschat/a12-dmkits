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
 * belongs to [enumConstantDomainFindings], which does not yet read this carrier.
 *
 * **Two classes of this construct are deliberately unreported because they cannot be reached through a
 * consistency check at all.** `MVK_DIFFERENT_YES_VALUE` and `MVK_DIFFERENT_NO_VALUE` compare the two
 * declarations' stored true (or false) representation, and the model conversion that feeds the checker
 * substitutes the literals `true`/`false` for whatever a Boolean or Confirm declaration wrote — so the two
 * are always equal and every mismatch is admitted, measured in the same grid. Reporting them would refuse a
 * model A12 accepts.
 *
 * **Silent on a string field declaring no value validation** (`MVK_INVALID_RAW_TYPE`, checked after the type
 * gate of the string arm) and on a `TypeDefType` or `UnspecifiedType` declaration, whose effective type this
 * reader does not resolve.
 */
fun PreparedModel.valueInFieldsConstantKindFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val carrier = keywords[VALUE_IN_FIELDS] ?: return emptyList()
    val spellings = ConstantSpellings(keywords[TRUE_CONSTANT], keywords[FALSE_CONSTANT])
    return elements.mapNotNull { element ->
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

/** How the model's own language spells the two boolean constants, absent where the table carries neither. */
private data class ConstantSpellings(val yes: String?, val no: String?)

/** One arm of A12's switch on the constant: which declarations it admits, and what it refuses with. */
private enum class ConstantArm(val code: String, val admitted: Set<String>) {
    YES("MVK_NO_TYPE_YES_OR_YESNO", setOf("BooleanType", "ConfirmType")),
    YES_NO("MVK_NO_TYPEYESNO", setOf("BooleanType")),
    TEXT("MVK_NO_STRING_OR_ENUM", setOf("StringType", "CustomType", "EnumerationType")),
    NUMBER("MVK_NO_NUMBER", setOf("NumberType")),
}

/** The declarations whose effective type this reader does not resolve, so no arm may judge them. */
private val UNRESOLVED_TYPES = setOf("TypeDefType", "UnspecifiedType")

private fun PreparedModel.constantKindFinding(
    call: ConditionCall,
    spellings: ConstantSpellings,
    declaringGroup: String,
    element: DeclaredElement,
    member: String,
): Finding? {
    val (constants, entities) = call.splitAtIn() ?: return null
    val constantText = constants.singleOrNull() ?: return null
    val arm = armOf(constantText, spellings) ?: return null
    val written = entities.map { referencesIn(it, conditionLanguage).singleOrNull() ?: return null }
    val targets = written.map { reference ->
        val resolved = resolveEntity(declaringGroup, reference.replace("*", ""), element.origin)
        (resolved as? Reference.Resolved)?.path ?: return null
    }
    if (preEmptedBeforeTheSwitch(written, targets)) {
        return null
    }
    val fields = targets.flatMap { target ->
        val declared = elements.firstOrNull { it.path == target } ?: return null
        when (declared.declaredKind) {
            DeclaredKind.FIELD -> listOf(declared)
            DeclaredKind.GROUP ->
                elements.filter { it.declaredKind == DeclaredKind.FIELD && it.path.startsWith("$target/") }
            else -> return null
        }
    }
    val offending = firstOffendingField(fields, arm, constantText) ?: return null
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        arm.code,
        "in `$member`: `$VALUE_IN_FIELDS` counts `${constantText.trim()}`, and `${offending.path}` is " +
            "declared `${offending.declaredFieldType()}` — that constant only counts " +
            arm.admitted.joinToString { "`$it`" } +
            " declarations; drop the field from the list, or count a constant of its own kind",
        element.path,
    )
}

/**
 * The first listed field the arm refuses, or null when none does — and null too wherever A12 would answer
 * with something other than this arm's class.
 *
 * The walk is the kernel's own: field by field in authored order, stopping at the first that fails. It stops
 * for a second reason as well — an Enumeration the string arm would refuse on MEMBERSHIP is A12's answer at
 * that position, so continuing past it would report a later field's type class where A12 reports the
 * membership one.
 */
private fun firstOffendingField(
    fields: List<DeclaredElement>,
    arm: ConstantArm,
    constantText: String,
): DeclaredElement? {
    fields.forEach { field ->
        val type = field.declaredFieldType() ?: return null
        if (type in UNRESOLVED_TYPES) {
            return null
        }
        if (arm == ConstantArm.TEXT && type == "EnumerationType") {
            val domain = field.enumerationBody()?.declaredEnumValues()?.toSet() ?: return null
            if (stringConstantOf(constantText) !in domain) {
                return null
            }
            return@forEach
        }
        if (type !in arm.admitted) {
            return field
        }
    }
    return null
}

/**
 * Whether one of the three gates ahead of the constant switch answers this list instead.
 *
 * A reference carrying a wildcard is exempt from the duplicate test, as A12's own `checkDuplicatesInDirectRefs`
 * skips it: two starred reads of one list are two different rows rather than the same entity twice.
 */
private fun PreparedModel.preEmptedBeforeTheSwitch(written: List<String>, targets: List<String>): Boolean {
    val keyed = written.indices.filterNot { '*' in written[it] }.map { targets[it] }
    val repeated = keyed.size != keyed.distinct().size
    val nested = targets.any { inner -> targets.any { outer -> inner.startsWith("$outer/") } }
    val rootGroup = targets.any { it.count { character -> character == '/' } == 1 }
    val loneField = targets.size == 1 && '*' !in written.first() &&
        elements.firstOrNull { it.path == targets.first() }?.declaredKind == DeclaredKind.FIELD
    return repeated || nested || rootGroup || loneField
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
        else -> null
    }
}

/** The string constant this text is, or null when it is anything else. */
private fun stringConstantOf(constantText: String): String? {
    val token = tokenize(constantText).singleOrNull()?.takeIf { it.kind == TokenKind.STRING } ?: return null
    return token.text.removeSurrounding("\"").replace("\"\"", "\"")
}
