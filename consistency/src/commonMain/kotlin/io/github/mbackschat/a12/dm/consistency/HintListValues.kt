package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The values INSIDE a string type's hint list, against that type's own bounds and each other.
 *
 * [stringTypeParameterFindings] judges the parameters against one another and reports
 * `MVK_NO_VAL_AND_HINT_LISTS` when the list may not be there at all; this family judges the list's entries
 * ([KERNEL-FINDINGS KF421], `rulekit.validate.laws.HintListValueLawsTest`). The two do not pre-empt each
 * other: A12 reports the list-level refusal and the value laws together, measured.
 *
 * **These three codes sat in [MVK-LEDGER](../../../../../../../../../../docs/MVK-LEDGER.md) as unreachable, measured on the wrong carrier.**
 * [KF321](../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf321) put a repeated, empty, spaced and 300-character token to A12 as an ENUM
 * value and each was admitted — correctly, and about a different member. The rows' own prose named the
 * carrier as a hint list, and nothing had asked it.
 *
 * **Only the locales the model DECLARES are judged**, which was predicted the other way and measured: an
 * entry for an undeclared locale is not examined at all, in either law, so a family judging every entry
 * present would refuse two models A12 takes.
 *
 * **`-1` is A12's "no bound"** (`IFieldType.UNDEFINED`), as everywhere else on this type, so a sentinel
 * maximum imposes nothing.
 *
 * **The multiplicities are measured, not chosen.** A length finding is per offending VALUE, and a uniqueness
 * finding is per duplicated value rather than per list — two distinct values each duplicated in one list draw
 * two, which one duplicated value could not have said.
 *
 * **What it leaves alone**, under-reporting: `MVK_VALUE_INVALID`, the fourth law of the same carrier. Its
 * gate is readable — `CheckHintList` refuses a value the type's sibling `pattern` does not match — and it
 * stays silent on a stored hint list under three attacked spellings, so reproducing it would refuse models
 * A12 takes. Also any `hintList` entry whose shape this checker cannot read, published as a [CoverageGap].
 */
internal fun TrackedFactBase.hintListValueFindings(): List<Finding> =
    declarations().filter { it.declaredKind == DeclaredKind.FIELD }.flatMap { field ->
        val body = field.stringTypeBody() ?: return@flatMap emptyList()
        judgedEntries(body).flatMap { values ->
            field.lengthFindings(values, body) + field.duplicateFindings(values)
        }
    }

/**
 * The declarations this family stayed silent about, so silence is never read as agreement.
 *
 * Per DECLARATION, because that is what a consumer needs: which field's hint list was left to A12.
 */
internal fun TrackedFactBase.hintListValueGaps(): List<CoverageGap> =
    declarations().filter { it.declaredKind == DeclaredKind.FIELD }.mapNotNull { field ->
        val unread = field.stringTypeBody()?.unreadableHintEntry() ?: return@mapNotNull null
        CoverageGap(
            field.path,
            "a `hintList` entry of this field was not judged against the type's own bounds, so A12 alone " +
                "judges it: $unread",
        )
    }

/** One value list per hint entry whose locale the model declares — the others A12 never examines. */
private fun TrackedFactBase.judgedEntries(body: JsonObject): List<List<String>> =
    (body["hintList"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        .filter { entry -> entry.string("locale")?.let { localeKeyOrNull(it) } in declaredLocales }
        .map { entry ->
            (entry["values"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
        }

private fun TrackedDeclaration.lengthFindings(values: List<String>, body: JsonObject): List<Finding> {
    val maximum = body.bound("maxLength")
    val minimum = body.bound("minLength")
    return values.flatMap { value ->
        buildList {
            if (maximum != null && value.length > maximum) {
                add(
                    hintFinding(
                        "MVK_VALUE_TOO_LONG",
                        "the hint value `$value` is longer than this field's `maxLength` of $maximum",
                    ),
                )
            }
            if (minimum != null && value.length < minimum) {
                add(
                    hintFinding(
                        "MVK_VALUE_TOO_SHORT",
                        "the hint value `$value` is shorter than this field's `minLength` of $minimum",
                    ),
                )
            }
        }
    }
}

/** One finding per duplicated VALUE, which is the multiplicity A12 raises. */
private fun TrackedDeclaration.duplicateFindings(values: List<String>): List<Finding> =
    values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.map { duplicated ->
        hintFinding(
            "MVK_VALUES_DUPLICATE",
            "this field's hint list for one locale offers `$duplicated` more than once, which A12 refuses; " +
                "the same value in ANOTHER locale's list is admitted",
        )
    }

private fun TrackedDeclaration.hintFinding(code: String, explain: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    code,
    explain,
    path,
)

/** A declared length bound, or null when the member is absent or carries A12's `-1` sentinel. */
private fun JsonObject.bound(member: String): Int? =
    (this[member] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }

/** Why a hint entry was skipped, or null when every entry of every list was judged. */
private fun JsonObject.unreadableHintEntry(): String? {
    val declared = this["hintList"] ?: return null
    val entries = declared as? JsonArray ?: return "its `hintList` is present and is not an array"
    return entries.firstNotNullOfOrNull { entry ->
        val hint = entry as? JsonObject ?: return@firstNotNullOfOrNull "a `hintList` entry is not an object"
        when {
            hint.string("locale") == null -> "a `hintList` entry spells no string `locale`, and A12 judges " +
                "an entry only for a locale the model declares"
            hint["values"] !is JsonArray -> "a `hintList` entry's `values` is missing or is not an array"
            (hint["values"] as JsonArray).any { (it as? JsonPrimitive)?.isString != true } ->
                "a `hintList` value is not a string"
            else -> null
        }
    }
}
