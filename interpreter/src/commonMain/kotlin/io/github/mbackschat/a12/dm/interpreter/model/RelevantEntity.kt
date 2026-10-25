package io.github.mbackschat.a12.dm.interpreter.model

/**
 * A relevant entity for **partial validation** (`DmInterpreter.validatePart`) — the interpreter's twin of the
 * kernel's `DocumentMultiPointer` relevant set. A [path] plus its repetition [indices] (one per path part, the
 * group/field included). An index of [ALL] (the kernel's `ALL_SEMANTIC` wildcard) matches **every** repetition
 * of that path element. A relevant **group** makes all its descendants relevant (the kernel rule).
 *
 * Partial validation runs a rule through its **error field**'s merged selector: every relevant pointer of that
 * field and its ancestor groups, a coordinate they disagree on becoming a wildcard, and a deeper level the
 * iteration never bound counting as a wildcard too (IF268, KF621). That selector decides dispatch only, never
 * operand relevance (IF387). Every route treats any
 * referenced field instance that is **not** relevant as UNKNOWN (3VL) — so the existing Kleene logic produces
 * the kernel's suppression (`true And Unknown` suppresses; `true Or Unknown` still fires). KERNEL-SEMANTICS §12.
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
