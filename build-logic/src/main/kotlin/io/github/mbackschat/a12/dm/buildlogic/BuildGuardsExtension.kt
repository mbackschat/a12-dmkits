package io.github.mbackschat.a12.dm.buildlogic

import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.publish.maven.MavenPom
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult

/**
 * The shared build code this repository's guards are wired with. It lives in a Kotlin included build rather
 * than on the root project's `ext` for two reasons: its decisions are unit-testable there (see the
 * `CompiledPurity`/`GuardInputs`/`ExclusiveHosting` objects and their tests), and a project reaches it by
 * applying a plugin rather than by reading another project's state, which is what cross-project `ext`
 * access is.
 */
abstract class BuildGuardsExtension(private val project: Project) {

    private companion object {
        const val PROJECT_URL = "https://github.com/mbackschat/a12-dmtool-releases"
        const val SCM_CONNECTION = "scm:git:https://github.com/mbackschat/a12-dmtool-releases.git"
        const val CAPTURE_CLASSIFICATION = "nativeCaptureNonRuntimeSystemProperties"
    }

    /**
     * Declares the repository files a guard task READS at runtime, and refuses the two ways that
     * declaration goes wrong.
     *
     * A declared input that the guard does not read is harmless. A file it READS that nothing declares makes
     * the lane UP-TO-DATE on an edit to that file, so the guard is green while checking nothing — and static
     * analysis cannot substitute, because whether a read is covered depends on how Gradle RESOLVES the
     * declared trees, which only the run knows.
     */
    fun guardInputs(task: Test, named: Map<String, Any?>) {
        // A null value is a declaration the author meant to write and did not — it must read as an empty
        // input, which is what names it, rather than as a configuration-time NPE from deep inside Gradle.
        val declared: Map<String, Any> = named.mapValues { (_, value) -> value ?: project.files() }
        task.inputs.files(*declared.values.toTypedArray())
            .withPropertyName("guardReadRepoFiles")
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .optional()

        // Everything the execution-time closures need is resolved HERE: reaching `Task.project` from a
        // doFirst/doLast is deprecated in Gradle 9 and fails in 10, because it cannot work with the
        // configuration cache. A FileCollection captured now still resolves lazily, so emptiness is still
        // decided at execution time.
        val declaredTrees: Map<String, FileCollection> = declared.mapValues { project.files(it.value) }
        val declaredValues: Map<String, Any?> = LinkedHashMap(named)
        val observedDir = File(task.temporaryDir, "observed-reads")
        val rootPath = project.rootDir.toPath()
        val taskName = task.name
        task.systemProperty("a12.guard.observedReads", observedDir.absolutePath)

        task.doFirst {
            val empty = declaredValues.filterKeys { name ->
                GuardInputs.declaredEntryIsEmpty(declaredValues[name]) { declaredTrees.getValue(name).isEmpty }
            }.keys
            GuardInputs.emptyInputRefusal(taskName, empty)?.let { throw GradleException(it) }
            observedDir.deleteRecursively()
            observedDir.mkdirs()
        }

        // In the task's own doLast, NOT a shared finalizer. A finalizer runs even when its guard is skipped,
        // and then judges a task that never executed against an observation directory left by an EARLIER
        // build — under `-Pfast` a skipped guard would still be reported on, and on an UP-TO-DATE run the
        // directory is neither cleared nor rewritten. That is a false refusal, and a guard that refuses
        // justified work is worse than none. A doLast can only ever see the directory its own doFirst just
        // cleared, at the cost of not running when the guard itself fails.
        task.doLast {
            val declared = task.inputs.files.files.map { GuardInputs.relativize(rootPath, it) }.toSet()
            val reads = (observedDir.listFiles() ?: emptyArray())
                .flatMap { it.readLines() }
                .filter { it.isNotBlank() }
            GuardInputs.undeclaredRefusal(taskName, GuardInputs.undeclaredReads(reads, declared))
                ?.let { throw GradleException(it) }
        }
    }

    /**
     * Derives the broad `test` task's exclusions FROM the owning tasks' include filters, so a class added to
     * a guard lane leaves the broad suite in the same edit.
     *
     * Only for tasks that own their classes EXCLUSIVELY. A lane that deliberately re-runs classes the broad
     * suite also runs is a different shape and keeps its hand-written excludes.
     */
    @JvmOverloads
    fun routeExclusivelyHostedTests(
        broad: Test,
        owningTaskNames: List<String>,
        classifiedNonOwners: Map<String, String> = emptyMap(),
        untracedDuringCapture: Map<String, String> = emptyMap(),
    ) {
        val owners: Map<String, Set<String>> = owningTaskNames.associateWith { name ->
            project.tasks.named(name, Test::class.java).get().filter.includePatterns
        }
        ExclusiveHosting.emptyOwnerRefusal(broad.path, owners)?.let { throw GradleException(it) }
        ExclusiveHosting.ambiguousOwnershipRefusal(owners)?.let { throw GradleException(it) }
        val hostByClass = ExclusiveHosting.hostByClass(owners)

        // Every Test task that filters classes out of the SAME compiled output must be classified — an
        // owner, or a reasoned non-owner. This is what makes "a new exclusively owning task must be added to
        // the owners list" an enforced invariant rather than a comment.
        val sharedDirs by lazy { broad.testClassesDirs.files }
        project.tasks.withType(Test::class.java).forEach { candidate ->
            val requires = ExclusiveHosting.requiresClassification(
                taskName = candidate.name,
                broadTaskName = broad.name,
                owningTaskNames = owningTaskNames,
                classifiedNonOwners = classifiedNonOwners.keys,
                hasIncludePatterns = { candidate.filter.includePatterns.isNotEmpty() },
                sharesCompiledOutput = { candidate.testClassesDirs.files.intersect(sharedDirs).isNotEmpty() },
            )
            if (requires) {
                throw GradleException(ExclusiveHosting.unclassifiedLaneRefusal(broad.path, candidate.name))
            }
        }

        ExclusiveHosting.untracedNonOwnerRefusal(
            broad.path, untracedDuringCapture.keys, owningTaskNames,
        )?.let { throw GradleException(it) }

        // The native-image REFLECTION CAPTURE traces this lane only (`-PrkNativeAgent`), and excluding a
        // class that drives the product in-process would shrink the traced surface. During a capture the
        // exclusion is therefore not applied at all, so the traced set stays a superset of what it was
        // before this derivation existed. Capture is release-only.
        //
        // Absorption has two failure modes, and the axis that decides between them is whether the class
        // exercises dmtool's RUNTIME or build-time tooling:
        //
        //  • runtime → stays IN, and this lane must SUPPLY the owning task's runtime inputs. Without them a
        //    packaged-launcher case execs the module directory instead of the executable; and excluding such
        //    a class instead is not the alternative, because a lane can drive the CLI in-process, which is
        //    where the picocli field injections behind `rule eval` and `batch` are traced.
        //  • build tooling → stays OUT, because its traffic is not dmtool reachability: javac under the agent
        //    registers `jdk.compiler` resource bundles, which the metadata hygiene guard refuses. What makes
        //    staying OUT safe is an ENUMERATION of the class's product-facing reflection, never a metadata
        //    diff against the baseline — the baseline is captured with the class already excluded, so that
        //    comparison holds whether or not the exclusion is correct.
        val buildTooling = ExclusiveHosting.buildToolingClasses(hostByClass, untracedDuringCapture.keys)
        val tracingForNativeImage = project.hasProperty("rkNativeAgent")
        if (tracingForNativeImage) {
            buildTooling.forEach { broad.filter.excludeTestsMatching(it) }
            assertCaptureRehostIsSupplied(broad, owningTaskNames - untracedDuringCapture.keys)
            val stayingIn = hostByClass.size - buildTooling.size
            val toolingLanes = untracedDuringCapture.keys.sorted()
            broad.doFirst {
                broad.logger.lifecycle(
                    "${broad.path}: -PrkNativeAgent set — $stayingIn exclusively-owned classes stay IN to " +
                        "keep the capture's surface complete; ${buildTooling.size} stay out as build " +
                        "tooling ($toolingLanes).",
                )
            }
        } else {
            hostByClass.keys.forEach { broad.filter.excludeTestsMatching(it) }
            val leaked = linkedSetOf<String>()
            broad.addTestListener(object : TestListener {
                override fun beforeSuite(suite: TestDescriptor) = Unit
                override fun afterSuite(suite: TestDescriptor, result: TestResult) = Unit
                override fun beforeTest(testDescriptor: TestDescriptor) = Unit
                override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
                    val className = testDescriptor.className
                    if (className != null && hostByClass.containsKey(className)) {
                        leaked += className
                    }
                }
            })
            broad.doLast {
                TestPatternRouting.leakRefusal(broad.path, leaked)?.let { throw GradleException(it) }
            }
        }
        wireMisroutedTestsCorrective(broad, hostByClass, tracingForNativeImage)
    }

    /**
     * Refuses a `--tests` selection that reaches ONLY owned classes, naming the lane that hosts each. Stays
     * silent when the patterns still reach something this task can run, so a broad pattern spanning both
     * lanes behaves exactly as before.
     */
    private fun wireMisroutedTestsCorrective(
        broad: Test,
        hostByClass: Map<String, String>,
        tracingForNativeImage: Boolean,
    ) {
        // The root project's path is already ':', so only a subproject needs the separator appended.
        val taskPathPrefix = if (project.path == ":") ":" else project.path + ":"
        broad.doFirst {
            if (tracingForNativeImage) {
                return@doFirst      // nothing was excluded, so there is nothing to reroute
            }
            // `commandLineIncludePatterns` — what the caller typed after `--tests` — is Gradle-internal, so it
            // is read reflectively and any failure falls back to Gradle's own message rather than breaking
            // the build. This corrective is a courtesy; it must never become a way to fail a legal run.
            val patterns = try {
                @Suppress("UNCHECKED_CAST")
                (broad.filter.javaClass.getMethod("getCommandLineIncludePatterns").invoke(broad.filter)
                    as Collection<String>).toList()
            } catch (ignored: Throwable) {
                return@doFirst
            }
            val misrouted = TestPatternRouting.misrouted(hostByClass, patterns)
            if (misrouted.isEmpty()) {
                return@doFirst
            }
            val stillReachable = broad.testClassesDirs.files.filter { it.isDirectory }.any { dir: File ->
                dir.walkTopDown().any { file: File ->
                    if (!file.isFile) {
                        return@any false
                    }
                    val fqcn: String = TestPatternRouting.fullyQualifiedName(dir, file) ?: return@any false
                    !hostByClass.containsKey(fqcn) &&
                        patterns.any { pattern -> TestPatternRouting.matches(pattern, fqcn) }
                }
            }
            if (!stillReachable) {
                throw GradleException(
                    TestPatternRouting.misroutedRefusal(taskPathPrefix, patterns, misrouted),
                )
            }
        }
    }

    /**
     * Deliberately narrow, and the narrowness is the honest part: this compares `systemProperties` KEYS
     * only. It does NOT see a property set inside a `doFirst`, a wrong VALUE for a key that is present, a
     * `dependsOn`, or an `inputs.file`. Widening it means resolving execution-time state at configuration
     * time, which Gradle does not offer. So it catches the one failure mode that actually occurred, and the
     * rest of the absorption contract stays the author's job.
     */
    private fun assertCaptureRehostIsSupplied(broad: Test, tracedOwners: List<String>) {
        tracedOwners.forEach { name ->
            val owner = project.tasks.named(name, Test::class.java).get()
            val extra = owner.extensions.extraProperties
            val raw = extra.takeIf { it.has(CAPTURE_CLASSIFICATION) }?.get(CAPTURE_CLASSIFICATION)
            val classified = ExclusiveHosting.classificationRows(raw)
                ?: throw GradleException(ExclusiveHosting.invalidClassificationMessage(name))
            ExclusiveHosting.invalidClassificationRefusal(
                name, owner.systemProperties.keys, classified,
            )?.let { throw GradleException(it) }
            ExclusiveHosting.unsuppliedPropertyRefusal(
                broad.path, name, owner.systemProperties.keys, broad.systemProperties.keys, classified.keys,
            )?.let { throw GradleException(it) }
            if (classified.isNotEmpty()) {
                project.logger.lifecycle(
                    "${broad.path}: '$name' capture re-host omits scheduler-only properties " +
                        "${classified.keys.sorted()} (${classified.values.joinToString("; ")})",
                )
            }
        }
    }

    /**
     * Counts a token in Kotlin source, over CODE only — see [KotlinSource]. A single-owner rule counted over
     * raw text has both failure directions, and both are reachable: a mention in prose fails a correct tree,
     * and commenting the real call out leaves the count unchanged while the invariant is gone.
     */
    fun countCallSitesInCode(source: java.io.File, token: String): Int =
        KotlinSource.countInCode(source.readText(), token)

    /**
     * The POM metadata every published dm kit shares. MIT — the kits are kernel-free, so no EUPL rider and
     * no kernel dependency to block Central.
     */
    fun dmkitsPom(pom: MavenPom) {
        pom.url.set(PROJECT_URL)
        pom.licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        pom.developers {
            developer {
                id.set("mbackschat")
                name.set("Martin Backschat")
            }
        }
        pom.scm {
            url.set(PROJECT_URL)
            connection.set(SCM_CONNECTION)
        }
    }
}
