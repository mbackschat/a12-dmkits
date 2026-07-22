package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Shared process seam for tests that need a real temporary Git history. */
final class GitTestSupport {

    private GitTestSupport() {}

    static void run(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(),
                () -> "git " + String.join(" ", arguments) + " failed: " + output);
    }
}
