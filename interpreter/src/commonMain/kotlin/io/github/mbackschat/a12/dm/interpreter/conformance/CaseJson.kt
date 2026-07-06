package io.github.mbackschat.a12.dm.interpreter.conformance

import kotlin.jvm.JvmStatic
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * (De)serialize a [ConformanceCase] to/from its `corpus/cases/<family>/<id>.json` — kernel-free and **multiplatform**
 * (JVM + JS), so both the JVM replay runner and the JS one (WI-6) share it. Tree API only
 * ([Json.parseToJsonElement] + a hand-written walk; proposal fork F1) — no `@Serializable`, no compiler plugin.
 *
 * The model lives in a **standalone** `models/<name>.json` file; the case carries only its corpus-root-relative
 * [ConformanceCase.modelRef]. A runner resolves that ref, reads the file, and feeds its text to the loader. The
 * separate model files are written by [writeModel] (stable pretty-printed DM-JSON, every literal preserved —
 * raw-number fidelity, no `Double` round-trip, so a declared scale survives a shared-model diff).
 */
object CaseJson {

    private val PRETTY = Json { prettyPrint = true }

    /** Parse one case document. Throws [IllegalArgumentException] on malformed JSON or a missing required key. */
    @JvmStatic
    fun parse(text: String): ConformanceCase {
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            throw IllegalArgumentException("not a valid conformance case document", e)
        }
        return ConformanceCase(
            meta = parseMeta(root.obj("meta")),
            modelRef = root["modelRef"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException(
                    "conformance case is missing required 'modelRef' — expected a corpus-root-relative model path " +
                        "like \"models/<name>.json\"",
                ),
            placements = root.arr("placements").map { parsePlacement(it.jsonObject) },
            op = parseOp(root.obj("op")),
            clock = root["clock"]?.jsonPrimitive?.contentOrNull,
            expected = root["expected"]?.jsonArray?.strings(),
            computeExpected = root["computeExpected"]?.jsonArray?.strings(),
            divergences = root["divergences"]?.jsonArray?.map { parseDivergence(it.jsonObject) } ?: emptyList(),
        )
    }

    /** Serialize [case] to pretty-printed JSON (LF, UTF-8 by the writer's contract) in the spec's key order. */
    @JvmStatic
    fun write(case: ConformanceCase): String = PRETTY.encodeToString(JsonElement.serializer(), toJson(case))

    /**
     * The stable, pretty-printed form of a DM-JSON model — what the generator writes to a standalone
     * `models/<name>.json` file. Formatting is deterministic (so re-capture is idempotent and a shared-model diff
     * stays reviewable) and every literal is preserved (raw-number fidelity, no `Double` round-trip, so a declared
     * `minValue`/scale survives). The output is pure DM-JSON with no wrapper envelope — openable in the A12
     * modeling tools and consumable by `dmtool` directly.
     */
    @JvmStatic
    fun writeModel(dmJson: String): String = PRETTY.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(dmJson))

    // ---- parse ----------------------------------------------------------------------------------

    private fun parseMeta(o: JsonObject) = CaseMeta(
        id = o.str("id"),
        kernelVersion = o.str("kernelVersion"),
        source = CaseSource.ofWire(o.str("source")),
        sections = o.arr("sections").strings(),
        tags = o["tags"]?.jsonArray?.strings() ?: emptyList(),
        seed = o["seed"]?.jsonPrimitive?.intOrNull,
        scenario = o["scenario"]?.jsonPrimitive?.contentOrNull,
        notes = o["notes"]?.jsonPrimitive?.contentOrNull,
    )

    private fun parsePlacement(o: JsonObject) = Placement(
        kind = PlacementKind.valueOf(o.str("kind")),
        path = o.str("path"),
        reps = o.arr("reps").ints(),
        value = o["value"]?.jsonPrimitive?.contentOrNull,
    )

    private fun parseOp(o: JsonObject): Op = when (val kind = o.str("kind")) {
        "validateFull" -> Op.ValidateFull
        "compute" -> Op.Compute
        "validatePart" -> Op.ValidatePart(o.arr("relevant").map {
            val r = it.jsonObject
            RelevantPointer(r.str("path"), r.arr("reps").ints())
        })
        else -> throw IllegalArgumentException("unknown op.kind: $kind")
    }

    private fun parseDivergence(o: JsonObject) = Divergence(
        strategy = o.str("strategy"),
        expected = o["expected"]?.jsonArray?.strings(),
        computeExpected = o["computeExpected"]?.jsonArray?.strings(),
    )

    // ---- write ----------------------------------------------------------------------------------

    private fun toJson(case: ConformanceCase): JsonObject = buildJsonObject {
        put("meta", metaJson(case.meta))
        put("modelRef", case.modelRef)
        case.clock?.let { put("clock", it) }
        putJsonArray("placements") { case.placements.forEach { p -> add(placementJson(p)) } }
        put("op", opJson(case.op))
        case.expected?.let { putStrings("expected", it) }
        case.computeExpected?.let { putStrings("computeExpected", it) }
        if (case.divergences.isNotEmpty()) {
            putJsonArray("divergences") { case.divergences.forEach { d -> add(divergenceJson(d)) } }
        }
    }

    private fun metaJson(m: CaseMeta): JsonObject = buildJsonObject {
        put("id", m.id)
        put("kernelVersion", m.kernelVersion)
        put("source", m.source.wire)
        putStrings("sections", m.sections)
        if (m.tags.isNotEmpty()) putStrings("tags", m.tags)
        m.seed?.let { put("seed", it) }
        m.scenario?.let { put("scenario", it) }
        m.notes?.let { put("notes", it) }
    }

    private fun placementJson(p: Placement): JsonObject = buildJsonObject {
        put("kind", p.kind.name)
        put("path", p.path)
        putJsonArray("reps") { p.reps.forEach { add(JsonPrimitive(it)) } }
        p.value?.let { put("value", it) }
    }

    private fun opJson(op: Op): JsonObject = buildJsonObject {
        put("kind", op.kind)
        if (op is Op.ValidatePart) {
            putJsonArray("relevant") {
                op.relevant.forEach { r ->
                    add(buildJsonObject {
                        put("path", r.path)
                        putJsonArray("reps") { r.reps.forEach { add(JsonPrimitive(it)) } }
                    })
                }
            }
        }
    }

    private fun divergenceJson(d: Divergence): JsonObject = buildJsonObject {
        put("strategy", d.strategy)
        d.expected?.let { putStrings("expected", it) }
        d.computeExpected?.let { putStrings("computeExpected", it) }
    }

    // ---- tiny tree accessors (fail loud on a missing required key) -------------------------------

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
    private fun JsonObject.arr(key: String): JsonArray = getValue(key).jsonArray
    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonArray.strings(): List<String> = map { it.jsonPrimitive.content }
    private fun JsonArray.ints(): List<Int> = map { it.jsonPrimitive.int }

    private fun JsonObjectBuilder.putStrings(key: String, values: List<String>) {
        putJsonArray(key) { values.forEach { add(JsonPrimitive(it)) } }
    }
}
