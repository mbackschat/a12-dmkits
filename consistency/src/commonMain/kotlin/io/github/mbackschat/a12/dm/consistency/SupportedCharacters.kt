package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A declared ENUM VALUE against the model's own character restriction.
 *
 * `content.modelConfig.supportedCharacters` restricts the characters a document's field VALUES may carry,
 * and the kernel bakes it into the generated validation code — which `checkConsistency` re-runs, so the
 * generator's own error class reaches an ordinary authoring gate and a declared enum value spelled outside
 * the set refuses the model ([KERNEL-FINDINGS KF317],
 * `rulekit.validate.laws.CodegenErrorClassReachesConsistencyLawsTest` and
 * `SupportedCharactersDeclarationGateLawsTest`).
 *
 * **The scope is enum values and nothing else, measured rather than transferred.** The identical refused
 * spelling in an element NAME, an error MESSAGE, an enum CATEGORY token and a string field's HINT LIST is
 * admitted under the identical restriction. A family that read the restriction as "the model's text" would
 * refuse four models A12 takes. The hint list is the carrier that reads as in-scope from A12's source —
 * `NoMetaModelChecks.checkEnumValues` runs the same tester over it, but only for an EXTENSIBLE enum, and
 * nothing in kernel `30.8.1` sets that flag, so the arm is dead for a DM-JSON document model. Measured, not
 * read: the source alone would have widened this family onto a carrier A12 never judges.
 *
 * **The restriction is the EXPANDED model's**, not the declaring file's: a provider that declares no
 * restriction of its own has its enum values judged by the host's once mounted, measured on a two-model
 * fixture. So the entry model's list is applied to every declaration, mounted ones included.
 *
 * **One finding per DISTINCT (value, character) pair.** A12 reports on the triple (field, value, character)
 * and folds the repeats, so `aa` draws one, the same pair in a SECOND value is reported again, and the same
 * value declared TWICE adds nothing. All three rows are measured, and none is inferable from the others —
 * the middle one alone would leave "distinct" ambiguous between the character's scope and the value's.
 *
 * **What it leaves alone**, all of it under-reporting: a restriction carrying an entry this checker cannot
 * read as a singleton or an inclusive `x-y` range — a COMBINED grapheme (more than one code point) is a
 * legal entry that A12 tests by walking code-point SEQUENCES rather than characters, so a per-character
 * reading of it would refuse values A12 admits — and any value carrying a surrogate code unit, which is a
 * supplementary-plane character nobody has put to this gate. Each silence is published as a [CoverageGap].
 */
fun PreparedModel.supportedCharacterFindings(): List<Finding> {
    val admitted = admittedRanges(supportedCharacters) ?: return emptyList()
    return elements.filter { it.declaredKind == DeclaredKind.FIELD }.flatMap { field ->
        val values = field.enumerationBody()?.get("values") as? JsonArray ?: return@flatMap emptyList()
        // Distinct SPELLINGS, because A12 reports on the triple (field, value, character): a value declared
        // twice yields the same notification twice and A12 keeps one, so pairing per entry would over-count.
        values.mapNotNull { it.judgeableValue() }.distinct()
            .flatMap { value -> field.offendersIn(value, admitted) }
    }
}

/**
 * What this family stayed silent about, so silence is never read as agreement.
 *
 * The unreadable restriction is a MODEL-level gap and the only one of its kind in the checker: it silences
 * the family over every declaration at once, so naming declarations would under-state it. The per-value
 * skips are declaration-level for the ordinary reason — a consumer needs to know which enum was left to A12.
 */
fun PreparedModel.supportedCharacterGaps(): List<CoverageGap> {
    if (supportedCharacters.isEmpty()) {
        return emptyList()
    }
    if (admittedRanges(supportedCharacters) == null) {
        return listOf(
            CoverageGap(
                at = null,
                reason = "this model restricts its characters with an entry this checker reads as neither a " +
                    "singleton nor an inclusive `x-y` range, so NO enum value was judged against it — A12 " +
                    "also admits a combined grapheme, whose membership test is a walk over code-point " +
                    "sequences rather than a per-character one",
            ),
        )
    }
    return elements.filter { it.declaredKind == DeclaredKind.FIELD }.mapNotNull { field ->
        val values = field.enumerationBody()?.get("values") as? JsonArray ?: return@mapNotNull null
        val unread = values.firstNotNullOfOrNull { it.unreadableValue() } ?: return@mapNotNull null
        CoverageGap(
            field.path,
            "an enum value here was not judged against this model's `supportedCharacters`: $unread",
        )
    }
}

/** Why a value entry was skipped, or null when it was judged. */
private fun JsonElement.unreadableValue(): String? {
    val value = declaredValue() ?: return "the entry spells no string `value`"
    return if (value.any { it.isSurrogate() }) {
        "`$value` carries a supplementary-plane character, which nobody has put to this gate"
    } else {
        null
    }
}

/**
 * The characters this restriction admits, as inclusive ranges — null when it restricts nothing, or when it
 * carries an entry whose meaning is not measured here.
 *
 * The two nulls are deliberately one answer: both mean "this family has nothing it may say about this
 * model". Distinguishing them would only matter if one of them justified a finding, and neither does.
 */
private fun admittedRanges(declared: List<String>): List<CharRange>? {
    if (declared.isEmpty()) {
        return null
    }
    return declared.map { entry ->
        when {
            entry.length == 3 && entry[1] == '-' && entry[0] <= entry[2] -> entry[0]..entry[2]
            entry.length == 1 -> entry[0]..entry[0]
            else -> return null
        }
    }
}

/** The spelling this entry declares, or null when it spells no string `value`. */
private fun JsonElement.declaredValue(): String? =
    ((this as? JsonObject)?.get("value") as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The spelling this entry declares, or null when the entry is one this family may not judge. */
private fun JsonElement.judgeableValue(): String? =
    declaredValue()?.takeUnless { spelling -> spelling.any { it.isSurrogate() } }

/** One declared value's offending characters, each named once however often it occurs in that value. */
private fun DeclaredElement.offendersIn(value: String, admitted: List<CharRange>): List<Finding> {
    return value.toCharArray().distinct()
        .filterNot { character -> admitted.any { character in it } }
        .map { character ->
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                "MVK_ENUM_VALUE_WITH_INVALID_CHARACTER",
                "the enum value `$value` carries `$character`, which this model's " +
                    "`supportedCharacters` does not admit — A12 bakes that restriction into the validation " +
                    "code it generates, and re-runs the generator when it checks the model",
                path,
            )
        }
}
