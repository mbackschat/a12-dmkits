package io.github.mbackschat.a12.rulekit.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;

/**
 * Bridges picocli's {@link Writer}-based output to the {@link Cli#run} seam's {@link Appendable} sinks
 * (a {@code StringBuilder} in tests, {@code System.out}/{@code err} in the process). Keeps the pure,
 * in-process-testable seam (CLI-SPEC §2/§3) intact under picocli.
 */
final class AppendableWriter extends Writer {

    private final Appendable target;

    AppendableWriter(Appendable target) {
        this.target = target;
    }

    @Override
    public void write(char[] cbuf, int off, int len) {
        try {
            target.append(new String(cbuf, off, len));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void flush() {
        // the underlying Appendable (StringBuilder / PrintStream) buffers nothing we own
    }

    @Override
    public void close() {
        // the seam owns the sink's lifecycle, not this adapter
    }
}
