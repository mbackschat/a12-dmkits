package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.ast.emptyValue
import io.github.mbackschat.a12.dm.interpreter.ast.typedValue
import io.github.mbackschat.a12.dm.interpreter.model.Kind

/**
 * A **lightweight, map-backed [EvalContext] double** for unit-testing the [Interpreter] directly on SINGLE-ROW
 * shapes — `Interpreter(SingleRowDoc("/Order", values, kinds)).fires(cond)` — with no model/`Document` stack.
 * A field referenced but ABSENT from [values] is EMPTY; the kernel's "an entirely empty row is not evaluated"
 * rule is the [evaluated] gate. No repetition, so [star] is unused.
 *
 * **This is NOT a second production document model — it is a test seam, kept deliberately** (the alternative,
 * consolidating onto the general [Document], was considered and rejected 2026-06-24: it would only make the
 * isolated Interpreter unit tests heavier without removing real duplication). The general [Document] is the
 * production AND **performance** path — benchmark only that. This `Map`-backed double has O(1) lookups, so its
 * timings are a fiction; **never benchmark it** (INTERPRETER-PERFORMANCE.md).
 */
class SingleRowDoc(
    private val rowPath: String,
    private val values: Map<String, String>,
    private val kinds: Map<String, Kind>,
) : EvalContext {

    private fun kindOf(path: String): Kind =
        kinds[path] ?: error("no kind declared for $path")

    override val evaluated: Boolean
        get() = values.keys.any { it.startsWith("$rowPath/") }

    override fun field(path: String): Val =
        values[path]?.let { typedValue(kindOf(path), it) } ?: emptyValue(kindOf(path))

    override fun filled(path: String): Boolean = values.containsKey(path)

    override fun star(path: String, bindDepth: Int): List<Val> =
        error("a single-row doc has no repetition for $path")

    override fun starOmission(path: String, bindDepth: Int): Boolean = false

    override fun displayValue(path: String): String? = values[path]

    override fun declaredKind(path: String): Kind? = kinds[path]
}
