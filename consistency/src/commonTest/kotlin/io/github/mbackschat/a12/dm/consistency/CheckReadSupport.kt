package io.github.mbackschat.a12.dm.consistency

import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * [check], refusing a source it did not admit.
 *
 * A source the checker cannot admit reports no finding at all, so a row expecting none passes on a fixture that
 * never loaded — a stray quote in a JSON fixture made a mutant survive exactly that way (2026-09-26). An empty
 * [ConsistencyReport.checksCompleted] is the one signal that nothing was checked. A test about admission itself
 * calls [check] directly, and `CheckerFixtureReadGuardTest` holds the list of the files that may.
 */
internal fun checkRead(source: ModelSource): ConsistencyReport =
    check(source).also { report ->
        assertTrue(report.checksCompleted.isNotEmpty(), "the fixture was not admitted: ${report.diagnostics}")
    }

/**
 * A harvested model's captured bytes with the two frame members A12 needs before its consistency check can run, a
 * version and model info, added where absent. The bytes were captured from fixtures written before the checker read
 * the frame (KERNEL-FINDINGS KF650), and they stay sha-locked as evidence; A12 builds none of them as captured, which
 * the adapter's `CheckerNearMissSweepTest` holds, so a replay of their condition-level assertions completes them.
 */
internal fun checkableSource(model: String): ModelSource {
    val root = Json.parseToJsonElement(model).jsonObject
    fun JsonObject.with(name: String, value: JsonElement) = if (name in this) this else JsonObject(this + (name to value))
    val header = root.getValue("header").jsonObject.with("modelVersion", JsonPrimitive("28.4.0"))
    val content = root.getValue("content").jsonObject.with("modelInfo", buildJsonObject { put("name", "model") })
    return ModelSource(JsonObject(root + mapOf("header" to header, "content" to content)).toString())
}
