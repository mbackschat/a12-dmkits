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
 * `status` is a string, never a boolean: a checker that cannot emit VALID by construction has three states to
 * report and a boolean can carry two. `checksCompleted` and `gaps` are always present, never omitted when
 * empty, because their absence and their emptiness mean opposite things — no family ran, against no gap
 * remains. `findings` and `diagnostics` stay separate arrays, because a finding is A12's verdict on a
 * declaration and a diagnostic is this checker saying it could not read something; folding them together is
 * exactly the conflation the JVM `ModelLoader.loadAndCheck` makes. And an absent `at` is `null` rather than
 * an empty string, so a model-level finding cannot be mistaken for one attached to the root.
 *
 * The rendering is stable across platforms because it builds the tree explicitly; nothing here depends on
 * declaration order, reflection, or a serializer's defaults.
 */
fun ConsistencyReport.toJson(): String = buildJsonObject {
    put("modelId", modelId)
    put("status", status.wireName())
    // Published beside the status because it is the half a consumer must not infer: INCONCLUSIVE with
    // `complete` false means coverage ran out, and the field exists so the distinction stays available when
    // `complete` starts carrying information.
    put("complete", status.complete)
    put("findings", JsonArray(findings.map { it.asJson() }))
    put("diagnostics", JsonArray(diagnostics.map { it.asJson() }))
    put("checksCompleted", buildJsonArray { checksCompleted.forEach { add(JsonPrimitive(it.name)) } })
    put("gaps", JsonArray(gaps.map { it.asJson() }))
}.toString()

private fun Finding.asJson(): JsonObject = buildJsonObject {
    put("code", code)
    put("category", category.name)
    put("severity", severity.name)
    put("explain", explain)
    put("at", at)
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
