package io.github.mbackschat.a12.dm.buildlogic

/**
 * The decision half of the guard-input contract, which answers two questions about a guard task that reads
 * repository files at runtime.
 *
 * A guard's file set is derived twice — declared to Gradle so an edit re-runs the lane, and re-derived at
 * runtime by the guard itself. Two derivations of one set can disagree, and when they do the lane goes
 * `UP-TO-DATE` while the guard checks something else: green while checking nothing. These rules compare
 * them, and refuse the two ways the comparison goes wrong.
 */
object GuardInputs {

    /**
     * A read is covered when it IS a declared file, or lies inside a declared directory, or is a directory a
     * declared file lies within — a guard resolves a directory in order to walk it, and Gradle resolves a
     * fileTree to its files, so neither side ever names the other exactly.
     *
     * The separator is what makes it a containment test rather than a string test: without it a read under
     * `docsX/` counts as covered by a declared `docs`, and the guard's lane then goes UP-TO-DATE on an edit
     * it never sees.
     */
    fun isCovered(read: String, declared: Collection<String>): Boolean =
        declared.any { it == read || read.startsWith("$it/") || it.startsWith("$read/") }

    fun undeclaredReads(reads: Collection<String>, declared: Collection<String>): List<String> =
        reads.filterNot { isCovered(it, declared) }.distinct().sorted()

    fun undeclaredRefusal(taskName: String, undeclared: List<String>): String? =
        if (undeclared.isEmpty()) {
            null
        } else {
            "$taskName: ${undeclared.size} repo path(s) read at runtime but NOT covered by a declared " +
                "input. Editing one leaves this lane UP-TO-DATE and the guard unrun — green while checking " +
                "nothing. Add each to its guardInputs map:\n  " + undeclared.joinToString("\n  ")
        }

    /**
     * A declared entry counts as empty when it resolves to no file — with one case a `FileCollection` cannot
     * answer. A single declared `File` resolves to itself whether or not it exists, so a deleted file leaves
     * the collection non-empty; existence is the question there, and most call sites declare one file rather
     * than a tree.
     */
    fun declaredEntryIsEmpty(value: Any?, resolvesToNoFile: () -> Boolean): Boolean = when (value) {
        null -> true
        is java.io.File -> !value.exists()
        else -> resolvesToNoFile()
    }

    /**
     * A moved or renamed folder silently EMPTIES a declared tree, and the guard then goes green while
     * checking NOTHING — a false green only an expensive probe would ever catch.
     */
    fun emptyInputRefusal(taskName: String, emptyNames: Collection<String>): String? =
        if (emptyNames.isEmpty()) {
            null
        } else {
            "$taskName: guarded runtime-read input(s) ${emptyNames.sorted()} are EMPTY or MISSING — a moved " +
                "or renamed folder likely silenced this guard while it checked nothing. Re-point the guard's " +
                "fileTree to the moved location; a guard must never run against an empty input."
        }

    /** Repository-relative, forward-slashed — the spelling both sides of the comparison must agree on. */
    fun relativize(rootPath: java.nio.file.Path, file: java.io.File): String =
        rootPath.relativize(file.toPath()).toString().replace('\\', '/')
}
