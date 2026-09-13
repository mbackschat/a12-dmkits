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
 * spelling in an element NAME, an error MESSAGE and an enum CATEGORY token is admitted under the identical
 * restriction. A family that read the restriction as "the model's text" would refuse three models A12 takes.
 *
 * **The restriction is the EXPANDED model's**, not the declaring file's: a provider that declares no
 * restriction of its own has its enum values judged by the host's once mounted, measured on a two-model
 * fixture. So the entry model's list is applied to every declaration, mounted ones included.
 *
 * **One finding per DISTINCT offending character, per value.** A repeated character is folded — `aa` draws
 * one — and the same pair in a second value is reported again, so distinctness is scoped to the value. Both
 * rows are measured, and neither is inferable from the other.
 *
 * **What it leaves alone**, all of it under-reporting: a restriction carrying an entry this checker cannot
 * read as a singleton or an inclusive `x-y` range — an atomic grapheme is a legal entry whose membership
 * test is a trie walk over code-point SEQUENCES, so a per-character reading of it would refuse values A12
 * admits — and any value carrying a surrogate code unit, which is a supplementary-plane character nobody has
 * put to this gate.
 */
fun PreparedModel.supportedCharacterFindings(): List<Finding> {
    val admitted = admittedRanges(supportedCharacters) ?: return emptyList()
    return elements.filter { it.declaredKind == DeclaredKind.FIELD }.flatMap { field ->
        val declared = field.body?.get("fieldType") as? JsonObject ?: return@flatMap emptyList()
        if ((declared["type"] as? JsonPrimitive)?.content != "EnumerationType") {
            return@flatMap emptyList()
        }
        val body = declared["EnumerationType"] as? JsonObject ?: return@flatMap emptyList()
        val values = body["values"] as? JsonArray ?: return@flatMap emptyList()
        values.flatMap { entry -> field.offendersIn(entry, admitted) }
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

/** One declared value's offending characters, each named once however often it occurs in that value. */
private fun DeclaredElement.offendersIn(entry: JsonElement, admitted: List<CharRange>): List<Finding> {
    val value = ((entry as? JsonObject)?.get("value") as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: return emptyList()
    if (value.any { it.isSurrogate() }) {
        return emptyList()
    }
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
