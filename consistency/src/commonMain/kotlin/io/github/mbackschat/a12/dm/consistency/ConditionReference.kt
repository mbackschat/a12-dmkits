package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
import io.github.mbackschat.a12.dm.consistency.syntax.firstReferenceSpansIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * An entity named inside condition or operation text.
 *
 * @param declaredAt the rule or computation that carries the text.
 * @param member the text member it was written in, so a report can name which of a computation's several
 *     texts is at fault rather than only the computation.
 * @param written the reference exactly as authored, which is what a reader has to find and edit.
 * @param span the characters of [member] this reference occupies — its FIRST occurrence when the text names
 *     it more than once, which is the one A12 would report about: it stops at the first defect in a text.
 */
data class ConditionReference(
    val declaredAt: String,
    val member: String,
    val written: String,
    val target: Reference,
    val span: TextSpan,
)

/**
 * Every entity a rule's condition or a computation's operations name, bound to what it resolves to.
 *
 * The texts are the model's own: a rule's `errorCondition`, and a computation's `commonPrecondition`, each
 * alternative's `precondition` and its `operation`. They are read as text because that is how A12 stores
 * them — unlike the error and computed targets, which are structured members ([declaredReferences]).
 *
 * References resolve from the declaring GROUP, not the declaring element, which is the opposite of those
 * structured members; [resolveEntity] carries the measurement. Keywords are decided by the model's own
 * [PreparedModel.conditionLanguage], so a field whose name spells an operator of the *other* language is
 * still a reference here.
 */
fun PreparedModel.conditionReferences(): List<ConditionReference> = conditionReferenceList

/**
 * The walk itself, run ONCE per model behind [PreparedModel.conditionReferenceList].
 *
 * Memoized for the reason its sibling [computeDeclaredReferences] already is, stated there in full: the walk
 * is a pure function of the model, so it is computed once here rather than by asking every caller to
 * remember to hoist it. That sibling's un-hoisted form made the whole check cubic in declarations and took
 * 95 % of a flight recorder's samples; this one was never cubic — every one of its callers hoists, checked
 * across all of them — so what is removed here is repetition, not a complexity class, and no timing claim
 * rides on it.
 */
internal fun PreparedModel.computeConditionReferences(): List<ConditionReference> =
    elements.flatMap { element ->
        conditionReferencesIn(element.path, element.kind, element.body, element.origin, conditionLanguage) {
            group, written, origin ->
            resolveEntity(group, written, origin)
        }
    }

/**
 * ONE declaration's condition references, behind the read barrier.
 *
 * Charged per declaration: this declaration's body for the DSL texts, plus whatever the language read and the
 * resolution charge on the way through — the same decomposition [TrackedDeclaration.declaredReference] makes,
 * and for the same reason. The whole-model list below it is the union.
 *
 * The reading itself is [conditionReferencesIn], shared with the untracked arm rather than restated: the
 * first-occurrence rule is load-bearing and a second copy of it could drift silently.
 */
internal fun TrackedFactBase.conditionReferencesOf(
    declaration: TrackedDeclaration,
): List<ConditionReference> = conditionReferencesIn(
    declaration.path,
    declaration.kind,
    declaration.body,
    declaration.origin,
    conditionLanguage,
) { group, written, origin -> resolveEntity(group, written, origin) }

/** Every declaration's condition references, assembled from the per-declaration arm. */
internal fun TrackedFactBase.conditionReferences(): List<ConditionReference> =
    declarations().flatMap { conditionReferencesOf(it) }

/**
 * The reading both arms share: which names one declaration's condition texts spell, and what each resolves to.
 *
 * Parameterized on the declaration's four readable facts and on the resolver, so the barrier can charge each
 * of them while the reading stays in one place.
 */
private inline fun conditionReferencesIn(
    path: String,
    kind: String,
    body: JsonObject?,
    origin: DeclarationOrigin,
    language: ConditionLanguage,
    resolve: (String, String, DeclarationOrigin) -> Reference,
): List<ConditionReference> {
    if (body == null) {
        return emptyList()
    }
    val group = path.substringBeforeLast('/', missingDelimiterValue = "")
    return conditionTexts(kind, body).flatMap { (member, text) ->
        firstReferenceSpansIn(text, language).map { occurrence ->
            ConditionReference(
                path,
                member,
                occurrence.written,
                resolve(group, occurrence.written, origin),
                TextSpan(member, occurrence.start, occurrence.end),
            )
        }
    }
}

/**
 * The DSL-bearing members of one declaration that are PRESENT, paired with the name a report should use.
 *
 * A computation carries up to three kinds at once and every one of them is checked, so naming only the
 * computation would leave an author looking through all of them. Alternatives are numbered by position
 * because they have no names of their own.
 *
 * Derived from [draftableMembers] rather than enumerating the vocabulary a second time: "which members could
 * this declaration carry" and "which does it carry" are one list and one filter, and two independent
 * enumerations of the member names are what the reader and the writer would eventually disagree about.
 */
internal fun conditionTexts(kind: String, body: JsonObject): List<Pair<String, String>> =
    draftableMembers(kind, body).mapNotNull { member -> conditionText(body, member)?.let { member to it } }

/**
 * Every DSL member this declaration COULD carry, present or not — the vocabulary, in report order.
 *
 * An absent member is draftable: an author writing a computation's first `commonPrecondition` is editing a
 * member the declaration does not yet have, and refusing that would make the one case an editor starts from
 * unanswerable. An absent ALTERNATIVE is not, because adding one is a structural edit to the declaration
 * rather than text being typed into it, so the indices come from what the model declares.
 */
internal fun draftableMembers(kind: String, body: JsonObject): List<String> = when (kind) {
    "Rule" -> listOf("errorCondition")
    "Computation" -> buildList {
        add("commonPrecondition")
        (body["computationAlternatives"] as? JsonArray).orEmpty().forEachIndexed { index, alternative ->
            if (alternative is JsonObject) {
                add("computationAlternatives[$index].precondition")
                add("computationAlternatives[$index].operation")
            }
        }
    }
    else -> emptyList()
}

/** Read one member of [draftableMembers], or null when the declaration does not carry it. */
private fun conditionText(body: JsonObject, member: String): String? {
    val (_, key) = alternativeMemberOf(member) ?: return body.text(member)
    return alternativeOf(body, member)?.text(key)
}

/**
 * Authored alternative position, with zero for declaration-level members. A family's semantic walk can
 * relocate a common-precondition finding (the self-read walk does so at its first judged alternative, KF181).
 */
internal fun walkPositionOf(member: String): Int =
    alternativeMemberOf(member)?.let { (index, _) -> alternativeWalkPosition(index) } ?: 0

/** The [walkPositionOf] every member of the alternative at [index] shares. */
internal fun alternativeWalkPosition(index: Int): Int = index + 1

/** The computation alternative an alternative's member of [draftableMembers] belongs to, or null for any other. */
internal fun alternativeOf(body: JsonObject, member: String): JsonObject? {
    val (index, _) = alternativeMemberOf(member) ?: return null
    val alternatives = body["computationAlternatives"] as? JsonArray ?: return null
    return alternatives.getOrNull(index) as? JsonObject
}

/**
 * Replace one member of [draftableMembers] in [body], or null when [member] is not one.
 *
 * The inverse of the naming in [draftableMembers], and deliberately beside it: the names are BUILT there, so
 * a reader that parsed them anywhere else would be the copy that stopped matching.
 */
internal fun replaceConditionText(body: JsonObject, member: String, text: String): JsonObject? {
    val (index, key) = alternativeMemberOf(member)
        ?: return JsonObject(body + (member to JsonPrimitive(text)))
    val alternatives = body["computationAlternatives"] as? JsonArray ?: return null
    if (index !in alternatives.indices || alternatives[index] !is JsonObject) {
        return null
    }
    val updated = alternatives.mapIndexed { position, alternative ->
        if (position != index) {
            alternative
        } else {
            JsonObject((alternative as JsonObject) + (key to JsonPrimitive(text)))
        }
    }
    return JsonObject(body + ("computationAlternatives" to JsonArray(updated)))
}

/**
 * Take an alternative's member name apart, mirroring where [draftableMembers] puts it together.
 *
 * **Deliberately not a `Regex`, and not because a regex reads worse.** A top-level `private val` holding one
 * is initialised by a per-file init function that Kotlin/JS inserts at the entry of the functions that need
 * it — and reading it from a PRIVATE top-level function reached from another file left it `undefined` on the
 * JS target while the JVM target was perfectly green (measured 2026-09-16: 359 of 615 `jsNodeTest` rows
 * failed with `Cannot read properties of undefined`, against 0 on `jvmTest`). Parsing a fixed-shape name by
 * hand holds no file-level state at all, so there is no initialisation order to get wrong.
 */
private fun alternativeMemberOf(member: String): Pair<Int, String>? {
    val prefix = "computationAlternatives["
    if (!member.startsWith(prefix)) {
        return null
    }
    val close = member.indexOf(']', prefix.length)
    if (close < 0) {
        return null
    }
    val index = member.substring(prefix.length, close).toIntOrNull() ?: return null
    val key = member.substring(close + 1).removePrefix(".")
    return if (key.isEmpty()) null else index to key
}

/**
 * Whether A12 reads more text after this member of [conditionTexts] — true for every computation member but
 * the operation of a lone alternative.
 *
 * A12 does not parse a computation's members one by one. It joins them into ONE condition
 * (`ComputationAlternativesJoiner`): `FieldFilled(<target>) And (<common>\n) And (<precondition>\n) And
 * [<target>] != <operation>` for one alternative, and with two or more each alternative wrapped as
 * `(<precondition>\n) And [<target>] != <operation>\n)` and the whole list closed by a final `\n)`. So a
 * member that runs out of text is followed by a `)` A12 added, and the parser meets that token instead of the
 * end of its input: the incompleteness class becomes the token class ([KERNEL-FINDINGS KF471],
 * `rulekit.validate.laws.ComputationGeneratedTextLawsTest`). Only a lone alternative's operation ends the text.
 * A rule's `errorCondition` is parsed as written.
 */
internal fun followedInGeneratedText(kind: String, body: JsonObject, member: String): Boolean {
    if (kind != "Computation") {
        return false
    }
    val alone = (body["computationAlternatives"] as? JsonArray)?.size == 1
    return !(alone && member.endsWith("operation"))
}

/**
 * Which entry rule A12 parses a member of [conditionTexts] with.
 *
 * It sits beside that enumeration because it is the same knowledge — which member holds which kind of DSL
 * text — and a second copy would be the member the syntax families disagreed about.
 */
internal fun syntaxEntryOf(member: String): SyntaxEntry =
    if (member.endsWith("operation")) SyntaxEntry.OPERATION else SyntaxEntry.CONDITION

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
