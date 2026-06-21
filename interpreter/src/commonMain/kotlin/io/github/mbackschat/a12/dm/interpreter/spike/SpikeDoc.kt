package io.github.mbackschat.a12.dm.interpreter.spike

/**
 * The plain-data document the interpreter walks for SINGLE-ROW shapes (1-3) — NO kernel types. A field
 * referenced but ABSENT from [values] is EMPTY; the kernel's "an entirely empty row is not evaluated"
 * rule is the [evaluated] gate. It IS an [EvalContext] (no repetition, so [star] is unused).
 */
class SpikeDoc(
    private val rowPath: String,
    private val values: Map<String, String>,
    private val kinds: Map<String, Kind>,
) : EvalContext {

    private fun kindOf(path: String): Kind =
        kinds[path] ?: error("spike: no kind declared for $path")

    override val evaluated: Boolean
        get() = values.keys.any { it.startsWith("$rowPath/") }

    override fun field(path: String): Val =
        values[path]?.let { typedValue(kindOf(path), it) } ?: emptyValue(kindOf(path))

    override fun filled(path: String): Boolean = values.containsKey(path)

    override fun star(path: String): List<Val> =
        error("spike: a single-row doc has no repetition for $path")
}
