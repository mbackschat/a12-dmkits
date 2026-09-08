package io.github.mbackschat.a12.dm.buildlogic

/**
 * The decision half of the compiled-purity guard: given jdeps' output, which compiled dependencies exist,
 * and is the result a refusal?
 *
 * A compiled dependency edge is the purity boundary. Source regexes cannot model Java text blocks, Kotlin
 * raw strings/templates, or Kotlin's nested comments; a delimiter inside a literal once hid later code from
 * a guard. jdeps reads class dependencies instead, so comments and string constants are irrelevant by
 * construction.
 *
 * Everything here is pure so it can be unit-tested. Running jdeps and counting class files is the task's
 * job; deciding what the output means is this object's.
 */
object CompiledPurity {

    /** `com.example.Foo -> java.util.List  java.base` — the arrow's right-hand side is the dependency. */
    private val EDGE = Regex("""^\s*\S+\s+->\s+(\S+)""")

    fun dependencies(jdepsOutput: String): List<String> =
        jdepsOutput.lineSequence().mapNotNull { EDGE.find(it)?.groupValues?.get(1) }.toList()

    /**
     * A scan over no classes reports no violations, which is indistinguishable from a clean scan. Refusing
     * is what turns a moved or emptied compiled source root into a build failure instead of a false green.
     */
    fun zeroClassesRefusal(guardName: String, classCount: Int): String? =
        if (classCount == 0) {
            "$guardName scanned ZERO class files — the compiled source roots moved or emptied"
        } else {
            null
        }

    /**
     * jdeps ships beside the launcher of the toolchain that compiled the classes. A missing one is a broken
     * toolchain rather than a purity violation, so it is named as such: the alternative is a scan that
     * reports nothing and reads as clean.
     */
    fun missingJdepsRefusal(guardName: String, jdeps: java.io.File, beside: java.io.File): String? =
        if (jdeps.isFile) null else "$guardName cannot find jdeps beside $beside"

    /** A non-zero jdeps exit means the scan did not happen; its output is the only useful diagnostic. */
    fun inspectionFailedRefusal(guardName: String, exitValue: Int, output: String): String? =
        if (exitValue == 0) null else "$guardName could not inspect compiled dependencies:\n$output"

    /**
     * The positive control is a dependency the scanned classes certainly have. Without it a jdeps run that
     * silently inspected nothing — a wrong root, an option change, a filtered output — would report clean.
     */
    fun missingPositiveControlRefusal(
        guardName: String,
        dependencies: List<String>,
        positiveControl: String,
    ): String? =
        if (dependencies.none { it.startsWith(positiveControl) }) {
            "$guardName did not see its compiled-reference positive control $positiveControl"
        } else {
            null
        }

    /**
     * Prefix matching, not equality: a forbidden package name must catch every type under it, and a
     * dependency is reported as a fully-qualified type name.
     */
    fun violations(dependencies: List<String>, forbidden: List<String>): List<String> =
        dependencies.filter { dependency -> forbidden.any { dependency.startsWith(it) } }.distinct().sorted()

    fun violationRefusal(guardName: String, violations: List<String>): String? =
        if (violations.isEmpty()) {
            null
        } else {
            "$guardName violated; forbidden compiled dependencies:\n  " + violations.joinToString("\n  ")
        }
}
