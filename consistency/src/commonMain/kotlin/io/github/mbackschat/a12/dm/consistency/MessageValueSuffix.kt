package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.messageParameterBodies
import io.github.mbackschat.a12.dm.consistency.syntax.plainParameterName
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn

/**
 * The two gates an error message's `.value` SUFFIX passes that its plain form does not.
 *
 * `$Field$` and `$Field.value$` bind the same field through the same membership check, and then the suffix
 * alone meets two further demands: the condition must not read that field through a STAR only
 * (`PARAM_VALUE_WITH_STARREFERENCE`), and the field must not be declared `noValueValidation`
 * (`INVALID_NO_VALUE_VALIDATION`). Each is measured against a within-model twin differing in one variable
 * ([KERNEL-FINDINGS KF279], `rulekit.validate.laws.RuleMessageParameterLawsTest`,
 * `…RuleMessageAdmissionGateLawsTest`).
 *
 * **Their ORDER is measured rather than chosen** (`…MessageValueSuffixGateOrderLawsTest`, 2026-09-15): on a
 * value-validation-free field the condition reads through a star alone, A12 answers
 * `PARAM_VALUE_WITH_STARREFERENCE`. Neither class says so on its own, and a checker reproducing the pair has
 * to pick one — picking the other would name the wrong class on a model A12 does refuse.
 *
 * **MEMBERSHIP pre-empts them both.** A suffix on a field the condition never reads is `INVALID_FIELD`, the
 * plain form's class, which [messageParameterFindings] owns — so this family asks the same membership set
 * first and stays silent wherever that one speaks.
 *
 * **Only the PLAIN name form is read.** A suffix is not admitted on the category, semantic-index or terminal
 * forms, and [plainParameterName] hands those over unrecognised; judging one here would claim a gate that
 * form never reaches.
 */
internal fun TrackedFactBase.messageValueSuffixFindings(): List<Finding> {
    val unsound = declarationsWithUnsoundDsl()
    val membership = entitiesReadByDeclaration()
    return declarations().filter { it.kind == "Rule" || it.kind == "Computation" }.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        if (element.path in unsound) {
            return@flatMap emptyList()
        }
        val read = membership[element.path] ?: return@flatMap emptyList()
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        val starredOnly = pathsReadThroughAStarAlone(element, group)
        val messages = body.authoredMessages() as? AuthoredMessages.Read ?: return@flatMap emptyList()
        messages.entries.flatMap { message ->
            if (message.text.carriesAnOffendingCharacter()) {
                return@flatMap emptyList()
            }
            messageParameterBodies(message.text).mapNotNull { parameter ->
                if (!parameter.trim().endsWith(VALUE_SUFFIX)) {
                    return@mapNotNull null
                }
                val name = plainParameterName(parameter.trim(), conditionLanguage) ?: return@mapNotNull null
                val path = (resolveEntity(group, name, element.origin) as? Reference.Resolved)?.path
                    ?: return@mapNotNull null
                if (path !in read) {
                    return@mapNotNull null
                }
                suffixDefect(path, starredOnly)?.let { code -> finding(code, name, element) }
            }
        }
    }
}

private const val VALUE_SUFFIX = ".value"

/** The star gate first, then the declaration one — A12's order, measured on a field carrying both. */
private fun TrackedFactBase.suffixDefect(path: String, starredOnly: Set<String>): String? = when {
    path in starredOnly -> STAR_REFERENCE
    declarations().firstOrNull { it.path == path }?.waivesValueValidation() == true -> NO_VALUE_VALIDATION
    else -> null
}

/**
 * The paths this declaration reads ONLY through a repetition wildcard.
 *
 * The gate is about how the CONDITION binds the field, not about how the parameter spells it: the measured
 * message writes `Lines/Sku` with no star at all and is refused because the condition's only read of that
 * field carries one. A single unstarred read anywhere clears it, which is why this collects both forms and
 * subtracts rather than testing the first read it finds.
 */
private fun TrackedFactBase.pathsReadThroughAStarAlone(element: TrackedDeclaration, group: String): Set<String> {
    val starred = mutableSetOf<String>()
    val plain = mutableSetOf<String>()
    val body = element.body ?: return emptySet()
    conditionTexts(element.kind, body).forEach { (_, text) ->
        referencesIn(text, conditionLanguage).forEach { written ->
            val resolved = (resolveEntity(group, written.replace("*", ""), element.origin) as? Reference.Resolved)
                ?.path ?: return@forEach
            if ('*' in written) starred += resolved else plain += resolved
        }
    }
    return starred - plain
}

private const val STAR_REFERENCE = "PARAM_VALUE_WITH_STARREFERENCE"

private const val NO_VALUE_VALIDATION = "INVALID_NO_VALUE_VALIDATION"

private fun finding(code: String, name: String, element: TrackedDeclaration): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    code,
    "in `errorMessage`: `${'$'}$name.value${'$'}` carries the value suffix " + if (code == STAR_REFERENCE) {
        "on a field this declaration reads only through a repetition wildcard — the suffix needs one row " +
            "to read, so drop the suffix or give the condition an unstarred read of the field"
    } else {
        "on a field declared `noValueValidation` — A12 has no validated value to show there, so the plain " +
            "`${'$'}$name${'$'}` form is the one this field admits"
    },
    element.path,
)
