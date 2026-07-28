package io.github.mbackschat.a12.dm.interpreter.conformance

import io.github.mbackschat.a12.dm.interpreter.A12_JSON
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
 * (JVM + JS), so both replay runners share it. Tree API only
 * ([Json.parseToJsonElement] + a hand-written walk; proposal fork F1) — no `@Serializable`, no compiler plugin.
 *
 * The model lives in a **standalone** `models/<name>.json` file; the case carries only its corpus-root-relative
 * [ConformanceCase.modelRef]. A runner resolves that ref, reads the file, and feeds its text to the loader. The
 * separate model files are written by [writeModel] (stable pretty-printed DM-JSON, every literal preserved —
 * raw-number fidelity, no `Double` round-trip, so a declared scale survives a shared-model diff).
 */
object CaseJson {

    private val PRETTY = Json { prettyPrint = true }

    /**
     * Parse one case document. Throws [IllegalArgumentException] on malformed JSON or a missing required key.
     *
     * The **case envelope is our own shape**, so it parses on the default `Json` — deliberately not `A12_JSON`.
     * A12's comment leniency is a property of A12's formats; inheriting it here would widen our corpus format
     * with no counterpart to cite. The DM-JSON a case *carries* is an A12 shape and does go through `A12_JSON`.
     */
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
     * The stable, pretty-printed form of a DM-JSON model, **normalized to be openable in the A12 modeling tools
     * (SME)** and identified by [modelId] — what the generator writes to a standalone `models/<name>.json` file.
     * Two normalizations make the base test fixture (authored for the lenient kernel *runtime*, which the JVM
     * differential uses) a valid *authoring* model:
     *
     * 1. **Identity** — both `header.id` and `content.modelInfo.name` are set to [modelId] (the file-stem domain
     *    name), so each model is a **uniquely-named workspace item**: files derived from one base (all sharing the
     *    base's id) would otherwise collide in the SME. [modelId] must match the A12 model-name grammar
     *    (letter/underscore start, then letters/digits/`_`/`-`/`.`; the file-stem form `[a-z0-9-]+` does).
     * 2. **Date formats** — every date-ish field's `format` is set to its canonical A12 storage format
     *    ([CANONICAL_DATE_FORMAT]). The SME's model deserializer accepts **only** those three canonical formats in
     *    `format` (a localized display format like German `dd.MM.yyyy` belongs in `notInDCustomFormat`); the kernel
     *    runtime is lenient and parses whatever `format` declares, so a fixture using `dd.MM.yyyy` directly in
     *    `format` evaluates fine but fails to *open* in the SME (`unsupported date format`). See KERNEL-FINDINGS §6.
     *
     * Formatting is deterministic (so re-capture is idempotent and a shared-model diff stays reviewable) and every
     * literal is preserved (raw-number fidelity, no `Double` round-trip, so a declared `minValue`/scale survives).
     * The output is pure DM-JSON with no wrapper envelope — openable in the A12 modeling tools and consumable by
     * `dmtool` directly.
     */
    @JvmStatic
    fun writeModel(dmJson: String, modelId: String): String {
        val root = A12_JSON.parseToJsonElement(dmJson).jsonObject
        val withIdentity = buildJsonObject {
            for ((key, value) in root) when (key) {
                "header" -> put("header", setMember(value.jsonObject, "id", modelId))
                "content" -> put("content", setContentModelName(value.jsonObject, modelId))
                else -> put(key, value)
            }
        }
        return PRETTY.encodeToString(JsonElement.serializer(), canonicalizeDateFormats(withIdentity))
    }

    /** The model identity of a DM-JSON model — its `header.id` (the SME workspace-item name); throws if absent. */
    @JvmStatic
    fun modelIdOf(dmJson: String): String =
        A12_JSON.parseToJsonElement(dmJson).jsonObject.obj("header")["id"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("DM-JSON model has no header.id")

    /**
     * The date-ish field `format`s in [dmJson] that the A12 modeling tools (SME) would reject — any value not in
     * [SME_DATE_FORMATS] (reported as `<Type>:<format>`). Empty means the model opens in the SME; the corpus guard
     * asserts this, and [writeModel] guarantees it. KERNEL-FINDINGS §6.
     */
    @JvmStatic
    fun unsupportedDateFormats(dmJson: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(el: JsonElement) {
            when (el) {
                is JsonObject -> {
                    val typeName = el["type"]?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
                    if (typeName != null && typeName in CANONICAL_DATE_FORMAT) {
                        val fmt = (el[typeName] as? JsonObject)?.get("format")?.jsonPrimitive?.contentOrNull
                        if (fmt != null && fmt !in SME_DATE_FORMATS) out += "$typeName:$fmt"
                    }
                    el.values.forEach(::walk)
                }
                is JsonArray -> el.forEach(::walk)
                else -> {}
            }
        }
        walk(A12_JSON.parseToJsonElement(dmJson))
        return out
    }

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

    // ---- model normalization for SME-openability (writeModel) -----------------------------------

    /**
     * The canonical A12 storage `format` per date-ish field type — the ONLY values the A12 modeling tools (SME)
     * accept in a field's `format` (a localized display format goes in `notInDCustomFormat`). Source: the
     * simple-model-editor date (de)serialization switch. KERNEL-FINDINGS §6.
     */
    val CANONICAL_DATE_FORMAT: Map<String, String> = mapOf(
        "DateType" to "yyyy-MM-dd",
        "DateTimeType" to "yyyy-MM-dd'T'HH:mm:ss",
        "TimeType" to "HH:mm:ss",
        "DateRangeType" to "yyyy-MM-dd",
    )

    /** The distinct SME-supported `format` values (the codomain of [CANONICAL_DATE_FORMAT]). */
    val SME_DATE_FORMATS: Set<String> = CANONICAL_DATE_FORMAT.values.toSet()

    /** [obj] with [key] set to the string [value], preserving key order (appended if absent). */
    private fun setMember(obj: JsonObject, key: String, value: String): JsonObject = buildJsonObject {
        var replaced = false
        for ((k, v) in obj) if (k == key) { put(k, value); replaced = true } else put(k, v)
        if (!replaced) put(key, value)
    }

    /** [content] with `modelInfo.name` set to [name] (so the model's SME name matches its `header.id`); a no-op if it has no `modelInfo`. */
    private fun setContentModelName(content: JsonObject, name: String): JsonObject = buildJsonObject {
        for ((k, v) in content) if (k == "modelInfo" && v is JsonObject) put(k, setMember(v, "name", name)) else put(k, v)
    }

    /**
     * [el] with every date-ish field type's `format` set to its canonical value ([CANONICAL_DATE_FORMAT]) and a
     * `DateRangeType` separator reset to the A12 standard `/` — so the model opens in the SME (KERNEL-FINDINGS §6).
     * A field type object carries `{ "type": "DateType", "DateType": { "format": … } }`; the walk descends the whole
     * tree. Already-canonical formats are untouched (no-op).
     */
    private fun canonicalizeDateFormats(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> {
            val typeName = el["type"]?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
            val canonical = typeName?.let { CANONICAL_DATE_FORMAT[it] }
            buildJsonObject {
                for ((k, v) in el) {
                    if (canonical != null && k == typeName && v is JsonObject) {
                        put(k, canonicalizeDateSubtype(v, typeName, canonical))
                    } else {
                        put(k, canonicalizeDateFormats(v))
                    }
                }
            }
        }
        is JsonArray -> JsonArray(el.map { canonicalizeDateFormats(it) })
        else -> el
    }

    /** A date field-type sub-object with `format` set to [canonical] (and a `DateRangeType`'s `rangeSeparator` to `/`). */
    private fun canonicalizeDateSubtype(sub: JsonObject, typeName: String, canonical: String): JsonObject {
        val withFormat = setMember(sub, "format", canonical)
        return if (typeName == "DateRangeType" && "rangeSeparator" in withFormat) setMember(withFormat, "rangeSeparator", "/")
        else withFormat
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
