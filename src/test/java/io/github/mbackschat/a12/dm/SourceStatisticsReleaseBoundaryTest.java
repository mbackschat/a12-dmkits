package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Keeps maintainer-only locmetry at the local publisher boundary, never in portable assembly. */
class SourceStatisticsReleaseBoundaryTest {

    private static final String DMTOOL_CHECK =
            "./scripts/update-code-statistics.sh --check --artifact dmtool";
    private static final String INTERPRETER_CHECK =
            "\"$source_root/scripts/update-code-statistics.sh\" --check --artifact interpreter";

    @Test
    void bothPublishersCheckFreshCountsBeforeCreatingPublicState() throws IOException {
        String dmtool = read("scripts/publish-release.sh");
        assertTrue(precedes(dmtool, DMTOOL_CHECK, "gh auth status"),
                "dmtool must check source statistics before reaching its first public precondition");
        assertFalse(precedes(dmtool.replace(DMTOOL_CHECK, ""), DMTOOL_CHECK, "gh auth status"),
                "the dmtool discriminator must fail when the check is removed");

        String interpreter = function(read("scripts/interpreter-release-publisher-core.sh"),
                "interpreter_release_qualify() {", "interpreter_release_stage() {");
        String receipt = "interpreter_release_write_qualification_receipt \"$receipt\"";
        assertTrue(precedes(interpreter, INTERPRETER_CHECK, receipt),
                "interpreter qualification must check source statistics before writing its receipt");
        assertFalse(precedes(interpreter.replace(INTERPRETER_CHECK, ""), INTERPRETER_CHECK, receipt),
                "the interpreter discriminator must fail when the check is removed");
    }

    @Test
    void portableAssemblersDoNotRegenerateSourceStatistics() throws IOException {
        assertFalse(read("scripts/assemble-release-repo.sh").contains("update-code-statistics.sh"));
        assertFalse(read("scripts/assemble-interpreter-release-repo.sh").contains("update-code-statistics.sh"));
    }

    @Test
    void publicSourceStatisticsExcludeTheSiblingLeanProject() throws IOException {
        String updater = read("scripts/update-code-statistics.sh")
                + read("scripts/typescript/update-code-statistics.ts");
        assertFalse(updater.contains("A12_LEAN_REPO"));
        assertFalse(updater.contains("| Lean |"));
        for (String readme : List.of(
                "README.md",
                "plugin/README.md",
                "interpreter/README.md",
                "interpreter/public-release/README.md")) {
            assertFalse(read(readme).contains("| Lean |"), readme + " must not report Lean statistics");
        }
    }

    @Test
    void dmtoolAssemblyPublishesTheCanonicalCountingPolicy(@TempDir Path temporary) throws Exception {
        Path root = temporary.resolve("source");
        Path kernel = temporary.resolve("a12-kernel");
        Files.createDirectories(kernel);
        for (String name : List.of("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES", "licenses/EUPL-1.2.txt")) {
            write(kernel.resolve(name), "synthetic notice\n");
        }
        run(kernel, "git", "init", "-q");
        run(kernel, "git", "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                "commit", "--allow-empty", "-qm", "Kernel 1.2.3");
        for (var entry : Map.ofEntries(
                Map.entry("build.gradle", "kernelVersion = '1.2.3'\na12Distribution = '2030.01'\n"),
                Map.entry(".locmetryrc", read(".locmetryrc")),
                Map.entry("plugin/README.md", "1.2.3 2030.01\n[Policy](.locmetryrc)\n"),
                Map.entry("plugin/CHANGELOG.md", "1.2.3 2030.01\n"),
                Map.entry("plugin/.claude-plugin/plugin.json", "{\"kernel\":\"1.2.3\",\"tools\":\"2030.01\"}"),
                Map.entry("plugin-codex/.codex-plugin/plugin.json", "{}"),
                Map.entry("plugin-codex/AGENTS.md", "Fixture guidance\n"),
                Map.entry("plugin-codex/marketplace.json", "{}"),
                Map.entry("examples/README.md", "Examples\n"),
                Map.entry("examples/cli-example.md", "Example\n"),
                Map.entry(".github/mirror-workflows/build-native.yml", "name: Fixture\n"),
                Map.entry("scripts/gen-scenarios.sh", "#!/bin/sh\nprintf 'Scenarios\\n'\n"),
                Map.entry("scripts/assemble-release-repo.sh", read("scripts/assemble-release-repo.sh"))).entrySet()) {
            write(root.resolve(entry.getKey()), entry.getValue());
        }
        for (String directory : List.of("plugin-codex/skills", "examples/models")) {
            Files.createDirectories(root.resolve(directory));
        }
        assertTrue(root.resolve("scripts/gen-scenarios.sh").toFile().setExecutable(true));
        Path mirror = temporary.resolve("mirror");
        run(root, "bash", root.resolve("scripts/assemble-release-repo.sh").toString(), mirror.toString());
        assertEquals(read(".locmetryrc"), Files.readString(mirror.resolve(".locmetryrc")));
    }

    private static void write(Path file, String contents) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }

    private static void run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private static boolean precedes(String text, String first, String second) {
        int firstIndex = text.indexOf(first);
        int secondIndex = text.indexOf(second);
        return firstIndex >= 0 && secondIndex >= 0 && firstIndex < secondIndex;
    }

    private static String function(String text, String start, String end) {
        int startIndex = text.indexOf(start);
        int endIndex = text.indexOf(end, startIndex + start.length());
        return text.substring(startIndex, endIndex);
    }

    private static String read(String relative) throws IOException {
        for (String base : List.of(".", "..", "../..")) {
            Path candidate = Path.of(base, relative);
            if (Files.exists(candidate)) {
                return Files.readString(candidate);
            }
        }
        throw new IllegalStateException(relative + " not found from " + Path.of("").toAbsolutePath());
    }
}
