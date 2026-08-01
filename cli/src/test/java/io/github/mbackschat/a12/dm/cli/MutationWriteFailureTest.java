package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A write that cannot reach its destination fails as an <b>environment</b> problem — not as an input rejection and
 * not as a consistency verdict.
 *
 * <p>Every model-editing verb ends with the same {@code Files.writeString} guarded by the same catch, and nothing
 * exercised it: the branch is reached only when the filesystem refuses, which no ordinary verb test arranges. It is
 * pinned here because the tail is about to be collapsed into one shared emitter, and the untested branch is exactly
 * the one a refactor can quietly change — the failure text, the exit code, or worse, the classification. An
 * unwritable path must not acquire the shape of a rejected model: the model was fine, the disk was not.
 *
 * <p>Two verbs rather than one, chosen from opposite ends of the surface (a structure edit that gates a
 * {@code ModelDocument}, a rule edit that re-binds a {@code BoundModel}) — enough to show the behaviour is the
 * tail's and not one command's.
 */
class MutationWriteFailureTest {

    /** A destination under a path component that is a regular file, so no directory can exist there. */
    private static Path unwritable(Path dir) throws Exception {
        Path blocker = dir.resolve("not-a-directory");
        Files.writeString(blocker, "x");
        return blocker.resolve("out.dm.json");
    }

    @Test
    void aStructureEditThatCannotWriteFailsWithoutClaimingTheModelIsBad(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);

        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(),
                "field", "add", "--group", "/Order", "--name", "PromoCode", "--kind", "STRING",
                "-o", unwritable(dir).toString());

        assertEquals(2, res.code(), () -> "an unwritable destination is a usage/environment failure: " + res.out());
        assertTrue(res.err().contains("cannot write output"),
                () -> "and it says so plainly on stderr: " + res.err() + res.out());
        assertTrue(res.out().isBlank(),
                () -> "stdout carries no envelope — nothing was applied, and no verdict was reached: " + res.out());
    }

    @Test
    void aRuleEditThatCannotWriteFailsTheSameWay(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);

        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(),
                "rule", "remove", "/Order/EligibilityCheck", "-o", unwritable(dir).toString());

        assertEquals(2, res.code(), () -> "same classification from a different verb family: " + res.out());
        assertTrue(res.err().contains("cannot write output"), res::err);
    }

    /** The model is untouched: a failed write leaves no half-edit behind, whichever destination was refused. */
    @Test
    void theSourceModelIsUnchangedAfterAFailedWrite(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        String before = Files.readString(model);

        CliTestSupport.run("-m", model.toString(),
                "field", "add", "--group", "/Order", "--name", "PromoCode", "--kind", "STRING",
                "-o", unwritable(dir).toString());

        assertEquals(before, Files.readString(model),
                "-o redirects the write; a failure there must not fall back to editing in place");
    }
}
