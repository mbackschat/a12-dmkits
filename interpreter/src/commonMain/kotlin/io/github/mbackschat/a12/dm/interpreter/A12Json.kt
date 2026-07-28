package io.github.mbackschat.a12.dm.interpreter

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

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
 * Number fidelity needs no flag: kotlinx's `JsonPrimitive.content` keeps the literal text verbatim, which is the
 * result A12 reaches by configuring `USE_BIG_DECIMAL_FOR_FLOATS` + `STRIP_TRAILING_BIGDECIMAL_ZEROES=false`
 * (KERNEL-FINDINGS "The canonical Document JSON …").
 *
 * Our **own** formats are deliberately not routed here — the conformance case envelope is our shape, so A12's
 * leniency is not automatically its contract.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val A12_JSON: Json = Json { allowComments = true }
