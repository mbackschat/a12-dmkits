package io.github.mbackschat.a12.dm.adapter.capture.packet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;

/**
 * The capture source state bound into every packet (proposal §2/§7): the a12-dmkits revision, whether the
 * working tree is CLEAN or DIRTY, and a classified tree-state digest (revision + status + diff hashed) —
 * the same digest the mutation receipts use to prove exact-bytes restoration. Clean capture requires a
 * clean committed checkout; {@code --development} runs against modified source, records the dirty state,
 * and is permanently qualification-ineligible.
 */
public record SourceState(String revision, boolean clean, String treeStateDigest) {

    public String workingTreeWire() {
        return clean ? "clean" : "dirty";
    }

    /** The probe seam — the real one runs git; tests inject fixed states to cover both refusal branches. */
    public interface Probe {
        SourceState state();
    }

    /** The real git probe over {@code repoRoot}. */
    public static Probe gitProbe(Path repoRoot) {
        return () -> {
            String revision = git(repoRoot, "rev-parse", "HEAD").trim();
            String status = git(repoRoot, "status", "--porcelain");
            String diff = git(repoRoot, "diff", "HEAD");
            String digest = Digests.sha256((revision + "\n" + status + "\n" + diff)
                    .getBytes(StandardCharsets.UTF_8));
            return new SourceState(revision, status.isBlank(), digest);
        };
    }

    private static String git(Path repoRoot, String... args) {
        try {
            String[] command = new String[args.length + 1];
            command[0] = "git";
            System.arraycopy(args, 0, command, 1, args.length);
            Process process = new ProcessBuilder(command)
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " failed (" + exit + "): "
                        + output);
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("git probe failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("git probe interrupted", e);
        }
    }
}
