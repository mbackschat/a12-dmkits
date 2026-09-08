package io.github.mbackschat.a12.dm.buildlogic

import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations

/**
 * Refuses a compiled output that depends on a forbidden package. The subject is the class files, declared
 * as this task's inputs, so there is only one derivation of the scanned set and no second one to disagree
 * with it.
 *
 * The verdicts live in [CompiledPurity], where a unit test can reach them.
 */
@CacheableTask
abstract class VerifyCompiledPurityTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    /** Names the guard in every refusal, so a failure says which boundary broke. */
    @get:Input
    abstract val guardName: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classRoots: ConfigurableFileCollection

    /** Package prefixes no scanned class may depend on. */
    @get:Input
    abstract val forbidden: ListProperty<String>

    /** A prefix the scanned classes certainly depend on — see [CompiledPurity.missingPositiveControlRefusal]. */
    @get:Input
    abstract val positiveControl: Property<String>

    @get:Nested
    abstract val javaLauncher: Property<JavaLauncher>

    /**
     * A check task with no declared output is never up to date, so it re-runs on every invocation. The
     * stamp exists to give this one a cache key, and its content is the scan's verdict.
     */
    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun verify() {
        val name = guardName.get()
        val roots = classRoots.files.filter { it.exists() }
        val classCount = roots.sumOf { root ->
            root.walkTopDown().count { it.isFile && it.name.endsWith(".class") }
        }
        CompiledPurity.zeroClassesRefusal(name, classCount)?.let { throw GradleException(it) }

        val jdeps = jdepsExecutable(name)
        // Kept apart on purpose: only stdout carries dependency edges, and a stderr line that happened to
        // contain an arrow would otherwise be parsed as one. The diagnostic wants both, so it gets both.
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine(
                listOf(jdeps.absolutePath, "-verbose:class", "--ignore-missing-deps") +
                    roots.map { it.absolutePath },
            )
            standardOutput = stdout
            errorOutput = stderr
            isIgnoreExitValue = true
        }
        val output = stdout.toString(Charsets.UTF_8)
        val diagnostic = (output + stderr.toString(Charsets.UTF_8)).trim()
        CompiledPurity.inspectionFailedRefusal(name, result.exitValue, diagnostic)
            ?.let { throw GradleException(it) }

        val dependencies = CompiledPurity.dependencies(output)
        CompiledPurity.missingPositiveControlRefusal(name, dependencies, positiveControl.get())
            ?.let { throw GradleException(it) }
        val violations = CompiledPurity.violations(dependencies, forbidden.get())
        CompiledPurity.violationRefusal(name, violations)?.let { throw GradleException(it) }

        stamp.get().asFile.apply {
            parentFile.mkdirs()
            writeText("$name: $classCount class file(s) scanned, no forbidden compiled dependency\n")
        }
    }

    private fun jdepsExecutable(guard: String): java.io.File {
        val launcherPath = javaLauncher.get().executablePath.asFile
        val onWindows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
        val jdeps = java.io.File(launcherPath.parentFile, if (onWindows) "jdeps.exe" else "jdeps")
        CompiledPurity.missingJdepsRefusal(guard, jdeps, launcherPath)?.let { throw GradleException(it) }
        return jdeps
    }
}
