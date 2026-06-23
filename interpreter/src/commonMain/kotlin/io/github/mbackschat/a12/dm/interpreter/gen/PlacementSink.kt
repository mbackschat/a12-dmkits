package io.github.mbackschat.a12.dm.interpreter.gen

/**
 * A sink that receives document placements — one group row / field value at a time, each addressed by **one
 * 1-based repetition index per path part** (group AND field), exactly as [io.github.mbackschat.a12.dm.interpreter.eval.Document]
 * is addressed. It is the seam that lets a generator ([SeedGenerator]) build a document **once** and target
 * any backing instance: the interpreter's `Document` (via [Document.asSink]), a kernel `IDocument` (an adapter
 * in `:adapter`), or a JSON serializer (a future `dmtool seed` verb). Kernel-free, so it lives in `:interpreter`
 * and rides the native/JS runtime.
 */
interface PlacementSink {
    /** A group row at the given 1-based repetition indices (one per path part). */
    fun group(path: String, vararg reps: Int)
    /** A field with a value at the given indices (one per path part, the field included). */
    fun field(path: String, value: String, vararg reps: Int)
    /** A present-but-valueless (unfilled) field. */
    fun emptyField(path: String, vararg reps: Int)
}
