package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import kotlin.jvm.JvmSynthetic

/**
 * The interpreter's internal comparison projection of a **message pointer**.
 *
 * <b>The typed value is the source of truth; these are derived from it and never the other way round.</b> That
 * direction is forced by a measurement rather than chosen: [messagePointerHouseForm] writes an index on every
 * segment except the last, so `("/Plan/Demand", [1, -5])` becomes `/Plan[1]/Demand` and
 * `("/Order/Items", [1, 0])` becomes
 * `/Order[1]/Items`. Both of A12's message sentinels sit exactly where the text is dropped, so a string can be
 * computed from the pointer and a pointer can not be recovered from the string.
 *
 * <b>[messagePointerHouseForm] is a TOOL-OWNED comparison projection, not an A12 format.</b> A12 defines no
 * pointer string for `PartiallyKnownDocumentMultiPointer` — no parser, no renderer — so nothing here claims to
 * be one. This spelling exists because the conformance corpus, the `:adapter` differential and dmtool's published
 * output have always compared it, and it stays available internally for exactly that. It must never be stored in
 * place of the pointer or exported as a consumer format.
 *
 * This is the interpreter's long-standing comparison spelling: a leading `/`, and the repetition written on
 * every segment that has one below it — `/Order[1]/Items[2]/Count`. The terminal index is not written, which is
 * the lossiness this projection is arranged around. The rendering is the engine's own [formatPointer], so the
 * corpus and engine diagnostics cannot drift into two spellings.
 */
@JvmSynthetic
internal fun messagePointerHouseForm(pointer: A12PartiallyKnownMultiPointer): String =
    formatPointer(pointer.fullName, pointer.repetitionIndexes)
