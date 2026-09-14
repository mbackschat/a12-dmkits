package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A string constant in a value LIST, against the Enumeration domain it is compared to.
 *
 * `CheckEntityListenUtils.checkEnumType` splits on whether the reference is categorized: a plain one checks
 * the enum's declared VALUES (`MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION`), a `->` one checks that
 * category's classifications (`MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_CATEGORY`)
 * ([KERNEL-FINDINGS KF360], `rulekit.validate.laws.EnumConstantDomainLawsTest`).
 *
 * **Two carriers, measured — and the family is deliberately narrower than the law.** The two SCALAR
 * predicates `FieldValueIncludedInValueList` and `FieldValueNotIncludedInValueList` check one reference's
 * domain and are what this reports. The three multi-member QUANTIFIERS check the UNION of their members'
 * domains and answer with a FOURTH class, `MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY`; a
 * COMPARISON answers with a fifth, `MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON`, whichever way it is
 * categorized. Both are measured in the same grid and neither is reproduced here, because each needs an
 * operand shape this reader does not reduce — widening by analogy would name the scalars' class on them.
 *
 * **And the CATEGORIZED scalar list is the third under-report, for the same reason one layer down.** A
 * `Tier -> Band` operand is not a bare entity spec, so the call reader reduces it to nothing and this family
 * never sees it; A12 answers it with `MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_CATEGORY`, measured. The
 * reduction is deliberate and shared, so widening it is a change to every family that reads an operand.
 *
 * **A STRING field's list is admitted whatever it carries**, `isLikeString` being a `continue` in A12's own
 * loop. So the family reports only where the reference is an Enumeration, which is also why a `TypeDefType`
 * field is silent: its body is a coverage gap this checker declares rather than reads, and a field typed
 * through one may be either.
 */
fun PreparedModel.enumConstantDomainFindings(): List<Finding> {
    val carriers = SCALAR_VALUE_LISTS.mapNotNull { CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(it) }
    return elements.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .filter { it.operator in carriers }
                .flatMap { call -> constantFindings(call, group, element, member) }
        }
    }
}

/** The two value-list predicates that take ONE reference, named by their catalog ids. */
private val SCALAR_VALUE_LISTS = setOf("FieldValueIncludedInValueList", "FieldValueNotIncludedInValueList")

private fun PreparedModel.constantFindings(
    call: ConditionCall,
    declaringGroup: String,
    element: DeclaredElement,
    member: String,
): List<Finding> {
    val reference = call.operands.firstOrNull() ?: return emptyList()
    val path = reference.base ?: return emptyList()
    val resolved = resolveEntity(declaringGroup, path.replace("*", ""), element.origin)
    if (resolved !is Reference.Resolved) {
        return emptyList()
    }
    val target = elements.firstOrNull { it.path == resolved.path && it.declaredKind == DeclaredKind.FIELD }
        ?: return emptyList()
    val enumeration = target.enumerationBody() ?: return emptyList()
    val domain = enumeration.declaredValues() ?: return emptyList()
    return call.operands.drop(1).mapNotNull { operand ->
        val constant = operand.literal ?: return@mapNotNull null
        if (constant in domain) {
            return@mapNotNull null
        }
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION",
            "in `$member`: `\"$constant\"` is not a value of `${target.path}` — it admits " +
                domain.joinToString { "`$it`" },
            element.path,
        )
    }
}

/**
 * The values a constant may take, or null when the declaration is a shape no row covers.
 *
 * An unreadable `values` belongs to [enumCategoryFindings] and A12 answers it with a class of its own, so it
 * makes the domain unreadable here rather than silently shortening it — a shortened domain would turn a
 * neighbouring defect into a wrong constant class on every literal in the list.
 */
private fun JsonObject.declaredValues(): Set<String>? {
    val values = this["values"] as? JsonArray ?: return null
    return values.map { entry ->
        ((entry as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull ?: return null
    }.toSet()
}
