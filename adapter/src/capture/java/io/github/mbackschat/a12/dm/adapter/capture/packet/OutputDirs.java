package io.github.mbackschat.a12.dm.adapter.capture.packet;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The caller-owned-output contract shared by every writing command (proposal §7): an explicitly NEW or
 * EMPTY directory, never the committed corpus tree — capture, verify, and both diffs write only there and
 * never overwrite or mix outputs.
 */
public final class OutputDirs {

    private OutputDirs() {
    }

    public static void requireNewOrEmptyOutsideCorpus(Path outputDir, Path repoRoot) {
        Path out = outputDir.toAbsolutePath().normalize();
        Path corpus = repoRoot.toAbsolutePath().normalize().resolve("corpus");
        if (out.startsWith(corpus)) {
            throw new CaptureRefusedException("the output directory lies inside the committed corpus tree ("
                    + "corpus/) — capture NEVER writes into the corpus; promotion is a separate, reviewed,"
                    + " explicitly authorized corpus-specific action.");
        }
        if (Files.exists(out)) {
            if (!Files.isDirectory(out)) {
                throw new CaptureRefusedException("the output path exists and is not a directory — supply a"
                        + " NEW or EMPTY caller-owned directory.");
            }
            try (var entries = Files.list(out)) {
                if (entries.findAny().isPresent()) {
                    throw new CaptureRefusedException("the output directory is NOT empty — a writing command"
                            + " only uses a new or empty caller-owned directory (it never overwrites or mixes"
                            + " outputs).");
                }
            } catch (IOException e) {
                throw new UncheckedIOException("failed to inspect the output directory", e);
            }
        }
    }
}
