package io.github.mbackschat.a12.dm.interpreter

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one `Json` every parse of an **A12-defined shape** goes through — DM-JSON model sources and canonical
 * Document JSON alike. It exists so the parse *configuration* is a single decision instead of a per-call-site
 * accident: A12's own ingress is configured, not default, and matching its shape while parsing it under
 * different rules still refuses files A12 reads (CLAUDE.md "…and adopt A12's JSON I/O BEHAVIOUR exactly, not
 * just its shape").
 *
 * ## Why comments are on
 * A12 builds **both** of its mappers with `JsonParser.Feature.ALLOW_COMMENTS` —
 * `ObjectMapperProvider.createPreconfiguredObjectMapper` (the model serializer's) and
 * `createObjectMapperForDocSerializer` (the document serializer's). A commented model or document is therefore a
 * **legal A12 artifact**, not merely input we could be generous about, and refusing one makes a file the A12
 * tooling round-trips unreadable here.
 *
 * ## Why nothing else is
 * Neither A12 mapper enables `ALLOW_TRAILING_COMMA`, so a trailing comma stays a parse error. Matching A12's
 * ingress means matching its **strictness** too — this instance is a parity decision, never a "be forgiving"
 * one, and a future leniency belongs here only with an A12 counterpart cited beside it.
 *
 * Number fidelity needs no flag: kotlinx's `JsonPrimitive.content` keeps the literal text verbatim, which keeps
 * every digit A12 keeps by configuring `USE_BIG_DECIMAL_FOR_FLOATS` + `STRIP_TRAILING_BIGDECIMAL_ZEROES=false`.
 * The literal is not A12's TEXT, though: A12 reads a number node's `BigDecimal` or integer rendering, which the
 * document reader derives lexically (`A12DocumentJson.numberNodeTextOrNull`; KERNEL-FINDINGS KF1).
 *
 * Our **own** formats are deliberately not routed here — the conformance case envelope is our shape, so A12's
 * leniency is not automatically its contract.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val A12_JSON: Json = Json { allowComments = true }

/**
 * Parse [text] with A12's ingress strictness. kotlinx reads any unquoted token as a literal, even in strict mode, so
 * `abc`, `True`, `NaN`, `01`, `1.`, `.5` and `+1` arrive as primitives, where A12's Jackson parser leaves no model
 * and no document for each of them (KERNEL-FINDINGS KF593). This refuses a literal that is not `true`, `false`,
 * `null` or an RFC 8259 number, with the [SerializationException] every caller already treats as unparseable.
 */
internal fun parseA12Json(text: String): JsonElement = A12_JSON.parseToJsonElement(text).also(::requireJsonLiterals)

private fun requireJsonLiterals(root: JsonElement) {
    val pending = ArrayList<JsonElement>()
    pending += root
    while (pending.isNotEmpty()) {
        when (val element = pending.removeAt(pending.lastIndex)) {
            is JsonObject -> pending.addAll(element.values)
            is JsonArray -> pending.addAll(element)
            is JsonNull -> Unit
            is JsonPrimitive -> if (!element.isString && !element.content.isJsonLiteral()) {
                throw SerializationException("'${element.content}' is not a JSON literal")
            }
        }
    }
}

private fun String.isJsonLiteral(): Boolean = this == "true" || this == "false" || JSON_NUMBER.matches(this)

private val JSON_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

