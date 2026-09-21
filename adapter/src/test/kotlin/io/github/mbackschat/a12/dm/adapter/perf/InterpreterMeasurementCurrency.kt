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
     * What this checkout can say about measurement currency. Three outcomes, not two, because the two ways of
     * having no comparand are different claims and only one of them is about the source.
     */
    sealed interface Currency {
        /** Every interpreter production commit is covered by some record. */
        data object Current : Currency

        /** Production source moved past every record; these commits owe a measurement, newest first. */
        data class Unmeasured(val commits: List<String>) : Currency

        /** Records exist but this checkout cannot resolve the commits they name, so currency is not decidable. */
        data class Undecidable(val reason: String) : Currency
    }

    /**
     * Whether [PRODUCTION_PATHS] has outrun every recorded measurement.
     *
     * **Minimum over every record rather than over the newest**, because records are written per run and a run
     * may be taken on an older tree while a newer record exists from a side branch: asking only the newest could
     * demand a measurement that a different record already supplies. A commit is covered when SOME record sits at
     * or after it.
     *
     * **`Undecidable` exists because a SHALLOW checkout is indistinguishable from an unmeasured one by commit
     * list alone, and it shipped that way (2026-09-21).** CI runs `actions/checkout` at its default
     * `fetch-depth: 1`, so none of the committed records' commits resolve, every record is filtered out, and the
     * old "no record at all" fallback reported the whole production history — which in a shallow clone is the
     * single available commit. The guard therefore named HEAD as owing a measurement on a commit that changed
     * only `settings.gradle` and a doc, and the message asserted that production source had moved when nothing
     * had been read that could establish it. Failing is still right here (NFR #1 refuses inconclusive evidence);
     * naming an innocent commit for it is not, so the two states are now separate arms.
     */
    fun currency(repository: File): Currency {
        val recorded = recordedCommits(repository)
        val measured = recorded.filter { known(repository, it) }
        if (measured.isEmpty()) {
            // Records present but none resolvable is a fact about the CHECKOUT; only their total absence is the
            // gap this guard exists for, and only that one licenses reading the production history as the answer.
            if (recorded.isNotEmpty()) return Currency.Undecidable(undecidableReason(repository, recorded.size))
            val history = revList(repository, range = null)
            return if (history.isEmpty()) Currency.Current else Currency.Unmeasured(history)
        }
        val outstanding = measured
            .map { revList(repository, range = "$it..HEAD") }
            .minBy { it.size }
        return if (outstanding.isEmpty()) Currency.Current else Currency.Unmeasured(outstanding)
    }

    private fun undecidableReason(repository: File, records: Int): String {
        val shallow = if (isShallow(repository)) {
            "this repository is SHALLOW (`git rev-parse --is-shallow-repository` is true), so the history the " +
                "records point into was never fetched"
        } else {
            "the history the records point into is not present in this repository"
        }
        return "$records committed measurement record(s) name commits this checkout cannot resolve, and $shallow. " +
            "Measurement currency is therefore not decidable here and this guard refuses to guess: it will not " +
            "report a commit as unmeasured on evidence it could not read. Fetch full history — in GitHub " +
            "Actions that is `fetch-depth: 0` on actions/checkout — and run it again."
    }

    /** True for a `--depth`-limited clone; `git` prints `true`/`false` and this guard treats a refusal as false. */
    private fun isShallow(repository: File): Boolean =
        git(repository, "rev-parse", "--is-shallow-repository") == "true"

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

    /**
     * A repository that is CURRENT and whose HEAD touches no production path — the shallow control's fixture.
     *
     * The head commit must be a non-production one, because that is what makes the shipped defect visible: a
     * shallow clone of this repository has exactly one commit, that commit touched no [PRODUCTION_PATHS] file,
     * and a guard reading it as unmeasured is accusing a commit it cannot have examined. `9c86459b3` — which
     * changed only `settings.gradle` and a doc — was that commit in CI.
     */
    fun fabricateCurrentRepositoryWithNonProductionHead(root: File) {
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
        File(root, "NOTES.md").writeText("a commit that ships nothing\n")
        git(root, "add", "-A")
        git(root, "commit", "--quiet", "-m", "record the measurement and change a doc")
    }

    /** A `--depth 1` clone of [source] at [target]; `file://` because git refuses `--depth` on a plain path. */
    fun shallowClone(source: File, target: File): Boolean {
        val parent = target.parentFile ?: return false
        parent.mkdirs()
        return git(
            parent, "clone", "--depth", "1", "--quiet",
            "file://${source.absolutePath}", target.absolutePath,
        ) != null
    }
}
