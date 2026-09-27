package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The four characters A12 refuses in an enumeration's declared TEXTS, whichever of the three carries them.
 *
 * Not to be confused with [supportedCharacterFindings], which judges an enum value against the MODEL's own
 * `supportedCharacters` restriction. This law is A12's own and is asked of every model: `CheckConfiguration`
 * registers `ungueltigerXMLWert`, `ungueltigerOberflaechenWert` and `ungueltigerKategorieWert` as three
 * instances of one class whose whole body tests for a newline, a tab, a carriage return or a pipe — so the
 * CARRIER picks the class and the four characters are the entire predicate ([KERNEL-FINDINGS KF418],
 * `rulekit.validate.laws.EnumValueForbiddenCharactersLawsTest`).
 *
 * **Half the predicate never reaches those classes.** A line break is refused one gate earlier and CODELESS —
 * `"Field with path '…': Only values without line breaks are allowed."` — so this family owns a code for it
 * rather than citing one A12 does not use there. That gate pre-empts the coded one WITHIN one text only: a
 * sibling value's tab is still reported, and the refusal is raised once for the FIELD however many of its
 * texts carry a break. Both halves are measured, and neither follows from the other.
 *
 * **The unit of report is the TEXT.** A value carrying both a tab and a pipe draws one finding where the
 * `supportedCharacters` family next door would draw two, because that one reports per (value, character) and
 * this predicate is a single containment test. Two defective values draw two, and each carrier answers on its
 * own account — a value and its own label both defective draw both classes.
 *
 * **A label is judged only for a DECLARED locale.** A label written for a locale the model does not declare
 * is ADMITTED however it is spelled, which is the row that keeps this family from refusing a model A12 takes:
 * the obvious reading — judge every text present — is an over-rejection.
 *
 * **What it leaves alone**, all of it under-reporting: a `values` or `categories` entry whose shape this
 * checker cannot read, published as a [CoverageGap]; the enumeration's `sValue`, which nobody has put to this
 * gate; and the category's own `name`, which A12 also refuses but under a NAME alphabet — its sentence is
 * *"A category name starts with a letter and may contain only letters, digits and the underscore"*, so the
 * character that triggers it is incidental and reporting it here would state the wrong law.
 */
internal fun TrackedFactBase.enumTextForbiddenCharacterFindings(): List<Finding> =
    defaultVariantFields().flatMap { field ->
        val body = field.enumerationBody() ?: return@flatMap emptyList()
        val texts = declaredTexts(body)
        val broken = texts.filter { it.text.any(::isALineBreak) }
        // A12 states the line-break refusal once for the field and ahead of every coded class, and the coded
        // gate is not asked about a text that carries one — but it IS still asked about the others.
        val lineBreak = if (broken.isEmpty()) emptyList() else listOf(field.lineBreakFinding(broken.first()))
        lineBreak + texts.filterNot { it.text.any(::isALineBreak) }
            .filter { it.text.any { character -> character == '\t' || character == '|' } }
            .map { field.codedFinding(it) }
    }

/**
 * The declarations this family stayed silent about, so silence is never read as agreement.
 *
 * Per DECLARATION for the ordinary reason: a consumer needs to know which enumeration was left to A12, not
 * that some were.
 */
internal fun TrackedFactBase.enumTextForbiddenCharacterGaps(): List<CoverageGap> =
    defaultVariantFields().mapNotNull { field ->
        val unread = field.enumerationBody()?.unreadableText() ?: return@mapNotNull null
        CoverageGap(
            field.path,
            "a declared text of this Enumeration was not judged against the characters A12 refuses in one, " +
                "so A12 alone judges it: $unread",
        )
    }

/** Which carrier a declared text sits in — the carrier is what picks A12's class. */
private enum class TextCarrier(val code: String, val describe: String) {
    INTERNAL_VALUE("MVK_INTERNAL_VALUE_INVALID", "the enum value"),
    DISPLAY_VALUE("MVK_DISPLAY_VALUE_INVALID", "the display label"),
    CATEGORY_VALUE("MVK_CATEGORY_VALUE_INVALID", "the category token"),
}

private data class DeclaredText(val carrier: TextCarrier, val text: String)

/** Every text of this enumeration A12 asks the question of, in the order it asks. */
private fun TrackedFactBase.declaredTexts(body: JsonObject): List<DeclaredText> {
    val values = body["values"] as? JsonArray ?: return emptyList()
    val fromValues = values.filterIsInstance<JsonObject>().flatMap { entry ->
        val value = entry.string("value")?.let { DeclaredText(TextCarrier.INTERNAL_VALUE, it) }
        listOfNotNull(value) + declaredLabels(entry)
    }
    val categories = body["categories"] as? JsonArray ?: return fromValues
    return fromValues + categories.filterIsInstance<JsonObject>().flatMap { category ->
        (category["values"] as? JsonArray).orEmpty()
            .mapNotNull { it.tokenOrNull() }
            .map { DeclaredText(TextCarrier.CATEGORY_VALUE, it) }
    }
}

/** …and only for a locale the model DECLARES, which is the over-rejection this family must not make. */
private fun TrackedFactBase.declaredLabels(entry: JsonObject): List<DeclaredText> =
    (entry["label"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        .filter { label -> label.string("locale")?.let { localeKeyOrNull(it) } in declaredLocales }
        .mapNotNull { label -> label.string("text") }
        .map { DeclaredText(TextCarrier.DISPLAY_VALUE, it) }

private fun isALineBreak(character: Char): Boolean = character == '\n' || character == '\r'

private fun TrackedDeclaration.lineBreakFinding(broken: DeclaredText): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "RK_ENUM_TEXT_LINE_BREAK",
    "${broken.carrier.describe} `${broken.text.replace("\n", "\\n").replace("\r", "\\r")}` carries a line " +
        "break, which A12 refuses — it states that refusal in prose and names no class, so this code is " +
        "this checker's own",
    path,
)

private fun TrackedDeclaration.codedFinding(declared: DeclaredText): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    declared.carrier.code,
    "${declared.carrier.describe} `${declared.text.replace("\t", "\\t")}` carries a tab or a pipe, which " +
        "A12 refuses in every text an Enumeration declares",
    path,
)

/** Why a declared text was skipped, or null when every one of them was judged. */
private fun JsonObject.unreadableText(): String? {
    val values = this["values"] ?: return null
    val entries = values as? JsonArray ?: return "its `values` is present and is not an array"
    val unreadableValue = entries.firstNotNullOfOrNull { entry ->
        val declared = entry as? JsonObject ?: return@firstNotNullOfOrNull "a `values` entry is not an object"
        if (declared.string("value") == null) "a `values` entry spells no string `value`" else null
    }
    if (unreadableValue != null) {
        return unreadableValue
    }
    val categories = this["categories"] ?: return null
    val declared = categories as? JsonArray ?: return "its `categories` is present and is not an array"
    return declared.firstNotNullOfOrNull { entry ->
        val category = entry as? JsonObject ?: return@firstNotNullOfOrNull "a `categories` entry is not an object"
        val tokens = category["values"] as? JsonArray
            ?: return@firstNotNullOfOrNull "a category's own `values` is missing or is not an array"
        if (tokens.any { it.tokenOrNull() == null }) "a category token is not a string" else null
    }
}

/** One category token's text, or null when the entry is not a string this family may judge. */
private fun JsonElement.tokenOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content
