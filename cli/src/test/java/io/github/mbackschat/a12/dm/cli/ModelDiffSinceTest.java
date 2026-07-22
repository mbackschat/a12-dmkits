package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code model diff --since <ref>} — the git convenience (MODEL-REVIEW-SPEC §1/§9c): diff the model's
 * current working file (HEAD) against its content at a git {@code <ref>} (BASE), so the common review
 * question "what did the agent change since HEAD?" is one command, not a manual {@code git show} + diff.
 * The diff engine stays git-free; only the CLI materializes the baseline.
 */
class ModelDiffSinceTest {

    @Test
    void diffsTheCurrentFileAgainstAGitRef(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, resource("models/subscription.dm.json"));
        GitTestSupport.run(dir, "init", "-q");
        GitTestSupport.run(dir, "add", "subscription.dm.json");
        GitTestSupport.run(dir, "-c", "user.email=t@example.com", "-c", "user.name=Test",
                "commit", "-qm", "base");

        // the agent's edit, in place: the same model + one added rule
        Files.writeString(model, CliTestSupport.modelWithRuleJson());

        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "-w", dir.resolve("unused").toString(),
                "model", "diff", "--since", "HEAD");
        assertEquals(0, res.code(), res::err);
        assertTrue(res.out().contains("RULE_ADDED"),
                () -> "the rule added since HEAD must show as RULE_ADDED: " + res.out());
    }

    @Test
    void sinceWithoutAModelFails() {
        CliTestSupport.Result res = CliTestSupport.run("model", "diff", "--since", "HEAD");
        assertEquals(2, res.code());
        assertTrue(res.err().contains("-m"), () -> "names the missing -m: " + res.err());
    }

    @Test
    void sinceOutsideAGitRepoFails(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("m.dm.json");
        Files.writeString(model, resource("models/subscription.dm.json"));
        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "model", "diff", "--since", "HEAD");
        assertEquals(2, res.code());
        assertTrue(res.err().toLowerCase().contains("git") || res.err().toLowerCase().contains("repositor"),
                () -> "explains it is not a git repo: " + res.err());
    }

    @Test
    void sinceRejectsPositionalFilesToo() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run(
                "-m", "x.dm.json", "model", "diff", "--since", "HEAD", "a.dm.json", "b.dm.json");
        assertEquals(2, res.code());
        assertTrue(res.err().toLowerCase().contains("since"), () -> "explains the mutual exclusion: " + res.err());
    }

    @Test
    void sinceRejectsSideSpecificWorkspaceRoots() {
        CliTestSupport.Result res = CliTestSupport.run("-m", "x.dm.json", "model", "diff", "--since", "HEAD",
                "--base-workspace", "old");
        assertEquals(2, res.code());
        assertTrue(res.err().contains("two-file mode"), () -> "explains which mode owns the option: " + res.err());
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = ModelDiffSinceTest.class.getClassLoader().getResourceAsStream(path)) {
            assertTrue(in != null, "missing test resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
