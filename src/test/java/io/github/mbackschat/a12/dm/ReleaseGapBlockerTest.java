package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proves that publication is blocked by any live open gap, while deferred work remains non-blocking. */
class ReleaseGapBlockerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void everyDerivedLedgerMustHaveADrainedOpenSection() throws IOException, InterruptedException {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"),
                "POSIX-only release guard");
        Path docs = temporaryDirectory.resolve("docs");
        Files.createDirectories(docs);
        writeLedger(docs.resolve("A-GAPS.md"), "_None open._", "### A1 — deferred work");
        writeLedger(docs.resolve("B-GAPS.md"), "_None open._", "_None deferred._");

        Result drained = run(docs);
        assertEquals(0, drained.code(), drained.output());

        writeLedger(docs.resolve("NEW-GAPS.md"), "### N1 — a live release blocker", "_None deferred._");
        Result open = run(docs);
        assertNotEquals(0, open.code(), "a newly discovered ledger with live work must block publication");
        assertTrue(open.output().contains("NEW-GAPS.md"), open.output());

        Files.delete(docs.resolve("NEW-GAPS.md"));
        Result deferredOnly = run(docs);
        assertEquals(0, deferredOnly.code(), "deferred rows outside the open section must not block publication");
    }

    @Test
    void anEmptyLedgerDenominatorCannotPass() throws IOException, InterruptedException {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"),
                "POSIX-only release guard");
        Path docs = temporaryDirectory.resolve("empty-docs");
        Files.createDirectories(docs);

        Result result = run(docs);
        assertNotEquals(0, result.code(), "a release guard that scans no ledgers proves nothing");
        assertTrue(result.output().contains("no *-GAPS.md ledgers"), result.output());
    }

    private static void writeLedger(Path path, String open, String deferred) throws IOException {
        Files.writeString(path, """
                # test — gaps

                ## 1. Open

                %s

                ## 2. Deferred

                %s
                """.formatted(open, deferred));
    }

    private static Result run(Path docs) throws IOException, InterruptedException {
        Path script = repoFile("scripts/check-release-gaps.sh");
        Process process = new ProcessBuilder("bash", script.toString(), docs.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        return new Result(process.waitFor(), output);
    }

    private static Path repoFile(String relative) {
        for (String base : List.of(".", "..", "../..")) {
            Path candidate = Path.of(base, relative);
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException(relative + " not found from " + Path.of("").toAbsolutePath());
    }

    private record Result(int code, String output) {}
}
