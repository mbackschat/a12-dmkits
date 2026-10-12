package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.DecimalObjectIdentity
import io.github.mbackschat.a12.dm.interpreter.eval.DocumentPlacementRecord
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.eval.parseRangeToIso
import io.github.mbackschat.a12.dm.interpreter.eval.renderDateToFormat
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/**
 * A12's canonical **Document JSON** — the one document shape this project reads and writes at every official
 * boundary (CLAUDE.md "NEVER invent a shape A12 already defines"). The format is the Kernel's, recorded in
 * KERNEL-FINDINGS "The canonical Document JSON and `DocumentPointer` string" from a 30.8.1 source read of
 * `DocumentV2JsonReader` / `DocumentV2JsonWriter` / `DocumentJsonAndXmlDeserializationInfoImpl`:
 *
 * - a **nested object tree mirroring the model** — a non-repeating group is a nested object, a **repeating
 *   group is a JSON array** whose index *is* the repetition, an unfilled row in that array is `{}`, a
 *   present-but-empty group is `{}`, and an **omitted entity is a missing key**;
 * - a field is its value: **native JSON for number and boolean**, a **model-format string** for the date
 *   family, the **S-value** for an enumeration — all read through one stringification
 *   (`value.isNull() ? null : value.asText()`) and then parsed model-driven, because only the model
 *   disambiguates a string from a date;
 * - a **JSON null keeps the field present with no value** (`FieldInstanceV2.ofValue(null)`), which is
 *   [Document.emptyField] here — not an omission;
 * - a value that fails conversion is **not dropped**: the Kernel stores the raw string verbatim and writes
 *   strings back untouched (`[A12K-3638]`), so malformed input is representable in the official shape;
 * - a root-level `id` is a serializer special case, not a model field.
 *
 * **Read is exactly as permissive as A12's reader:** an object at a repeating group is one row at repetition 1,
 * and an array at a *non-repeating* group is rows 1..n — `repetitionsFromJson` never consults repeatability, so
 * an over-repetition (which formal validation exists to report) stays readable. **Write is canonical:** array
 * versus object comes from *declared* repeatability, never from what happens to be populated. The one document
 * state A12's writer has no shape for — a non-repeatable group row above 1 — is refused ([DocumentEncodeError])
 * rather than silently dropped.
 *
 * Read conversion drops unknown or wrong-shaped entities and compacts surviving object rows, as A12's
 * serializer does (SPEC-2026-10-10-07; `A12JsonIngressDiffTest`). Syntax, root and scalar-id faults remain fatal.
 */
internal object A12DocumentJson {

    /** The serializer's root-level document identity — a string-like key that is deliberately not a model field. */
    const val DOCUMENT_ID_KEY: String = "id"

    private const val TRUE_LITERAL = "true"
    private const val FALSE_LITERAL = "false"

    /** The `{}` an unfilled row of a repeating group is written as. */
    private val EMPTY_ROW = JsonObject(emptyMap())

    /** JSON's own number grammar — the gate for WRITING a stored number back as a native literal. */
    private val JSON_NUMBER = Regex("(-?)(0|[1-9][0-9]*)(?:\\.([0-9]+))?(?:[eE]([-+]?[0-9]+))?")

    /**
     * A12's own CONVERTER grammar — `new BigDecimal(String)`, which `NumberUtil.createBigDecimalIfPossible`
     * calls unchanged. Deliberately **broader** than [JSON_NUMBER]: a leading `+`, a bare `.5`, a trailing `5.`,
     * and leading zeros all parse for it, and A12 therefore normalizes them away. The digit-presence check below
     * rejects what this pattern alone would let through (`""`, `"."`, `"+"`, `"e5"`).
     */
    private val A12_CONVERTER_NUMBER = Regex("([-+]?)([0-9]*)(?:\\.([0-9]*))?(?:[eE]([-+]?[0-9]+))?")

    /**
     * The widest point shift we will materialize. A12 would expand `1e999999999` into a billion characters
     * (`toPlainString` has no ceiling); at a public ingress that is a twelve-character denial of service, so a
     * shift beyond the hard per-field ceiling is refused and the value stays verbatim — A12's own pass-through
     * shape, which can never produce invalid JSON. The significand needs no bound of its own: it cannot be
     * longer than the literal, which the caller has already charged against `maxRawCodeUnitsPerField`.
     */
    private const val MAX_POINT_SHIFT = DocumentDecodeLimits.HARD_MAX_RAW_CODE_UNITS_PER_FIELD.toLong()

    /**
     * [literal] in A12's **plain** number form, or null when it is not a JSON number at all — the WRITE gate.
     *
     * A12 never writes a number in scientific notation: its generator runs with
     * `JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN`, so the emitted text is `BigDecimal.toPlainString()` —
     * the point shifted out, the **scale preserved** (`250.00` stays `250.00`, `1e5` becomes `100000`,
     * `1.2e-3` becomes `0.0012`). Negative zero collapses because `BigDecimal` has none. Matching this is what
     * makes our encode byte-identical to the Kernel's for every legal numeric input, not merely equal in value.
     *
     * Purely lexical on purpose: a decimal *type* would impose a rounding/precision model the transport must
     * not have. Cross-checked against `java.math.BigDecimal.toPlainString` by `A12NumberPlainFormTest`.
     */
    internal fun plainNumberOrNull(literal: String): String? {
        val match = JSON_NUMBER.matchEntire(literal) ?: return null
        val (sign, integerPart, fractionPart, exponentPart) = match.destructured
        return plainForm(sign, integerPart, fractionPart, exponentPart)
    }

    /**
     * [literal] in A12's plain number form when **A12's converter** would parse it, else null — the READ gate.
     * Same output form as [plainNumberOrNull] (one shared lexical conversion, one oracle) over the broader
     * [A12_CONVERTER_NUMBER] grammar, so the values A12 silently normalizes at ingestion — `+5` → `5`,
     * `007` → `7`, `1e5` → `100000`, `-0.0` → `0.0` — normalize here too.
     */
    internal fun canonicalNumberOrNull(literal: String): String? {
        val match = A12_CONVERTER_NUMBER.matchEntire(literal) ?: return null
        val (sign, integerPart, fractionPart, exponentPart) = match.destructured
        if (integerPart.isEmpty() && fractionPart.isEmpty()) return null
        return plainForm(sign, integerPart, fractionPart, exponentPart)
    }

    /**
     * The text A12's reader hands its converter for an UNQUOTED number token, or null when [literal] is not a JSON
     * number. Its mapper parses a token with a point or an exponent into a `BigDecimal` that keeps its signed scale,
     * and `asText()` renders that as `BigDecimal.toString()`: `250.00` stays, `1e3` becomes `1E+3`, `0.0000001`
     * becomes `1E-7`. An integer token renders as its integer, so `-0` becomes `0`. Every kind reads this text; a
     * NUMBER field re-renders it from the value anyway, but a STRING field stores it (KERNEL-FINDINGS KF1,
     * `A12JsonIngressKernelTest`). Purely lexical like [plainForm]; cross-checked against `BigDecimal.toString` by
     * `A12NumberPlainFormTest`.
     */
    internal fun numberNodeTextOrNull(literal: String): String? {
        val match = JSON_NUMBER.matchEntire(literal) ?: return null
        val (sign, integerPart, fractionPart, exponentPart) = match.destructured
        val digits = (integerPart + fractionPart).trimStart('0').ifEmpty { "0" }
        val isZero = digits == "0"
        val negative = sign == "-" && !isZero
        if (fractionPart.isEmpty() && exponentPart.isEmpty() && !literal.contains('.')) {
            return if (negative) sign + digits else digits
        }
        val exponent = if (exponentPart.isEmpty()) 0 else exponentPart.toIntOrNull() ?: return null
        val scale = fractionPart.length.toLong() - exponent
        val adjusted = digits.length - 1 - scale
        // No point-shift ceiling here: the plain branch is reached only at a scale the literal's own length bounds,
        // and the scientific one is as long as the significand, so `1e999999999` stays `1E+999999999`.
        val text = if (scale >= 0 && adjusted >= -6) {
            plainForm("", integerPart, fractionPart, exponentPart) ?: return null
        } else {
            val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            mantissa + "E" + (if (adjusted >= 0) "+" else "") + adjusted
        }
        return if (negative) sign + text else text
    }

    /** `BigDecimal.toPlainString()` reproduced lexically from an already-validated significand + exponent. */
    private fun plainForm(sign: String, integerPart: String, fractionPart: String, exponentPart: String): String? {
        val exponent = if (exponentPart.isEmpty()) 0 else exponentPart.toIntOrNull() ?: return null
        // A leading zero is not part of the UNSCALED value, so `BigDecimal` never renders one: `007` is `7`
        // and `00.50` is `0.50`. The scale is untouched by the strip — it comes from the fraction and exponent
        // alone. (Caught by the `toPlainString` oracle, not by derivation.)
        val digits = (integerPart + fractionPart).trimStart('0').ifEmpty { "0" }
        val scale = fractionPart.length.toLong() - exponent
        if (scale > MAX_POINT_SHIFT || -scale > MAX_POINT_SHIFT) return null
        val isZero = digits.all { it == '0' }
        val plain = when {
            // `BigDecimal.toPlainString` short-circuits zero at a negative scale: `0e5` is "0", not "000000".
            scale <= 0 && isZero -> "0"
            scale <= 0 -> digits + "0".repeat((-scale).toInt())
            scale < digits.length -> {
                val point = digits.length - scale.toInt()
                digits.substring(0, point) + "." + digits.substring(point)
            }
            else -> "0." + "0".repeat((scale - digits.length).toInt()) + digits
        }
        // BigDecimal has no negative zero: `-0.0` reads back `0.0`. A leading `+` is never part of its output.
        return if (sign == "-" && !isZero) sign + plain else plain
    }

    /**
     * The **RUNTIME** rendering of an already-plain stored number — the text A12's `IData` hands every
     * evaluation channel, as against [plainNumberOrNull]'s **serializer** text. A12 renders one stored decimal
     * twice, and the two differ in BOTH directions: `FormatDefinitionZahl.convertFromBasicType` computes
     * `bd.setScale(max(bd.stripTrailingZeros().scale(), minFractionalDigits)).toPlainString()`, so a fractional
     * trailing zero beyond [minFractionalDigits] is **stripped** (`250.00` → `250`) and a value with fewer
     * fractional digits than [minFractionalDigits] is **padded** (`250` → `250.00`). IF231; the measurement is
     * KERNEL-FINDINGS "The canonical Document JSON …".
     *
     * Purely lexical, for the same reason [plainNumberOrNull] is: routing a transport value through a decimal
     * type would impose the evaluation model's rounding on a rendering that must not have one. Nothing is ever
     * rounded here — the destination scale is by construction at least the value's own significant scale, so
     * only trailing zeros are added or dropped. Cross-checked against `java.math.BigDecimal` itself by
     * `A12RuntimeNumberFormTest`.
     *
     * [plain] must already be in plain form (a [plainNumberOrNull] / [canonicalNumberOrNull] output): no
     * exponent, no leading `+`, no leading zeros beyond a lone `0`.
     */
    internal fun runtimeNumberForm(plain: String, minFractionalDigits: Int): String {
        val point = plain.indexOf('.')
        val fractionPart = if (point < 0) "" else plain.substring(point + 1)
        val significantFraction = fractionPart.trimEnd('0')
        // Once no significant fractional digit survives, `stripTrailingZeros().scale()` is <= 0 (`250.00` strips
        // to `2.5E+2`, scale -1; `0.00` to `0`, scale 0) — and `max(<= 0, minFractionalDigits)` is then just
        // minFractionalDigits, which is why the two branches need no signed scale of their own.
        val scale = if (significantFraction.isEmpty()) minFractionalDigits
        else maxOf(significantFraction.length, minFractionalDigits)
        if (scale == fractionPart.length) return plain
        val integerPart = if (point < 0) plain else plain.substring(0, point)
        return if (scale == 0) integerPart else integerPart + "." + significantFraction.padEnd(scale, '0')
    }

    /**
     * One field cell's stored text in A12's **two renderings** — [stored] is what the serializer writes back,
     * [evaluation] what the runtime hands every evaluation channel. `null` in both slots is the empty cell.
     *
     * They differ for exactly one thing: a **converted NUMBER** whose serializer and runtime renderings disagree
     * (IF231). That Number also retains [numberIdentity], because source-relative computation classification
     * compares its converted coefficient and scale rather than either text. Every other kind — and A12's own
     * pass-through of a value its converter rejects, which stays a `String` that `ValidationData.getValue`
     * returns unchanged — carries one text in both slots.
     */
    private data class CellText(
        val evaluation: String?,
        val stored: String?,
        val numberIdentity: DecimalObjectIdentity? = null,
    ) {
        constructor(one: String?) : this(one, one, null)

        companion object {
            val EMPTY = CellText(null)
        }
    }

    /**
     * The stored text of one field cell as A12's own ingestion leaves it, in both renderings ([CellText]).
     *
     * A12's document is **value-normalizing**: `DocumentV2JsonReader` stringifies the JSON node (`asText()`),
     * `FieldValueConverter` converts that text to `String | Boolean | BigDecimal | Instant | InstantRange`, and
     * every later rendering — the serializer's `toJsonFieldNode`, the runtime's `IData` — is produced from the
     * stored VALUE, never from the authored text. Our cell stores text, so the ingestion normalization has to
     * happen here instead. Per kind, measured by `adapter … A12JsonIngressKernelTest`:
     *
     * - **NUMBER** — [canonicalNumberOrNull] for the serializer text, then [runtimeNumberForm] for the runtime
     *   text; the one case A12 keeps as text is a `leadingZerosAllowed` field's leading-zero value, where
     *   `createBigDecimalIfPossible` returns the original String and both renderings are that String.
     * - **BOOLEAN** — `"true".equalsIgnoreCase` / `"false".equalsIgnoreCase` → the canonical token; **CONFIRM**
     *   accepts only `true` (it has no false, so even a native JSON `false` is a pass-through).
     * - **the DATE family** — nothing to do: `SimpleDateFormat` is non-lenient, so every non-canonical spelling
     *   *fails* conversion and is kept verbatim, and every spelling that parses already IS the model format —
     *   except a DATE_RANGE read under a format shared from another declaration ([runtimeRangeText]).
     * - **STRING / Enumeration** — never normalized; A12's converter succeeds with the text itself.
     * - **`""` on any TYPED kind** is `ConversionResult.success(null)` — the empty cell, written back as `null`.
     *   A STRING keeps its `""`.
     *
     * A value the converter cannot parse stays verbatim ([A12K-3638]) and is written back as a JSON string.
     */
    private fun ModelDef.cellText(path: String, raw: String): CellText = when (kinds[path]) {
        Kind.NUMBER -> when {
            raw.isEmpty() -> CellText.EMPTY
            else -> {
                val converted = canonicalNumberOrNull(raw)
                    ?.takeUnless { path in leadingZeroNumbers && hasLeadingZeros(raw) }
                if (converted == null) CellText(raw)   // A12's pass-through: a String, read back unchanged
                else CellText(
                    runtimeNumberForm(converted, minFractionalDigitsOf(path)),
                    converted,
                    DecimalObjectIdentity.parse(raw),
                )
            }
        }
        Kind.BOOLEAN -> when {
            raw.isEmpty() -> CellText.EMPTY
            raw.equals(TRUE_LITERAL, ignoreCase = true) -> CellText(TRUE_LITERAL)
            raw.equals(FALSE_LITERAL, ignoreCase = true) -> CellText(FALSE_LITERAL)
            else -> CellText(raw)
        }
        Kind.CONFIRM -> when {
            raw.isEmpty() -> CellText.EMPTY
            raw.equals(TRUE_LITERAL, ignoreCase = true) -> CellText(TRUE_LITERAL)
            else -> CellText(raw)
        }
        Kind.DATE, Kind.DATE_TIME, Kind.TIME -> CellText(raw.ifEmpty { null })
        Kind.DATE_RANGE -> if (raw.isEmpty()) CellText.EMPTY else CellText(runtimeRangeText(path, raw), raw)
        Kind.STRING, null -> CellText(raw)
    }

    /**
     * The text a DATE_RANGE value is evaluated as. A12 converts the authored text under the field's DECLARED format
     * into two dates — a missing month or day defaulting to 1 at both ends — and every later reading renders those
     * dates under the RUNTIME format the field may share with others ([ModelDef.rangeConfig]), so a shared yearless
     * format drops a declared year and a shared month format a declared day. Text the declared format cannot convert
     * stays as authored and meets the runtime format's own checks (KERNEL-FINDINGS KF540). The stored text is
     * unchanged.
     */
    private fun ModelDef.runtimeRangeText(path: String, raw: String): String {
        val declared = declaredRangeConfig[path] ?: return raw
        val runtime = rangeConfig.getValue(path)
        if (declared == runtime) return raw
        val (start, end) = parseRangeToIso(raw, declared.first, declared.second, baseYear ?: 2000,
            rangeYearInterpretationOf(path), completeEndToLastDay = false) ?: return raw
        val renderedStart = renderDateToFormat(start, runtime.first) ?: return raw
        val renderedEnd = renderDateToFormat(end, runtime.first) ?: return raw
        return renderedStart + runtime.second + renderedEnd
    }

    /**
     * A12's `NumberUtil.containsLeadingZeros` on an already-parseable number: the first digit after an optional
     * sign is `0` and something other than the decimal separator follows it. Note this is lexical, so `0e5`
     * counts as leading-zero-bearing — the Kernel's own behaviour, not a simplification.
     */
    private fun hasLeadingZeros(literal: String): Boolean {
        val afterSign = if (literal[0] == '+' || literal[0] == '-') 1 else 0
        return literal[afterSign] == '0' && literal.length > afterSign + 1 && literal[afterSign + 1] != '.'
    }

    /** One decoded document plus the root `id` the shape carries beside it. */
    internal data class Read(val document: Document, val documentId: String?)

    /**
     * Convert the complete Document JSON text against [model] before building the document, so a fatal
     * input or resource ceiling leaves no partial state. Notification-only nodes are discarded.
     */
    fun read(model: ModelDef, json: String, limits: DocumentDecodeLimits): Read {
        requireWithin(
            actual = json.length.toLong(),
            maximum = limits.maxDocumentCodeUnits.toLong(),
            resource = DocumentInputResource.DOCUMENT_CODE_UNITS,
        ) { null }
        val session = ReadSession(model, limits)
        session.walk(parseRoot(json), path = "", coordinates = emptyList())
        return session.build()
    }

    /**
     * Write [document] as canonical A12 Document JSON: direct fields sorted by name, then direct subgroups
     * sorted by name (the Kernel writer's key order), a declared-repeatable group as an array, everything else
     * as a nested object.
     */
    fun write(model: ModelDef, document: Document, documentId: String?): String {
        val root = WriteNode()
        document.placementsSnapshot().forEach { root.place(it) }
        if (documentId != null) root.fields[DOCUMENT_ID_KEY] = documentId
        return A12_JSON.encodeToString(
            JsonObject.serializer(),
            root.toJson(model, path = "", coordinates = emptyList()),
        )
    }

    private fun parseRoot(json: String): JsonObject {
        val element = try {
            A12_JSON.parseToJsonElement(json)
        } catch (failure: Exception) {
            throw DocumentInputError.from(listOf(MalformedJsonDiagnostic(failure.message ?: "unparseable JSON")))
        }
        return element as? JsonObject
            ?: throw DocumentInputError.from(
                listOf(MalformedJsonDiagnostic("the document root must be a JSON object but was ${element.nodeType()}")),
            )
    }

    // ---- read -----------------------------------------------------------------------------------------

    private class ReadSession(private val model: ModelDef, private val limits: DocumentDecodeLimits) {
        /** Encounter-ordered pending placements — applied in JSON document order, mirroring A12's read order. */
        private val pending = mutableListOf<Pending>()
        private var groupRows = 0L
        private var fieldCells = 0L
        private var totalRawCodeUnits = 0L
        private var documentId: String? = null

        fun walk(node: JsonObject, path: String, coordinates: List<Int>) {
            for ((name, child) in node) {
                val childPath = "$path/$name"
                val kind = ModelAddressValidation.entityKind(model, childPath)
                if (kind == null && path.isEmpty() && name == DOCUMENT_ID_KEY) {
                    readDocumentId(child)
                    continue
                }
                val ownCoordinates = coordinates + 1
                requireWithin(
                    actual = childPath.length.toLong(),
                    maximum = limits.maxPathCodeUnits.toLong(),
                    resource = DocumentInputResource.PATH_CODE_UNITS,
                ) { A12PointerFormat.format(childPath, ownCoordinates) }
                requireWithin(
                    actual = ownCoordinates.size.toLong(),
                    maximum = limits.maxModelNestingDepth.toLong(),
                    resource = DocumentInputResource.MODEL_NESTING_DEPTH,
                ) { A12PointerFormat.format(childPath, ownCoordinates) }
                when (kind) {
                    ModelEntityKind.FIELD -> readCell(childPath, ownCoordinates, child)
                    ModelEntityKind.GROUP -> readGroup(childPath, coordinates, child)
                    null -> Unit
                }
            }
        }

        fun build(): Read {
            val document = model.newDocument()
            for (placement in pending) {
                val coordinates = placement.coordinates.toIntArray()
                val text = placement.text
                when {
                    placement.isGroup -> document.group(placement.path, *coordinates)
                    text.stored == null -> document.emptyField(placement.path, *coordinates)
                    else -> document.renderedField(
                        path = placement.path,
                        evaluationValue = text.evaluation!!,
                        storedValue = text.stored,
                        numberIdentity = text.numberIdentity,
                        reps = coordinates,
                    )
                }
            }
            return Read(document, documentId)
        }

        // Every pointer here is built LAZILY: a canonical pointer string per cell would allocate once per
        // field of every document just to hand a ceiling check a locator it almost never uses.
        private fun readCell(path: String, coordinates: List<Int>, node: JsonElement) {
            val pointer = { A12PointerFormat.format(path, coordinates) }
            when {
                node === JsonNull -> {
                    countCell(pointer)
                    pending += Pending(path, coordinates, CellText.EMPTY, isGroup = false)
                }
                node is JsonPrimitive -> {
                    countCell(pointer)
                    // The ceilings measure the AUTHORED text — that is the input a caller controls, and it is
                    // charged before any normalization can shorten or lengthen it.
                    val raw = node.content
                    requireWithin(
                        actual = raw.length.toLong(),
                        maximum = limits.maxRawCodeUnitsPerField.toLong(),
                        resource = DocumentInputResource.RAW_CODE_UNITS_PER_FIELD,
                        pointer = pointer,
                    )
                    totalRawCodeUnits += raw.length
                    requireWithin(
                        actual = totalRawCodeUnits,
                        maximum = limits.maxTotalRawCodeUnits.toLong(),
                        resource = DocumentInputResource.TOTAL_RAW_CODE_UNITS,
                        pointer = pointer,
                    )
                    // A12 reads a value as its node's text, so an unquoted number arrives in its BigDecimal or
                    // integer rendering rather than as typed; a quoted one is kept as typed.
                    val text = if (node.isString) raw else numberNodeTextOrNull(raw) ?: raw
                    pending += Pending(path, coordinates, model.cellText(path, text), isGroup = false)
                }
                else -> Unit
            }
        }

        private fun readGroup(path: String, parentCoordinates: List<Int>, node: JsonElement) {
            when (node) {
                is JsonObject -> readRow(path, parentCoordinates + 1, node)
                is JsonArray -> {
                    var repetition = 0
                    for (element in node) if (element is JsonObject) {
                        readRow(path, parentCoordinates + ++repetition, element)
                    }
                }
                else -> Unit
            }
        }

        private fun readRow(path: String, coordinates: List<Int>, node: JsonObject) {
            groupRows++
            requireWithin(
                actual = groupRows,
                maximum = limits.maxGroupPlacements.toLong(),
                resource = DocumentInputResource.GROUP_PLACEMENTS,
            ) { A12PointerFormat.format(path, coordinates) }
            pending += Pending(path, coordinates, CellText.EMPTY, isGroup = true)
            walk(node, path, coordinates)
        }

        /**
         * A12's scalar-id branch accepts text and, without Java assertions, numbers. Containers follow group
         * conversion and are discarded here; Boolean/null scalar ids are fatal (`A12JsonIngressDiffTest`). A numeric id
         * is its node's text like any unquoted number, so `1e3` reads `1E+3` (`DocumentJsonAndXmlDeserializationInfoImpl`
         * takes `value.asText()` on the id branch as on a field's).
         */
        private fun readDocumentId(node: JsonElement) {
            if (node is JsonObject || node is JsonArray) return
            if (node is JsonPrimitive && node !== JsonNull && node.isString) {
                documentId = node.content
                return
            }
            val numberText = if (node is JsonPrimitive && node !== JsonNull) numberNodeTextOrNull(node.content) else null
            if (numberText != null) {
                documentId = numberText
                return
            }
            throw DocumentInputError.from(listOf(InvalidDocumentIdDiagnostic(node.nodeType())))
        }

        private fun countCell(pointer: () -> String?) {
            fieldCells++
            requireWithin(
                actual = fieldCells,
                maximum = limits.maxFieldPlacements.toLong(),
                resource = DocumentInputResource.FIELD_PLACEMENTS,
                pointer = pointer,
            )
        }
    }

    private data class Pending(
        val path: String,
        val coordinates: List<Int>,
        val text: CellText,
        val isGroup: Boolean,
    )

    /** [pointer] is evaluated only when the ceiling is actually exceeded. */
    private inline fun requireWithin(
        actual: Long,
        maximum: Long,
        resource: DocumentInputResource,
        pointer: () -> String?,
    ) {
        if (actual > maximum) {
            throw DocumentInputError.from(
                listOf(ResourceLimitExceededDiagnostic(resource, maximum, actual, pointer())),
            )
        }
    }

    // ---- write ----------------------------------------------------------------------------------------

    /** One group instance being written: its direct field cells (a `null` value is a present-empty cell). */
    private class WriteNode {
        val fields = LinkedHashMap<String, String?>()
        val subgroups = LinkedHashMap<String, MutableMap<Int, WriteNode>>()

        fun place(record: DocumentPlacementRecord) {
            val parts = record.path.trim('/').split('/')
            check(parts.size == record.reps.size) {
                "placement '${record.path}' carries ${record.reps.size} coordinates for ${parts.size} path parts"
            }
            val groupDepth = if (record.isGroup) parts.size else parts.size - 1
            var node = this
            for (level in 0 until groupDepth) {
                node = node.child(parts[level], record.reps[level])
            }
            if (record.isGroup) return
            check(record.reps.last() == 1) {
                "field cell '${record.path}' carries repetition ${record.reps.last()}; a field has no repetition axis"
            }
            node.fields[parts.last()] = record.raw
        }

        fun toJson(model: ModelDef, path: String, coordinates: List<Int>): JsonObject {
            val out = LinkedHashMap<String, JsonElement>()
            for (name in fields.keys.sorted()) {
                out[name] = fieldToJson(model, "$path/$name", fields[name])
            }
            for (name in subgroups.keys.sorted()) {
                val childPath = "$path/$name"
                val rows = subgroups.getValue(name)
                out[name] = if (childPath in model.repeatable) {
                    JsonArray(
                        (1..rows.keys.max()).map { repetition ->
                            rows[repetition]?.toJson(model, childPath, coordinates + repetition) ?: EMPTY_ROW
                        },
                    )
                } else {
                    rows.keys.filter { it != 1 }.minOrNull()?.let { excess ->
                        throw DocumentEncodeError.nonRepeatableRow(
                            modelPath = childPath,
                            pointer = A12PointerFormat.format(childPath, coordinates + excess),
                        )
                    }
                    rows.getValue(1).toJson(model, childPath, coordinates + 1)
                }
            }
            return JsonObject(out)
        }

        private fun child(name: String, repetition: Int): WriteNode =
            subgroups.getOrPut(name) { LinkedHashMap() }.getOrPut(repetition) { WriteNode() }
    }

    /**
     * One field cell as A12 writes it: **native JSON** where the stored text *is* the canonical literal for the
     * declared kind, otherwise the text as a JSON string — the Kernel's own pass-through for a value it could
     * not convert. A cell with no stored text is `null`, which reads back present-and-valueless.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun fieldToJson(model: ModelDef, path: String, raw: String?): JsonElement {
        if (raw == null) return JsonNull
        return when (model.kinds[path]) {
            // A12 emits the PLAIN form, never scientific — see [plainNumberOrNull].
            Kind.NUMBER -> plainNumberOrNull(raw)?.let { JsonUnquotedLiteral(it) } ?: JsonPrimitive(raw)
            Kind.BOOLEAN ->
                if (raw == TRUE_LITERAL || raw == FALSE_LITERAL) JsonUnquotedLiteral(raw) else JsonPrimitive(raw)
            // A CONFIRM has no false — `parseConfirm` converts only `true`, so even a native JSON `false` is a
            // value A12 could not convert and writes back as the string `"false"`.
            Kind.CONFIRM -> if (raw == TRUE_LITERAL) JsonUnquotedLiteral(raw) else JsonPrimitive(raw)
            // The date family and enumerations are model-format strings; `null` is the root `id` pseudo-field.
            Kind.STRING, Kind.DATE, Kind.DATE_TIME, Kind.TIME, Kind.DATE_RANGE, null -> JsonPrimitive(raw)
        }
    }
}

private fun JsonElement.nodeType(): DocumentJsonNodeType = when {
    this === JsonNull -> DocumentJsonNodeType.NULL
    this is JsonObject -> DocumentJsonNodeType.OBJECT
    this is JsonArray -> DocumentJsonNodeType.ARRAY
    else -> DocumentJsonNodeType.VALUE
}

private data class MalformedJsonDiagnostic(private val detail: String) : DocumentInputDiagnostic {
    override val reasonCode = DocumentInputReasonCode.MALFORMED_JSON
    override val message = "the Document JSON could not be read: $detail"
}

private data class InvalidDocumentIdDiagnostic(
    override val nodeType: DocumentJsonNodeType,
) : DocumentInputDiagnostic {
    override val reasonCode = DocumentInputReasonCode.INVALID_DOCUMENT_ID
    override val message =
        "the root '${A12DocumentJson.DOCUMENT_ID_KEY}' carries ${nodeType.name.lowercase()}; it must be a string"
}

private data class ResourceLimitExceededDiagnostic(
    override val resource: DocumentInputResource,
    override val maximum: Long,
    override val actual: Long,
    override val pointer: String?,
) : DocumentInputDiagnostic {
    override val reasonCode = DocumentInputReasonCode.RESOURCE_LIMIT_EXCEEDED
    override val message =
        "Document decoding exceeded ${resource.name.lowercase()} limit $maximum (actual $actual)"
}
