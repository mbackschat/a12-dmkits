package io.github.mbackschat.a12.dm.consistency.syntax

/**
 * Decodes an authored STRING token; the caller has already recognized its complete outer quote pair.
 *
 * CommonUtils.removeAndUnescapeQuotesAndRemoveCrFromCrLf is the owner counterpart: doubled quotes and one
 * CRLF pass belong to the token boundary, while token text and offsets retain the original source.
 */
internal fun String.decodeAuthoredStringLiteral(): String =
    removeSurrounding("\"").replace("\"\"", "\"").replace("\r\n", "\n")
