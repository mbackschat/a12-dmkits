package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The report as JSON — the wire shape every non-JVM consumer reads.
 *
 * **It is written by hand rather than derived from the data classes, and that is the point.** Annotating
 * [ConsistencyReport] and its members `@Serializable` would make every field name and every enum constant a
 * published wire contract the moment it is added, silently, by whoever adds it. This function is the one
 * place that contract changes, so changing it is a decision rather than a side effect — which matters most
 * for a report whose whole job is to be read carefully.
 *
 * **Four rules the shape follows, each because a consumer would otherwise draw the wrong conclusion.**
 *
 * `status` is a string, never a boolean, and `outcome` rides beside it as a second string because the two
 * answer different questions: `status` is A12's verdict, where there is no VALID to report, while `outcome`
 * is the checker's own classification and is the only place a PASS appears — `ALL_CHECKS_PASSED`, scoped to
 * what this checker implements. A boolean could carry neither, and one field could not carry both. `checksCompleted` and `gaps` are always present, never omitted when
 * empty, because their absence and their emptiness mean opposite things — no family ran, against no gap
 * remains. `findings` and `diagnostics` stay separate arrays, because a finding is A12's verdict on a
 * declaration and a diagnostic is this checker saying it could not read something; folding them together is
 * exactly the conflation the JVM `ModelLoader.loadAndCheck` makes. And an absent `at` is `null` rather than
 * an empty string, so a model-level finding cannot be mistaken for one attached to the root.
 *
 * The rendering is stable across platforms because it builds the tree explicitly; nothing here depends on
 * declaration order, reflection, or a serializer's defaults.
 */
fun ConsistencyReport.toJson(): String = asJson().toString()

/**
 * The same tree, unrendered, so a report can be NESTED inside another document without re-implementing its
 * shape. [EditReport.toJson] is the one caller; rendering stays in exactly one place either way.
 */
private fun ConsistencyReport.asJson(): JsonObject = buildJsonObject {
    put("modelId", modelId)
    put("status", status.wireName())
    // Published beside the status because it is the half a consumer must not infer: INCONCLUSIVE with
    // `complete` false means coverage ran out, and the field exists so the distinction stays available when
    // `complete` starts carrying information.
    put("complete", status.complete)
    // The CHECKER's own classification, published 2026-09-18 so a non-JVM consumer can read the scoped pass
    // instead of re-deriving it. It had to be re-derived before, and a JSON consumer cannot: deciding
    // ALL_CHECKS_PASSED needs the family DENOMINATOR, which no other member of this shape carries, so
    // `ci-gate.mjs` could only ever compute the three outcomes that do not need it. Publishing the answer
    // also retires a duplicated rule — that gate mirrored `outcome`'s logic in JavaScript with this
    // function's owner named as the durable fix.
    put("outcome", outcome.name)
    put("findings", JsonArray(findings.map { it.asJson() }))
    put("diagnostics", JsonArray(diagnostics.map { it.asJson() }))
    // A JSON array of what is a SET in the API, so the order must come from somewhere stable. The enum's own
    // declaration order is the only candidate that does not couple these bytes to something unrelated: the
    // set used to arrive in the order a literal in `checkPrepared` happened to list its members, so a
    // reshuffle of the dispatch moved the wire — measured when the registry replaced that literal and the
    // parity corpus diffed on element 4 alone.
    put("checksCompleted", buildJsonArray {
        CheckFamily.entries.filter { it in checksCompleted }.forEach { add(JsonPrimitive(it.name)) }
    })
    put("gaps", JsonArray(gaps.map { it.asJson() }))
}

/**
 * An edit's attribution as JSON — the wire shape a candidate-checking consumer reads.
 *
 * **`status` describes the EDIT, not the edited model**, which is the one thing a reader must not conflate:
 * an edit can be INCONCLUSIVE while `edited.status` is INVALID, because the model was already broken and
 * this verdict is about what the edit added. Both nested reports are published in full for that reason —
 * `baseline` is the disclosure that makes an INCONCLUSIVE edit on a broken model readable, and summarizing
 * it would invent a second shape for facts the report already states.
 *
 * `introduced` and `resolved` are always present, never omitted when empty, on the same rule the whole-model
 * shape follows: absent and empty would otherwise mean the same thing for a consumer deciding whether an
 * edit is safe to save.
 */
fun EditReport.toJson(): String = buildJsonObject {
    put("status", status.wireName())
    put("complete", status.complete)
    put("introduced", JsonArray(introduced.map { it.asJson() }))
    put("resolved", JsonArray(resolved.map { it.asJson() }))
    put("gaps", JsonArray(gaps.map { it.asJson() }))
    put("baseline", baseline.asJson())
    put("edited", edited.asJson())
}.toString()

/**
 * What a snapshot operation reused, as JSON — L's whole point, so a JS consumer must be able to see it.
 *
 * **Published because reuse is a CLAIM about work performed, and an unobservable claim is not one.** The
 * typed API states it with measured members ([Reuse]); a consumer across the wire had no way to tell a
 * snapshot that reused a preparation from one that silently rebuilt it, which is exactly the property L
 * exists to deliver. The two family counts sum to the cells a check scheduled, so a reader can compute the
 * ratio without knowing the family list.
 */
internal fun Reuse.asJson(): JsonObject = buildJsonObject {
    put("sourcesReused", sourcesReused)
    put("sourcesAdmitted", sourcesAdmitted)
    put("preparationReused", preparationReused)
    put("baselineReportReused", baselineReportReused)
    put("familiesReused", families.reused)
    put("familiesRecomputed", families.recomputed)
}

private fun Finding.asJson(): JsonObject = buildJsonObject {
    put("code", code)
    put("category", category.name)
    put("severity", severity.name)
    put("explain", explain)
    put("at", at)
    // Absent far more often than present, and `null` rather than an omitted member so "this finding has no
    // characters to point at" and "this consumer is reading an older report" cannot look the same.
    val located = span
    if (located == null) {
        put("span", JsonNull)
    } else {
        put("span", buildJsonObject {
            put("member", located.member)
            put("start", located.start)
            put("end", located.end)
        })
    }
    // Derived rather than stored, and published because it is the one question a consumer asks of a finding
    // that the fields alone do not answer: an AUTHORING_POLICY finding at ERROR does NOT make a model
    // invalid, so severity cannot be read as the verdict on its own.
    put("justifiesRejection", justifiesRejection)
    // A12's own guidance for the CODE, projected from its Java owners. Two members rather than one flat
    // string because they answer different questions — what to change here, and what the code classifies
    // wherever it is raised — and `null` rather than an omitted member so "no owner explains this code" and
    // "this consumer is reading an older report" cannot look the same.
    val guidance = corrective
    if (guidance == null) {
        put("corrective", JsonNull)
    } else {
        put("corrective", buildJsonObject {
            put("fix", guidance.fix)
            put("meaning", guidance.meaning)
        })
    }
}

private fun Diagnostic.asJson(): JsonObject = buildJsonObject {
    put("code", code.name)
    put("modelId", modelId)
    put("explain", explain)
    put("at", at)
    put("diagnosticLabel", diagnosticLabel)
}

private fun CoverageGap.asJson(): JsonObject = buildJsonObject {
    put("at", at)
    put("reason", reason)
}

/**
 * The status as one word.
 *
 * `ConsistencyStatus` is a sealed interface rather than an enum — it has no `name` — because `Valid` arrives
 * with the coverage evidence that earns it and `complete` becomes derived at the same time. Spelling the
 * wire names here rather than reflecting over the class keeps that future change from silently renaming
 * anything a consumer switches on.
 */
private fun ConsistencyStatus.wireName(): String = when (this) {
    ConsistencyStatus.Invalid -> "INVALID"
    ConsistencyStatus.Inconclusive -> "INCONCLUSIVE"
}

/** `put` for a nullable string, emitting JSON `null` rather than dropping the key. */
private fun kotlinx.serialization.json.JsonObjectBuilder.put(key: String, value: String?) {
    put(key, value?.let { JsonPrimitive(it) } ?: JsonNull)
}
