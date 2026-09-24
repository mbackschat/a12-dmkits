package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.eval.Document

/**
 * One fixture document written to the kernel's [RuntimeLaws.Doc] and the interpreter's [Document] in lockstep, so
 * a differential states each document once and the two engines cannot be handed different inputs.
 */
internal class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
    fun group(path: String, vararg reps: Int): Both {
        kDoc.group(path, *reps); iDoc.group(path, *reps); return this
    }

    fun field(path: String, v: String, vararg reps: Int): Both {
        kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this
    }

    /** A present-but-valueless field, which the engines keep distinct from an absent one. */
    fun emptyField(path: String, vararg reps: Int): Both {
        kDoc.emptyField(path, *reps); iDoc.emptyField(path, *reps); return this
    }
}
