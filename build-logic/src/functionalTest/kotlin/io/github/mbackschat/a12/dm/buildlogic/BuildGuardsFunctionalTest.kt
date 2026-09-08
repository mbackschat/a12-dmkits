package io.github.mbackschat.a12.dm.buildlogic

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Proves each refusal FIRES against a real Gradle build, which the unit tests beside the decision objects
 * cannot: they establish what a rule decides, not that the plugin ever asks it.
 *
 * The repository's refactoring-safety convention is the reason this lane exists — a gate that has never
 * failed is indistinguishable from one that cannot. It also fixes the vacuity the text-matching guards had:
 * a mutant that gutted the ownership derivation left every `build.contains(...)` assertion passing while the
 * owned classes quietly re-entered the broad lane.
 */
class BuildGuardsFunctionalTest {

    private fun project(dir: File, buildScript: String): GradleRunner {
        dir.resolve("settings.gradle.kts").writeText("""rootProject.name = "probe"""")
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("a12.build-guards")
            }
            $buildScript
            """.trimIndent(),
        )
        return GradleRunner.create().withProjectDir(dir).withPluginClasspath()
    }

    private fun failureOf(dir: File, buildScript: String, vararg arguments: String): String =
        project(dir, buildScript).withArguments(*arguments, "--stacktrace").buildAndFail().output

    /**
     * A `Test` task with no candidate class files is SKIPPED as NO-SOURCE, and a skipped task runs neither
     * its doFirst nor its doLast — so a probe of either needs the task to be genuinely runnable. This writes
     * one real JUnit test and lends the synthetic project this lane's own runtime classpath, which carries
     * the JUnit jars; TestKit builds resolve no repository, so borrowing the classpath is what keeps the
     * lane offline.
     */
    private fun withARealTest(dir: File): String {
        val source = dir.resolve("src/test/java/probe/ProbeTest.java")
        source.parentFile.mkdirs()
        source.writeText(
            """
            package probe;
            class ProbeTest {
                @org.junit.jupiter.api.Test void runs() { }
            }
            """.trimIndent(),
        )
        val classpath = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .joinToString(", ") { "\"" + it.replace("\\", "\\\\") + "\"" }
        return """
            dependencies { testImplementation(files($classpath)) }
            tasks.withType<Test>().configureEach { useJUnitPlatform() }
        """.trimIndent()
    }

    @Test
    fun `the plugin applies and exposes the extension`(@TempDir dir: File) {
        val output = project(
            dir,
            """
            tasks.register("showExtension") {
                val present = project.extensions.findByName("a12Guards") != null
                doLast { logger.lifecycle("a12Guards present: ${'$'}present") }
            }
            """.trimIndent(),
        ).withArguments("showExtension").build().output
        assertTrue(output.contains("a12Guards present: true"), output)
    }

    @Test
    fun `an empty declared input tree is refused before the guard runs`(@TempDir dir: File) {
        val output = failureOf(
            dir,
            withARealTest(dir) + """

            val guard = tasks.register<Test>("probeGuard") {
                testClassesDirs = sourceSets["test"].output.classesDirs
                classpath = sourceSets["test"].runtimeClasspath
            }
            a12Guards.guardInputs(guard.get(), mapOf(
                "a corpus that has moved" to fileTree("no-such-directory")))
            """.trimIndent(),
            "probeGuard",
        )
        assertTrue(output.contains("a corpus that has moved"), output)
        assertTrue(output.contains("EMPTY or MISSING"), output)
    }

    @Test
    fun `a declared single file that does not exist is refused`(@TempDir dir: File) {
        // The other half of the emptiness rule, and the one a FileCollection does not answer the same way:
        // most call sites declare `file('docs/SOMETHING.md')` rather than a tree, and a deleted file must
        // read as MISSING rather than as a collection that happens to contain one absent entry.
        val output = failureOf(
            dir,
            withARealTest(dir) + """

            val guard = tasks.register<Test>("probeGuard") {
                testClassesDirs = sourceSets["test"].output.classesDirs
                classpath = sourceSets["test"].runtimeClasspath
            }
            a12Guards.guardInputs(guard.get(), mapOf("a doc that was deleted" to file("no-such-file.md")))
            """.trimIndent(),
            "probeGuard",
        )
        assertTrue(output.contains("a doc that was deleted"), output)
        assertTrue(output.contains("EMPTY or MISSING"), output)
    }

    @Test
    fun `a runtime read no declared input covers is refused`(@TempDir dir: File) {
        dir.resolve("declared").mkdirs()
        dir.resolve("declared/covered.md").writeText("x")
        // The observation file is written between the guard's own doFirst (which clears the directory) and
        // its doLast (which judges it), which is where a real guard's JVM writes it. `actions.add(1, …)` is
        // the only way to land there: a later `doFirst` PREPENDS and would be wiped, a later `doLast` runs
        // after the judgement.
        val output = failureOf(
            dir,
            withARealTest(dir) + """

            val guard = tasks.register<Test>("probeGuard") {
                testClassesDirs = sourceSets["test"].output.classesDirs
                classpath = sourceSets["test"].runtimeClasspath
            }
            a12Guards.guardInputs(guard.get(), mapOf("declared" to fileTree("declared")))
            val observed = File(guard.get().temporaryDir, "observed-reads")
            guard.get().actions.add(1, Action<Task> {
                observed.mkdirs()
                File(observed, "reads").writeText("declared/covered.md\ndeclaredX/sibling.md\n")
            })
            """.trimIndent(),
            "probeGuard",
        )
        assertTrue(output.contains("declaredX/sibling.md"), output)
        assertTrue(output.contains("green while checking nothing"), output)
        assertTrue(!output.contains("  declared/covered.md"), "a covered read must not be reported: $output")
    }

    @Test
    fun `an observation left by an earlier run is cleared before the guard is judged`(@TempDir dir: File) {
        // The clear is what makes the judgement's own comment true — a doLast can only ever see the
        // directory its own doFirst just cleared. Without a stale observation to leak, no probe can tell a
        // clearing guard from a non-clearing one, so this one plants the stale entry at configuration time
        // and asserts the build SUCCEEDS: the entry names a path nothing declares, so surviving the clear
        // would refuse a legitimate run.
        dir.resolve("declared").mkdirs()
        dir.resolve("declared/covered.md").writeText("x")
        val output = project(
            dir,
            withARealTest(dir) + """

            val guard = tasks.register<Test>("probeGuard") {
                testClassesDirs = sourceSets["test"].output.classesDirs
                classpath = sourceSets["test"].runtimeClasspath
            }
            a12Guards.guardInputs(guard.get(), mapOf("declared" to fileTree("declared")))
            val stale = File(guard.get().temporaryDir, "observed-reads")
            stale.mkdirs()
            File(stale, "reads").writeText("left-over-from-an-earlier-run/thing.md\n")
            """.trimIndent(),
        ).withArguments("probeGuard").build().output
        assertTrue(output.contains("BUILD SUCCESSFUL"), output)
    }

    @Test
    fun `an owned class is kept OUT of the broad lane while an unowned one still runs`(@TempDir dir: File) {
        // The derivation's actual job, and the one thing every refusal above leaves unchecked: a mutant that
        // excludes nothing keeps every refusal correct and simply stops doing the work. Each test records
        // that it ran, so the assertion is about what EXECUTED rather than about what was configured — the
        // liveness half matters as much, or "excluded everything" would pass too.
        val sources = dir.resolve("src/test/java/probe")
        sources.mkdirs()
        listOf("OwnedTest" to "owned", "FreeTest" to "free").forEach { (name, marker) ->
            sources.resolve("$name.java").writeText(
                """
                package probe;
                class $name {
                    @org.junit.jupiter.api.Test void runs() throws java.io.IOException {
                        java.nio.file.Files.createDirectories(java.nio.file.Path.of("markers"));
                        java.nio.file.Files.writeString(java.nio.file.Path.of("markers", "$marker"), "ran");
                    }
                }
                """.trimIndent(),
            )
        }
        project(
            dir,
            withARealTest(dir) + """

            tasks.register<Test>("ownerLane") {
                testClassesDirs = sourceSets["test"].output.classesDirs
                classpath = sourceSets["test"].runtimeClasspath
                filter { includeTestsMatching("probe.OwnedTest") }
            }
            a12Guards.routeExclusivelyHostedTests(tasks.named<Test>("test").get(), listOf("ownerLane"))
            """.trimIndent(),
        ).withArguments("test").build()

        assertTrue(dir.resolve("markers/free").isFile, "an unowned class must still run in the broad lane")
        assertTrue(
            !dir.resolve("markers/owned").isFile,
            "the owned class ran in the broad lane, so the derivation excluded nothing",
        )
    }

    @Test
    fun `an owned class that actually executes in the broad lane is refused`(@TempDir dir: File) {
        // The EFFECT assertion the text-matching guards could not make, and the one the docs single out.
        // Reaching it needs the exclusion defeated after the derivation has run — the listener and the
        // doLast survive, the owned class executes in the broad lane, and the leak is what fails the build.
        val output = failureOf(
            dir,
            withARealTest(dir) + """

            tasks.register<Test>("ownerLane") {
                testClassesDirs = sourceSets["test"].output.classesDirs
                classpath = sourceSets["test"].runtimeClasspath
                filter { includeTestsMatching("probe.ProbeTest") }
            }
            a12Guards.routeExclusivelyHostedTests(tasks.named<Test>("test").get(), listOf("ownerLane"))
            tasks.named<Test>("test") { filter.setExcludePatterns() }
            """.trimIndent(),
            "test",
        )
        assertTrue(output.contains("class(es) a dedicated lane owns exclusively"), output)
        assertTrue(output.contains("probe.ProbeTest"), output)
    }

    @Test
    fun `a failing guard is not also accused of an undeclared read`(@TempDir dir: File) {
        // Where the judgement lives is the acceptance item here, not merely that it judges. It is in the
        // task's own doLast rather than a finalizer, so it can only ever see the directory its own doFirst
        // just cleared. A finalizer runs even when its guard is skipped or red, and then reports on an
        // observation directory left by an EARLIER build — a false refusal, and a guard that refuses
        // justified work is worse than none. So: a red guard fails as a red guard.
        dir.resolve("declared").mkdirs()
        dir.resolve("declared/covered.md").writeText("x")
        val source = dir.resolve("src/test/java/probe/FailingTest.java")
        source.parentFile.mkdirs()
        source.writeText(
            """
            package probe;
            class FailingTest {
                @org.junit.jupiter.api.Test void fails() { throw new AssertionError("deliberate"); }
            }
            """.trimIndent(),
        )
        val output = failureOf(
            dir,
            withARealTest(dir) + """

            val guard = tasks.register<Test>("probeGuard") {
                testClassesDirs = sourceSets["test"].output.classesDirs
                classpath = sourceSets["test"].runtimeClasspath
            }
            a12Guards.guardInputs(guard.get(), mapOf("declared" to fileTree("declared")))
            val observed = File(guard.get().temporaryDir, "observed-reads")
            guard.get().actions.add(1, Action<Task> {
                observed.mkdirs()
                File(observed, "reads").writeText("declaredX/sibling.md\n")
            })
            """.trimIndent(),
            "probeGuard",
        )
        assertTrue(output.contains("There were failing tests"), output)
        assertTrue(!output.contains("green while checking nothing"),
            "a red guard must fail as a red guard, not be accused of an undeclared read too: " + output)
    }

    @Test
    fun `an exclusive owner whose filter includes nothing is refused`(@TempDir dir: File) {
        val output = failureOf(
            dir,
            """
            val owner = tasks.register<Test>("ownerLane") { }
            a12Guards.routeExclusivelyHostedTests(tasks.named<Test>("test").get(), listOf("ownerLane"))
            """.trimIndent(),
            "help",
        )
        assertTrue(output.contains("'ownerLane' is declared an exclusive owner"), output)
    }

    @Test
    fun `two lanes claiming one class is refused`(@TempDir dir: File) {
        val output = failureOf(
            dir,
            """
            tasks.register<Test>("laneA") { filter { includeTestsMatching("a.Shared") } }
            tasks.register<Test>("laneB") { filter { includeTestsMatching("a.Shared") } }
            a12Guards.routeExclusivelyHostedTests(
                tasks.named<Test>("test").get(), listOf("laneA", "laneB"))
            """.trimIndent(),
            "help",
        )
        assertTrue(output.contains("claimed as exclusively owned by both"), output)
    }

    @Test
    fun `an unclassified filtered lane over the same output is refused`(@TempDir dir: File) {
        val output = failureOf(
            dir,
            """
            tasks.register<Test>("ownerLane") { filter { includeTestsMatching("a.Owned") } }
            val stray = tasks.register<Test>("strayLane") {
                testClassesDirs = sourceSets["test"].output.classesDirs
                filter { includeTestsMatching("a.Stray") }
            }
            stray.get()
            a12Guards.routeExclusivelyHostedTests(tasks.named<Test>("test").get(), listOf("ownerLane"))
            """.trimIndent(),
            "help",
        )
        assertTrue(output.contains("'strayLane'"), output)
        assertTrue(output.contains("neither an exclusive owner nor a classified non-owner"), output)
    }

    @Test
    fun `a real forbidden compiled dependency is refused, naming the type`(@TempDir dir: File) {
        // Over genuinely compiled classes and a real jdeps run, so this covers the whole chain the unit
        // tests only cover in pieces: the scan, the parse, the positive control and the verdict.
        val output = failureOf(
            dir,
            withARealTest(dir) + """

            tasks.register<io.github.mbackschat.a12.dm.buildlogic.VerifyCompiledPurityTask>("probePurity") {
                dependsOn(tasks.named("testClasses"))
                guardName = "probe purity"
                classRoots.from(sourceSets["test"].output.classesDirs)
                forbidden = listOf("org.junit.")
                positiveControl = "java.lang.Object"
                javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
                stamp = layout.buildDirectory.file("probe-purity.txt")
            }
            """.trimIndent(),
            "probePurity",
        )
        assertTrue(output.contains("probe purity violated"), output)
        assertTrue(output.contains("org.junit.jupiter.api.Test"), output)
    }

    @Test
    fun `a --tests selection reaching only owned classes names the lane that hosts them`(@TempDir dir: File) {
        val output = failureOf(
            dir,
            withARealTest(dir) + """

            tasks.register<Test>("ownerLane") {
                testClassesDirs = sourceSets["test"].output.classesDirs
                classpath = sourceSets["test"].runtimeClasspath
                filter { includeTestsMatching("probe.ProbeTest") }
            }
            a12Guards.routeExclusivelyHostedTests(tasks.named<Test>("test").get(), listOf("ownerLane"))
            """.trimIndent(),
            "test", "--tests", "probe.ProbeTest",
        )
        assertTrue(output.contains("deliberately excludes"), output)
        assertTrue(output.contains("./build.sh :ownerLane --tests 'probe.ProbeTest'"), output)
    }

    @Test
    fun `a build-tooling classification naming a lane that owns nothing is refused`(@TempDir dir: File) {
        val output = failureOf(
            dir,
            """
            tasks.register<Test>("ownerLane") { filter { includeTestsMatching("a.Owned") } }
            a12Guards.routeExclusivelyHostedTests(
                tasks.named<Test>("test").get(), listOf("ownerLane"), mapOf(),
                mapOf("ghostLane" to "invokes javac, so its traffic is build tooling"))
            """.trimIndent(),
            "help",
        )
        assertTrue(output.contains("'ghostLane'"), output)
        assertTrue(output.contains("owns nothing in"), output)
    }

    @Test
    fun `a capture re-host that drops an owner's system property is refused, naming it`(@TempDir dir: File) {
        // The one absorption failure that actually shipped: the traced lane ran an owned lane's classes
        // without the system property that lane declares, and 13 packaged-launcher cases exec'd a directory.
        // It fires only under -PrkNativeAgent, which is why it needs its own probe rather than riding another.
        val output = failureOf(
            dir,
            """
            tasks.register<Test>("ownerLane") {
                filter { includeTestsMatching("a.Owned") }
                systemProperty("a12.probe.executable", "/somewhere/dmtool")
            }
            a12Guards.routeExclusivelyHostedTests(tasks.named<Test>("test").get(), listOf("ownerLane"))
            """.trimIndent(),
            "help", "-PrkNativeAgent",
        )
        assertTrue(output.contains("a12.probe.executable"), output)
        assertTrue(output.contains("shrinks the captured metadata silently"), output)
    }

    @Test
    fun `a purity scan that never sees its positive control is refused`(@TempDir dir: File) {
        // Guards the case where jdeps silently inspected nothing useful — a wrong root, a changed option, a
        // filtered output. Absence of violations is then indistinguishable from a clean scan.
        val output = failureOf(
            dir,
            withARealTest(dir) + """

            tasks.register<io.github.mbackschat.a12.dm.buildlogic.VerifyCompiledPurityTask>("probePurity") {
                dependsOn(tasks.named("testClasses"))
                guardName = "probe purity"
                classRoots.from(sourceSets["test"].output.classesDirs)
                forbidden = listOf("com.example.")
                positiveControl = "no.such.package.AtAll"
                javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
                stamp = layout.buildDirectory.file("probe-purity.txt")
            }
            """.trimIndent(),
            "probePurity",
        )
        assertTrue(output.contains("did not see its compiled-reference positive control no.such.package.AtAll"),
            output)
    }

    @Test
    fun `a purity scan over no class files is refused rather than reported clean`(@TempDir dir: File) {
        val output = failureOf(
            dir,
            """
            tasks.register<io.github.mbackschat.a12.dm.buildlogic.VerifyCompiledPurityTask>("probePurity") {
                guardName = "probe purity"
                classRoots.from(layout.projectDirectory.dir("no-classes-here"))
                forbidden = listOf("com.example.")
                positiveControl = "java.lang.Object"
                javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
                stamp = layout.buildDirectory.file("probe-purity.txt")
            }
            """.trimIndent(),
            "probePurity",
        )
        assertTrue(output.contains("probe purity scanned ZERO class files"), output)
    }
}
