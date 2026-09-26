package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * That no text A12 validates as part of the model carries a character outside the Basic Multilingual Plane.
 *
 * A12 reads the model itself as a document of its metamodel ([KERNEL-FINDINGS KF146]) and runs its RUNTIME
 * formal charset check over it, whose legal set is the BMP when no charset is configured ([KF32]). So a UTF-16
 * surrogate is refused: a supplementary-plane character, or either half of one alone. The refusal carries NO
 * class, only the sentence "The field contains one or several unsupported signs.", so this family owns a code
 * for it rather than citing one A12 does not use there ([KF489], `rulekit.validate.laws.ModelTextRepertoireLawsTest`).
 *
 * **The carriers are measured one by one, and some texts are not among them.** Every text of a rule or a
 * computation is one — its condition or preconditions, operation, error code and message. So are a field's
 * annotations, enum values and enum labels, and a group's annotations. The labels of the model, of a group and
 * of a field are NOT, and neither is a message or an enum label written for a locale the model does not
 * declare. Reading the check as "every text of the model" would refuse three kinds of legal model.
 *
 * **The unit is the element, and its PATH is a carrier.** Two defective texts of one rule or one field draw
 * one finding. A group name carrying the character is reported on the group and again on every element
 * beneath it, whose own names are plain, so this family judges each declaration's path rather than its name.
 *
 * **It stands beside every other class, and neither withholds the other.** A12 lists it with a refused
 * condition's class, with the message's own classes and with a broken error target's, so report assembly
 * holds it outside both the DSL's withholding and the error target's stop. An `ONLY` variant filter is the
 * one thing that takes it away, with the rest of the rule.
 *
 * **What it leaves to A12**, all of it under-reporting: a mounted provider's declarations, whose reach through
 * the host was not measured, and every member not listed above — a field type's own members, a category, the
 * model's header. The report publishes that silence once, as a coverage gap.
 */
internal fun TrackedFactBase.modelTextRepertoireFindings(): List<Finding> =
    declarations().filterNot { it.origin.isMounted }.mapNotNull { element ->
        element.carriedTexts(declaredLocales).firstNotNullOfOrNull { it.unsupported() }
            ?.let { element.repertoireFinding(it) }
    }

/** This family's own code, since A12's refusal carries none. */
internal const val RK_UNSUPPORTED_CHARACTER = "RK_UNSUPPORTED_CHARACTER"

/** One text A12 validates, and what to call it in a finding. */
private data class CarriedText(val describe: String, val text: String) {
    fun unsupported(): Unsupported? =
        text.indexOfFirst { it.isSurrogate() }.takeIf { it >= 0 }?.let { Unsupported(this, it) }
}

private data class Unsupported(val carrier: CarriedText, val offset: Int)

/**
 * Every measured carrier of this declaration, its path first, since that is what A12 names the element by.
 *
 * None at all for a payload A12 refuses to bind, since A12 never examines that source.
 */
private fun TrackedDeclaration.carriedTexts(declaredLocales: List<String>): List<CarriedText> {
    val kind = declaredKind ?: return emptyList()
    val body = body ?: return emptyList()
    val members = when (kind) {
        DeclaredKind.RULE -> listOfNotNull(body.member("errorCondition"), body.member("errorCode")) +
            body.messages(declaredLocales)
        DeclaredKind.COMPUTATION -> listOfNotNull(body.member("commonPrecondition")) +
            body.alternativeTexts() + body.messages(declaredLocales)
        DeclaredKind.FIELD -> annotationTexts() + (enumerationBody()?.enumTexts(declaredLocales) ?: emptyList())
        DeclaredKind.GROUP -> annotationTexts()
    }
    return listOf(CarriedText("its path `$path`", path)) + members
}

private fun JsonObject.member(key: String): CarriedText? = string(key)?.let { CarriedText("`$key`", it) }

/** The texts A12's error-text checker examines, which are the ones its charset check reaches too. */
private fun JsonObject.messages(declaredLocales: List<String>): List<CarriedText> =
    (authoredMessages() as? AuthoredMessages.Read)?.examined(declaredLocales).orEmpty()
        .map { CarriedText("`errorMessage` for `${it.localeKey}`", it.text) }

private fun JsonObject.alternativeTexts(): List<CarriedText> =
    (this["computationAlternatives"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().flatMap { alternative ->
        listOfNotNull(alternative.member("precondition"), alternative.member("operation"))
    }

private fun TrackedDeclaration.annotationTexts(): List<CarriedText> =
    annotations.orEmpty().filterIsInstance<JsonObject>().flatMap { annotation ->
        listOfNotNull(
            annotation.string("name")?.let { CarriedText("an annotation's name", it) },
            annotation.string("value")?.let { CarriedText("an annotation's value", it) },
        )
    }

/** An enum's values and, for a declared locale only, their labels. */
private fun JsonObject.enumTexts(declaredLocales: List<String>): List<CarriedText> =
    (this["values"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().flatMap { entry ->
        listOfNotNull(entry.string("value")?.let { CarriedText("the enum value `$it`", it) }) +
            (entry["label"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                .filter { label -> label.string("locale")?.let { localeKeyOrNull(it) } in declaredLocales }
                .mapNotNull { label -> label.string("text")?.let { CarriedText("an enum label", it) } }
    }

private fun TrackedDeclaration.repertoireFinding(found: Unsupported): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    RK_UNSUPPORTED_CHARACTER,
    "${found.carrier.describe} carries a character outside the Basic Multilingual Plane at offset " +
        "${found.offset}, which A12 refuses with no class: \"The field contains one or several unsupported " +
        "signs.\" A12 admits no UTF-16 surrogate here, paired or alone",
    path,
)
