package io.github.mbackschat.a12.dm.interpreter.gen

import io.github.mbackschat.a12.dm.interpreter.eval.Document

/**
 * A [PlacementSink] backed by a [Document] — so [SeedGenerator] can build an interpreter instance directly.
 * (`Document`'s own `group`/`field`/`emptyField` return the document for chaining; this just discards that.)
 * The interpreter side of the dual-engine generation seam; the kernel side is an adapter in `:adapter`.
 */
fun Document.asSink(): PlacementSink = object : PlacementSink {
    override fun group(path: String, vararg reps: Int) { this@asSink.group(path, *reps) }
    override fun field(path: String, value: String, vararg reps: Int) { this@asSink.field(path, value, *reps) }
    override fun emptyField(path: String, vararg reps: Int) { this@asSink.emptyField(path, *reps) }
}
