package io.github.mbackschat.a12.dm.interpreter.model

/**
 * A relevant entity for **partial validation** (`DmInterpreter.validatePart`) — the interpreter's twin of the
 * kernel's `DocumentMultiPointer` relevant set. A [path] plus its repetition [indices] (one per path part, the
 * group/field included). An index of [ALL] (the kernel's `ALL_SEMANTIC` wildcard) matches **every** repetition
 * of that path element. A relevant **group** makes all its descendants relevant (the kernel rule).
 *
 * Partial validation runs only the rules whose **error field** is in the relevant set at the repetition levels
 * the rule's ITERATION bound — a deeper level the iteration never bound counts as a wildcard, and iteration is
 * reference-driven rather than decided by the rule's declared group (IF268) — and treats any
 * referenced field instance that is **not** relevant as UNKNOWN (3VL) — so the existing Kleene logic produces
 * the kernel's suppression (`true And Unknown` suppresses; `true Or Unknown` still fires). KERNEL-FINDINGS
 * "Partial validation".
 */
data class RelevantEntity(val path: String, val indices: List<Int>) {
    companion object {
        /** The `ALL_SEMANTIC` wildcard — matches any repetition at that path level (never a real 1-based index). */
        const val ALL: Int = 0

        /** Relevant at **every** repetition (all path parts wildcarded) — the kernel's encouraged form. */
        fun anyRep(path: String): RelevantEntity =
            RelevantEntity(path, List(path.trim('/').split('/').size) { ALL })
    }
}
