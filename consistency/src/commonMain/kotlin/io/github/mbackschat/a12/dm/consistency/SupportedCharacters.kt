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
 * **A supplementary-plane character is judged per UTF-16 unit, and its high unit is never admitted.** A12
 * tests the value unit by unit, so `o` followed by one such character draws three findings under `A-Z`: the
 * letter and both surrogate halves (KERNEL-FINDINGS KF551). A range spanning the whole surrogate block, such as
 * `U+0020-U+FFFD`, admits the low unit and still refuses the high one, so the character draws one finding
 * (KF560, `adapter.laws.CheckerEnumDeclarationStreamAgreementTest`). No buildable restriction separates the two
 * halves otherwise: an entry holding a surrogate unit leaves A12 unable to build the model.
 *
 * **That entry, and one A12's charset reader refuses, stop A12's whole check**, so this family never sees them: the
 * preparation refuses the model ([unbuildableCharacterRefusals]).
 *
 * **What it leaves alone**, all of it under-reporting: a restriction carrying an entry this checker cannot
 * read as a singleton or an inclusive `x-y` range — a COMBINED grapheme (more than one code point) is a
 * legal entry that A12 tests by walking code-point SEQUENCES rather than characters, so a per-character
 * reading of it would refuse values A12 admits. That silence is published as a [CoverageGap].
 */
internal fun TrackedFactBase.supportedCharacterFindings(): List<Finding> {
    val admitted = admittedRanges(supportedCharacters) ?: return emptyList()
    return defaultVariantFields().flatMap { field ->
        val values = field.ownEnumerationBody()?.get("values") as? JsonArray ?: return@flatMap emptyList()
        // Distinct SPELLINGS, because A12 reports on the triple (field, value, character): a value declared
        // twice yields the same notification twice and A12 keeps one, so pairing per entry would over-count.
        values.mapNotNull { it.declaredValue() }.distinct()
            .flatMap { value -> field.offendersIn(value, admitted) }
    }
}

/**
 * What this family stayed silent about, so silence is never read as agreement.
 *
 * The unreadable restriction is a MODEL-level gap and the only one of its kind in the checker: it silences
 * the family over every declaration at once, so naming declarations would under-state it. The per-value
 * skip is declaration-level for the ordinary reason — a consumer needs to know which enum was left to A12.
 */
internal fun TrackedFactBase.supportedCharacterGaps(): List<CoverageGap> {
    if (supportedCharacters.isEmpty()) {
        return emptyList()
    }
    if (admittedRanges(supportedCharacters) == null) {
        return listOf(
            CoverageGap(
                at = null,
                reason = "this model restricts its characters with an entry this checker reads as neither a " +
                    "singleton nor an inclusive `x-y` range, so NO enum value was judged against it, and whether " +
                    "A12 can build the model at all is left to A12 — it admits a combined grapheme, whose " +
                    "membership test is a walk over code-point sequences rather than a per-character one, refuses " +
                    "two or three characters that are not one grapheme and two combined graphemes that overlap, and " +
                    "only a grapheme segmenter decides either; a number written in exponent form is an entry whose " +
                    "text this checker does not know",
            ),
        )
    }
    return defaultVariantFields().mapNotNull { field ->
        val values = field.ownEnumerationBody()?.get("values") as? JsonArray ?: return@mapNotNull null
        val unread = values.firstNotNullOfOrNull { it.unreadableValue() } ?: return@mapNotNull null
        CoverageGap(
            field.path,
            "an enum value here was not judged against this model's `supportedCharacters`: $unread",
        )
    }
}

/** Why a value entry was skipped, or null when it was judged. */
private fun JsonElement.unreadableValue(): String? =
    if (declaredValue() == null) "the entry spells no string `value`" else null

/**
 * The characters this restriction admits, as inclusive ranges — null when it restricts nothing, or when it
 * carries an entry whose meaning is not measured here.
 *
 * The two nulls are deliberately one answer: both mean "this family has nothing it may say about this
 * model". Distinguishing them would only matter if one of them justified a finding, and neither does.
 */
private fun admittedRanges(declared: List<String?>): List<CharRange>? {
    if (declared.isEmpty()) {
        return null
    }
    return declared.map { entry ->
        when {
            entry == null -> return null
            entry.length == 3 && entry[1] == '-' && entry[0] <= entry[2] -> entry[0]..entry[2]
            entry.length == 1 -> entry[0]..entry[0]
            else -> return null
        }
    }
}

/**
 * Why A12 builds no model from the entry's restriction, or nothing when it reads it: an entry holding a surrogate unit
 * (KF560), and an entry its charset reader refuses ([refusedEntry]). Either stops A12's whole consistency check, so
 * nothing beside it is reported, measured beside a time zone and a version A12 refuses
 * (`adapter.laws.CheckerFrameValueAgreementTest`).
 */
internal fun List<String?>.unbuildableCharacterRefusals(): List<String> = listOfNotNull(
    when {
        any { entry -> entry.orEmpty().any(Char::isSurrogate) } ->
            "In the entry model, `content.modelConfig.supportedCharacters` holds an entry with a surrogate unit, " +
                "and A12 cannot build a model from that, so no consistency verdict about it exists; a " +
                "supplementary-plane character cannot be admitted by any entry"
        else -> refusedEntry(this)?.let { entry ->
            "In the entry model, `content.modelConfig.supportedCharacters` holds `$entry`, which is neither one " +
                "character, a range `x-y` that runs upwards, nor one combined grapheme of at most three units, and " +
                "A12 cannot build a model from that, so no consistency verdict about it exists"
        }
    },
)

/**
 * The first entry A12's charset reader refuses, or null when this checker can name none.
 *
 * `CharsetUtil` reads an entry of three units with `-` in the middle as a range, one unit as a character, and any
 * other non-empty entry as a combined grapheme, admitted only when Java's `\X` finds fewer clusters than it has
 * units; its validation then refuses a grapheme longer than three units. So the empty entry, a range running
 * downwards, anything longer than three units, and two or three units that each [standsAlone] are refused, and every
 * one of those refusals leaves the model unbuilt (KERNEL-FINDINGS KF650,
 * `adapter.laws.CheckerFrameValueAgreementTest`). An entry of other characters is left to A12, since only a
 * grapheme segmenter decides it, and so is an overlap between two combined graphemes.
 */
private fun refusedEntry(declared: List<String?>): String? = declared.filterNotNull().firstOrNull { entry ->
    when {
        entry.isEmpty() || entry.length > 3 -> true
        entry.length == 3 && entry[1] == '-' -> entry[0] > entry[2]
        else -> entry.length > 1 && entry.all { it.standsAlone() }
    }
}

/**
 * Whether this character is a grapheme cluster of its own beside any other such character: printable ASCII and
 * U+00A0 to U+017F, none of which extends, joins or prepends under Unicode's segmentation.
 * `SupportedCharacterEntryJvmTest` holds every pair of them to Java's `\X`.
 */
internal fun Char.standsAlone(): Boolean = this in ' '..'~' || this in '\u00A0'..'\u017F'

/** The spelling this entry declares, or null when it spells no string `value`. */
private fun JsonElement.declaredValue(): String? =
    ((this as? JsonObject)?.get("value") as? JsonPrimitive)?.takeIf { it.isString }?.content

/** One declared value's offending UTF-16 units, each named once however often it occurs in that value. */
private fun TrackedDeclaration.offendersIn(value: String, admitted: List<CharRange>): List<Finding> {
    return value.toCharArray().distinct()
        .filterNot { character -> !character.isHighSurrogate() && admitted.any { character in it } }
        .map { character ->
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                "MVK_ENUM_VALUE_WITH_INVALID_CHARACTER",
                "the enum value `$value` carries ${character.described()}, which this model's " +
                    "`supportedCharacters` does not admit — A12 bakes that restriction into the validation " +
                    "code it generates, and re-runs the generator when it checks the model",
                path,
            )
        }
}

/** A unit as a finding names it: a surrogate half has no glyph of its own, so it is named by its code. */
private fun Char.described(): String =
    if (isSurrogate()) "the surrogate half U+${code.toString(16).asciiUppercase().padStart(4, '0')}" else "`$this`"
