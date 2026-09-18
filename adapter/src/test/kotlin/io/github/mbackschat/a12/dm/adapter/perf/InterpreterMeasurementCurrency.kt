package io.github.mbackschat.a12.dm.adapter.perf

import java.io.File

/**
 * Whether interpreter production source has outrun every recorded measurement.
 *
 * The rule is separated from its test so it can be driven against a fabricated repository — see
 * [InterpreterMeasurementCurrencyGuardTest]'s control, and `PerfAnchors.headCommitIn` for the same seam and the
 * same reason.
 */
internal object InterpreterMeasurementCurrency {

    /**
     * Interpreter source that SHIPS. `commonTest`/`jvmTest`/`jsTest` are deliberately absent: a test-only commit
     * changes no consumer's runtime and owing a measurement for one would train people to run the gate to clear
     * noise, which is how a gate stops being read.
     */
    private val PRODUCTION_PATHS = listOf(
        "interpreter/src/commonMain",
        "interpreter/src/jvmMain",
        "interpreter/src/jsMain",
    )

    /**
     * Commits touching [PRODUCTION_PATHS] that no record covers, newest first — empty when measurement is current.
     *
     * **Minimum over every record rather than over the newest**, because records are written per run and a run
     * may be taken on an older tree while a newer record exists from a side branch: asking only the newest could
     * demand a measurement that a different record already supplies. A commit is covered when SOME record sits at
     * or after it.
     */
    fun unmeasuredCommits(repository: File): List<String> {
        val measured = recordedCommits(repository).filter { known(repository, it) }
        if (measured.isEmpty()) {
            // No record at all is not "measured": it is the strongest form of the gap this guard exists for, so
            // report the whole production history rather than passing for want of a comparand.
            return revList(repository, range = null)
        }
        return measured
            .map { revList(repository, range = "$it..HEAD") }
            .minBy { it.size }
    }

    /** Every `commit` a committed record names, with the `-DIRTY`/`-UNKNOWN-STATE` labels stripped. */
    private fun recordedCommits(repository: File): Set<String> {
        val root = File(repository, "perf-results")
        if (!root.isDirectory) return emptySet()
        return root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".jsonl") }
            .flatMap { file -> file.readLines().asSequence().mapNotNull { commitOf(it) } }
            .toSet()
    }

    /** The `"commit": "<value>"` of one record line, read textually so no JSON dependency enters the guard. */
    private fun commitOf(line: String): String? {
        val marker = "\"commit\":"
        val at = line.indexOf(marker).takeIf { it >= 0 } ?: return null
        val opening = line.indexOf('"', at + marker.length).takeIf { it >= 0 } ?: return null
        val closing = line.indexOf('"', opening + 1).takeIf { it >= 0 } ?: return null
        return line.substring(opening + 1, closing).substringBefore('-').takeIf { it.isNotBlank() }
    }

    private fun known(repository: File, commit: String): Boolean =
        git(repository, "cat-file", "-e", "$commit^{commit}") != null

    private fun revList(repository: File, range: String?): List<String> {
        val arguments = buildList {
            add("rev-list")
            add("--abbrev-commit")
            add(range ?: "HEAD")
            add("--")
            addAll(PRODUCTION_PATHS)
        }
        return git(repository, *arguments.toTypedArray())
            ?.lines()
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    /** Null when git itself refuses — an unknown revision is a question this guard must not answer with a pass. */
    private fun git(repository: File, vararg arguments: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(repository)
            .redirectErrorStream(false)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()

    /**
     * A repository whose interpreter production source moved past its only record — the control's fixture.
     *
     * Returns the commits that should be reported. It writes a record naming the FIRST commit and then changes
     * production source again, so a guard that reads records correctly still has exactly one commit to report.
     */
    fun fabricateRepositoryWithUnmeasuredChange(root: File): List<String> {
        val production = File(root, "interpreter/src/commonMain/kotlin")
        production.mkdirs()
        File(root, "perf-results/fabricated").mkdirs()
        check(git(root, "init", "--quiet") != null) { "could not init the fabricated repository at $root" }
        git(root, "config", "user.email", "guard@example.invalid")
        git(root, "config", "user.name", "guard")

        File(production, "Engine.kt").writeText("val engine = 1\n")
        git(root, "add", "-A")
        git(root, "commit", "--quiet", "-m", "first production commit")
        val measured = git(root, "rev-parse", "--short", "HEAD").orEmpty()

        File(root, "perf-results/fabricated/run.jsonl")
            .writeText("""{"kind":"run","commit":"$measured"}""" + "\n")
        git(root, "add", "-A")
        git(root, "commit", "--quiet", "-m", "record the measurement")

        File(production, "Engine.kt").writeText("val engine = 2\n")
        git(root, "add", "-A")
        git(root, "commit", "--quiet", "-m", "production moves past the record")

        return revList(root, range = "$measured..HEAD")
    }
}
