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
