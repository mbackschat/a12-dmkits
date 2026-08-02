package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer

/**
 * The interpreter's own projections of a **message pointer**, and the one place they are produced.
 *
 * <b>The typed value is the source of truth; these are derived from it and never the other way round.</b> That
 * direction is forced by a measurement rather than chosen: [houseForm] writes an index on every segment except
 * the last, so `("/Plan/Demand", [1, -5])` becomes `/Plan[1]/Demand` and `("/Order/Items", [1, 0])` becomes
 * `/Order[1]/Items`. Both of A12's message sentinels sit exactly where the text is dropped, so a string can be
 * computed from the pointer and a pointer can not be recovered from the string.
 *
 * <b>[houseForm] is a TOOL-OWNED comparison projection, not an A12 format.</b> A12 defines no pointer string for
 * `PartiallyKnownDocumentMultiPointer` — no parser, no renderer — so nothing here claims to be one. This
 * spelling exists because the conformance corpus, the `:adapter` differential and dmtool's published output have
 * always compared it, and it stays available for exactly that. It must never be stored in place of the pointer.
 */
object MessagePointers {

    /**
     * The interpreter's long-standing comparison spelling: a leading `/`, and the repetition written on every
     * segment that has one below it — `/Order[1]/Items[2]/Count`.
     *
     * The terminal index is not written, which is the lossiness this whole file is arranged around. The
     * rendering itself is the engine's own `formatPointer`, so the corpus, the differential and this projection
     * cannot drift apart into two spellings of one string.
     */
    fun houseForm(pointer: A12PartiallyKnownMultiPointer): String =
        formatPointer(pointer.fullName, pointer.repetitionIndexes)

    /**
     * The published spelling for a message address: A12's canonical exact-pointer string when the address
     * really is one exact pointer, and otherwise `null`.
     *
     * `null` is the honest answer, not a failure. A wildcard above the last level, or an unresolved level
     * anywhere, names a set or a level that matched no row — and A12 gives that no textual form, so there is
     * nothing canonical to return. Callers that must print something fall back to [houseForm] and say so.
     *
     * <b>The decision is made on the typed value, never by re-parsing text.</b> `/Plan[1]/Demand` parses
     * perfectly well as the *exact* pointer `Plan[1]/Demand`, so a try-parse fallback would silently promote an
     * unresolved address to a resolved one.
     */
    fun canonicalExactForm(pointer: A12PartiallyKnownMultiPointer): String? {
        // The root is the one address whose conversion RAISES rather than answering — A12's own does, and
        // [A12PartiallyKnownMultiPointer.toExactPointer] matches it. A printer wants the absent answer, so the
        // case is named here rather than caught: a blanket catch would also swallow failures worth seeing.
        if (pointer.repetitionIndexes.isEmpty()) {
            return null
        }
        val exact = pointer.toExactPointer() ?: return null
        return A12PointerFormat.render(exact)
    }
}
