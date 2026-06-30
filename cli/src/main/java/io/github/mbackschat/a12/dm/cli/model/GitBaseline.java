package io.github.mbackschat.a12.dm.cli.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Materializes a model file's content at a git {@code <ref>} — the baseline for {@code model diff --since}
 * (MODEL-REVIEW-SPEC §9c: the one deliberate git touch in an otherwise git-free tool). It shells to
 * {@code git} (so it needs git on PATH and the model inside a working tree) and reports a clear
 * {@link GitBaselineException} on every failure mode the caller turns into a corrective message.
 */
final class GitBaseline {

    private GitBaseline() {}

    /** A git-side failure resolving the baseline (not a repo, bad ref, file absent at ref, git missing). */
    static final class GitBaselineException extends RuntimeException {
        GitBaselineException(String message) {
            super(message);
        }
    }

    /** The content of {@code modelFile} as committed at {@code ref} (e.g. {@code HEAD}). */
    static String contentAt(Path modelFile, String ref) {
        Path abs = modelFile.toAbsolutePath().normalize();
        Path dir = abs.getParent();
        // the model's path RELATIVE TO THE REPO ROOT — git's `<ref>:<path>` pathspec is root-relative,
        // and --show-prefix sidesteps macOS /tmp→/private symlink mismatches that path relativizing hits.
        String prefix = run(dir, "the model is not in a git repository (cannot resolve --since)",
                "rev-parse", "--show-prefix").trim();
        String relPath = prefix + abs.getFileName();
        return run(dir, "cannot read " + relPath + " at git ref '" + ref + "'", "show", ref + ":" + relPath);
    }

    private static String run(Path cwd, String failHint, String... gitArgs) {
        String[] cmd = new String[gitArgs.length + 3];
        cmd[0] = "git";
        cmd[1] = "-C";
        cmd[2] = cwd.toString();
        System.arraycopy(gitArgs, 0, cmd, 3, gitArgs.length);
        Process process;
        try {
            process = new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            throw new GitBaselineException("git is not available on PATH (needed for --since)");
        }
        try {
            byte[] out = process.getInputStream().readAllBytes();
            byte[] err = process.getErrorStream().readAllBytes();
            int code = process.waitFor();
            if (code != 0) {
                String detail = new String(err, StandardCharsets.UTF_8).trim();
                throw new GitBaselineException(failHint + (detail.isEmpty() ? "" : " — " + detail));
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GitBaselineException("git baseline read failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitBaselineException("interrupted resolving the git baseline");
        }
    }
}
