package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Invokes the public {@code scripts/mutant.sh} path through Dash, where the POSIX wrapper meets the TypeScript
 * runner. The verdict logic is locked by {@code scripts/typescript/mutant.test.ts}; this locks the seam, on the
 * refusals that need no Gradle run, and requires each to be exit 2 — no verdict — because the SURVIVED code 1
 * is the one a broken command would be believed as.
 */
class MutantScriptTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    @Test
    void withoutItsArgumentsTheCommandPrintsItsUsage() throws Exception {
        Run run = mutant();
        assertEquals(2, run.exit(), run.output());
        assertTrue(run.output().contains("usage: scripts/mutant.sh"), run.output());
    }

    @Test
    void anAnchorTheFileDoesNotCarryIsRefusedAndLeavesTheFileUntouched() throws Exception {
        Path license = CHECKOUT.requireFile("LICENSE");
        byte[] before = Files.readAllBytes(license);

        Run run = mutant("LICENSE", "a phrase this licence never contains", "x", "*AnyTest*");

        assertEquals(2, run.exit(), run.output());
        assertTrue(run.output().contains("NOT in the file"), run.output());
        assertArrayEquals(before, Files.readAllBytes(license), "a refused mutant must not touch the file");
    }

    @Test
    void aBatchEntryWithoutItsFourStringsIsRefusedBeforeAnyRun(@TempDir Path temporary) throws Exception {
        Path batch = temporary.resolve("mutants.json");
        Files.writeString(batch, "[{\"file\": \"LICENSE\", \"old\": \"a\", \"new\": 1, \"filter\": \"*T*\"}]");

        Run run = mutant("--batch", batch.toString());

        assertEquals(2, run.exit(), run.output());
        assertTrue(run.output().contains("entry 0: \"new\""), run.output());
    }

    private record Run(int exit, String output) {}

    private static Run mutant(String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(List.of("/bin/dash", "scripts/mutant.sh"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = CHECKOUT.command(command).redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(process.waitFor(), output);
    }
}
