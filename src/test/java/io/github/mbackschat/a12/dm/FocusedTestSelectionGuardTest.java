package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FocusedTestSelectionGuardTest {

    @Test
    void filteredBroadTasksRefuseBeforePrerequisitesButExplicitSelectionsRun(@TempDir Path dir) throws Exception {
        Path policy = Path.of(System.getProperty("focusedTestPolicy")).toAbsolutePath();
        Result live = run(policy.getParent().getParent(), ":test", "--tests", "*Focused", "--dry-run");
        assertNotEquals(0, live.code(), live::output);
        assertTrue(live.output().contains("requires -Pfast"), live::output);
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'selection-control'\ninclude 'child'\n");
        Files.createDirectories(dir.resolve("child"));
        Files.writeString(dir.resolve("build.gradle"), """
                allprojects {
                    apply plugin: 'java'
                    tasks.register('expensiveGuard') {
                        doLast { rootProject.file('prerequisite-ran').text = 'ran' }
                    }
                    tasks.named('test') {
                        if (!project.hasProperty('fast')) dependsOn 'expensiveGuard'
                    }
                    tasks.register('namedGuard', Test)
                }
                apply from: '%s'
                """.formatted(policy.toString().replace("\\", "\\\\").replace("'", "\\'")));

        for (String task : List.of(":test", ":child:test")) {
            Result refused = run(dir, task, "--tests", "*Focused");
            assertNotEquals(0, refused.code(), refused::output);
            assertTrue(refused.output().contains("-Pfast"), refused::output);
            assertFalse(Files.exists(dir.resolve("prerequisite-ran")), refused::output);
        }
        Result fast = run(dir, ":test", "--tests", "*Focused", "-Pfast");
        assertEquals(0, fast.code(), fast::output);
        assertFalse(Files.exists(dir.resolve("prerequisite-ran")), fast::output);

        Result named = run(dir, ":namedGuard", "--tests", "*Focused");
        assertEquals(0, named.code(), named::output);

        Result full = run(dir, ":test");
        assertEquals(0, full.code(), full::output);
        assertTrue(Files.exists(dir.resolve("prerequisite-ran")), full::output);
        Files.delete(dir.resolve("prerequisite-ran"));

        Result capture = run(dir, ":test", "--tests", "*Focused", "-PrkNativeAgent");
        assertEquals(0, capture.code(), capture::output);
        assertTrue(Files.exists(dir.resolve("prerequisite-ran")), capture::output);
    }

    /**
     * Gradle binds {@code --tests} to the task just before it, so {@code :test :child:test --tests X} filters
     * {@code :child:test} only and runs the whole root suite beside it. Every filtered root run in one 2026-10-20
     * session paid for the full suite this way, unnoticed because the targeted classes passed among the rest.
     * Giving each task its own filter is the control, and a task bounded by its own script filter or JUnit tags
     * is a lane that is meant to run whole. A project-relative task path names the same task as its absolute one.
     */
    @Test
    void aSuiteLeftUnfilteredBesideAFilteredOneIsRefused(@TempDir Path dir) throws Exception {
        Path policy = Path.of(System.getProperty("focusedTestPolicy")).toAbsolutePath();
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'binding-control'\ninclude 'child'\n");
        Files.createDirectories(dir.resolve("child"));
        Files.writeString(dir.resolve("build.gradle"), """
                allprojects {
                    apply plugin: 'java'
                    tasks.register('boundedGuard', Test) { filter.includeTestsMatching 'BoundedGuardTest' }
                    tasks.register('taggedLane', Test) { useJUnitPlatform { includeTags 'perf' } }
                }
                apply from: '%s'
                """.formatted(policy.toString().replace("\\", "\\\\").replace("'", "\\'")));

        Result refused = run(dir, ":test", ":child:test", "--tests", "*Focused", "-Pfast", "--dry-run");
        assertNotEquals(0, refused.code(), refused::output);
        assertTrue(refused.output().contains("binds --tests to the task just before it"), refused::output);
        assertTrue(refused.output().contains(":test would run unfiltered"), refused::output);
        Result relative = run(dir, ":test", "child:test", "--tests", "*Focused", "-Pfast", "--dry-run");
        assertNotEquals(0, relative.code(), relative::output);
        assertTrue(relative.output().contains(":test would run unfiltered"), relative::output);

        for (String[] allowed : List.of(
                new String[] {":test", "--tests", "*Focused", ":child:test", "--tests", "*Focused", "-Pfast", "--dry-run"},
                new String[] {":boundedGuard", ":child:test", "--tests", "*Focused", "-Pfast", "--dry-run"},
                new String[] {":taggedLane", ":child:test", "--tests", "*Focused", "-Pfast", "--dry-run"},
                new String[] {":test", ":child:test", "--dry-run"})) {
            Result result = run(dir, allowed);
            assertEquals(0, result.code(), result::output);
        }
    }

    /**
     * A JVM task with {@code failOnNoMatchingTests} switched off stands in for a Kotlin/JS test task, which
     * ignores that switch, so only the policy can fail the empty selection; the matching filter is the control
     * that proves the task runs at all.
     */
    @Test
    void aFilterThatSelectsNoTestFailsWhereTheTaskItselfWouldNot(@TempDir Path dir) throws Exception {
        Path policy = Path.of(System.getProperty("focusedTestPolicy")).toAbsolutePath();
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'empty-selection-control'\n");
        Files.writeString(dir.resolve("build.gradle"), """
                apply plugin: 'java'
                repositories { mavenCentral() }
                dependencies {
                    testImplementation platform('org.junit:junit-bom:5.11.4')
                    testImplementation 'org.junit.jupiter:junit-jupiter'
                    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
                }
                tasks.withType(Test).configureEach {
                    useJUnitPlatform()
                    filter.failOnNoMatchingTests = false
                }
                tasks.register('scriptFiltered', Test) {
                    testClassesDirs = sourceSets.test.output.classesDirs
                    classpath = sourceSets.test.runtimeClasspath
                    filter.includeTestsMatching 'NoSuch*'
                }
                apply from: '%s'
                """.formatted(policy.toString().replace("\\", "\\\\").replace("'", "\\'")));
        Files.createDirectories(dir.resolve("src/test/java"));
        Files.writeString(dir.resolve("src/test/java/ProbeTest.java"),
                "class ProbeTest { @org.junit.jupiter.api.Test void runs() { } }\n");

        Result matching = run(dir, ":test", "--tests", "*ProbeTest", "-Pfast");
        assertEquals(0, matching.code(), matching::output);
        for (String[] empty : List.of(new String[] {":test", "--tests", "NoSuch*", "-Pfast"},
                new String[] {":scriptFiltered"})) {
            Result refused = run(dir, empty);
            assertNotEquals(0, refused.code(), refused::output);
            assertTrue(refused.output().contains("selected no test"), refused::output);
        }
    }

    private static Result run(Path dir, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(System.getProperty("gradleExecutable"),
                "--offline", "--console=plain", "--max-workers=1", "-p", dir.toString()));
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile("focused-selection-", ".log");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor();
                throw new AssertionError("selection probe exceeded 30 seconds: " + Files.readString(log));
            }
            return new Result(process.exitValue(), Files.readString(log));
        } finally {
            Files.deleteIfExists(log);
        }
    }

    private record Result(int code, String output) { }
}
