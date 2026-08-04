package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Black-box checks for the exact source-provenance program invoked by the Gradle build. */
class SourceProvenanceBuildTest {

    @Test
    void identifiesOnlyItsOwnRepositoryAndCannotHideUntrackedSources(@TempDir Path dir) throws Exception {
        assertTrue(Files.readString(Path.of("build.gradle")).contains("scripts/SourceProvenance.java"),
                "the Gradle resource generator must invoke the black-box-tested provenance program");
        Path repository = dir.resolve("repository");
        Files.createDirectories(repository);
        git(repository, "init");
        git(repository, "-c", "user.name=dmtool-test", "-c", "user.email=dmtool@example.invalid",
                "commit", "--allow-empty", "-m", "initial");
        String revision = git(repository, "rev-parse", "HEAD").strip();

        assertEquals(new Provenance(revision, "CLEAN"), provenance(repository));

        Path nestedArchive = repository.resolve("unpacked-source");
        Files.createDirectories(nestedArchive);
        assertEquals(new Provenance("(unknown)", "UNKNOWN"), provenance(nestedArchive),
                "a source archive must not inherit an enclosing repository's HEAD");

        git(repository, "config", "status.showUntrackedFiles", "no");
        Files.writeString(repository.resolve("UntrackedSource.java"), "final class UntrackedSource {}\n");
        assertEquals(new Provenance(revision, "DIRTY"), provenance(repository),
                "the build must override Git config that hides untracked source files");
    }

    private static Provenance provenance(Path repository) throws Exception {
        Path program = Path.of("..", "scripts", "SourceProvenance.java").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(program), () -> "missing production provenance program " + program);
        String java = Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(java, program.toString(), repository.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        String[] lines = output.strip().split("\\R");
        assertEquals(2, lines.length, output);
        return new Provenance(lines[0], lines[1]);
    }

    private static String git(Path directory, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), () -> command + ": " + output);
        return output;
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private record Provenance(String revision, String state) { }
}
