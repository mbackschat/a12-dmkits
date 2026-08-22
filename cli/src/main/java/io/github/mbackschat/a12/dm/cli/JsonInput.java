package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Reads a JSON input a caller named on the command line — a file path, or <b>{@code -} for stdin</b>.
 *
 * <p><b>Why one shared reader.</b> Only the config-LESS field kinds can be authored with flags; TIME,
 * DATETIME, DATERANGE, DATEFRAGMENT, ENUM and CUSTOM carry per-kind config and so needed a spec file on disk,
 * which turned a scripted author into a loop with cleanup. {@code -} removes that without adding a second
 * authoring shape: the rich-spec schema stays the single source for the config, exactly as for the file form.
 * (A {@code --config <json>} flag was the alternative and is deliberately not built — it would duplicate that
 * schema as a parallel input surface on every call site.)
 *
 * <p><b>Scope, measured rather than asserted.</b> `-` is wired to the nine SPEC and OP-LIST inputs (the seven
 * authoring specs plus {@code apply}/{@code batch}). The CLI declares 18 JSON-file inputs in total, of which 8
 * are positionals; the remaining inbound ones — the document {@code --instance} on the three eval verbs, the
 * two {@code profile compare} operands, {@code profile synthesize --input}, {@code model diff}'s two model
 * files, and the global {@code -m} — are NOT wired, and that is a scope boundary, not a claim about the class.
 *
 * <p><b>stdin is the PROCESS's, and it is claimed once per process.</b> Two properties follow, and both were
 * defects in the first version of this class:
 * <ul>
 *   <li><b>The stream is never closed.</b> Closing a reader over {@code System.in} closes fd 0 for the whole
 *       process, which would end an {@code mcp} stdio session mid-conversation and starve any later reader.</li>
 *   <li><b>The claim survives nesting.</b> {@code batch} and the MCP invoker re-enter {@link Cli#run} per
 *       child, so resetting there would let a second {@code -} through — which is precisely the case the
 *       refusal exists for. The reset therefore lives at the process shell ({@code CliMain}), and a test
 *       harness calls {@link #resetForInvocation()} itself.</li>
 * </ul>
 * The claim is taken BEFORE the stream is consumed, so a read that fails part-way still blocks a retry rather
 * than handing the next caller a drained stream.
 */
public final class JsonInput {

    /** The stdin marker. Only this exact string; a file named {@code -} is still reachable by path. */
    public static final String STDIN = "-";

    /** The help sentence appended to every wired parameter — single-sourced so nine copies cannot drift. */
    public static final String HELP = " Or `-` to read the JSON from stdin instead of a file (once per process).";

    /**
     * Where stdin is read from. A seam, not a parameter — same reason as {@code McpCommand.stdinSource}: the
     * process supplies its own stdin, and a test needs to drive it without a subprocess. A test that models an
     * exhaustible stream must return the SAME reader instance from the supplier, because that is what
     * {@code System.in} is.
     */
    static Supplier<Reader> stdinSource = () -> new InputStreamReader(System.in, StandardCharsets.UTF_8);

    private static String stdinClaimedBy;

    /** Non-null once a transport has declared that this process has no caller stdin — see {@link
     *  #markStdinUnavailable}. */
    private static String stdinUnavailableBecause;

    private JsonInput() {
    }

    /**
     * Clear the stdin claim. Called by {@code CliMain} once per process, and by a test harness between runs —
     * deliberately NOT by {@link Cli#run}, which nesting callers re-enter per child.
     */
    static void resetForInvocation() {
        stdinClaimedBy = null;
        stdinUnavailableBecause = null;
    }

    /**
     * Declare that this process has <b>no caller stdin</b>, so {@code -} is refused for its whole life.
     *
     * <p>Called by the {@code mcp} verb before any worker starts. The transport decides it, not the request:
     * over <b>stdio</b> stdin IS the protocol channel, so a verb reading it would consume the caller's frames
     * and end the session; over <b>--http</b> there is no caller stdin at all, so a worker would block on the
     * process's until EOF and exhaust the pool. Either way {@code -} names something the caller does not own,
     * which is the same thing the server already refuses for a caller-supplied PATH — it just arrives one step
     * further out, through a {@code batch} child's argv, where the content-parameter check does not look.
     *
     * <p>Set once, before concurrency begins, and never unset. That ordering is what keeps this class's state
     * single-threaded in practice: no MCP dispatch can take the claim, so only the ordinary CLI dispatch chain
     * ever writes it.
     */
    static void markStdinUnavailable(String because) {
        stdinUnavailableBecause = because;
    }

    /**
     * Run {@code body} with {@code -} refused because {@code because} has no caller stdin, restoring the prior
     * posture afterwards. The claim state is deliberately left alone: a nested dispatch inside {@code body}
     * must still see one consumed stdin, and the point here is only that nothing inside may claim it.
     */
    static <T> T withoutCallerStdin(String because, java.util.function.Supplier<T> body) {
        String previous = stdinUnavailableBecause;
        stdinUnavailableBecause = because;
        try {
            return body.get();
        } finally {
            stdinUnavailableBecause = previous;
        }
    }

    /**
     * The text at {@code source} — the file's contents, or all of stdin when {@code source} is {@code -}.
     * {@code label} names the parameter for the double-claim message (e.g. {@code "<spec.json>"}).
     */
    public static String read(String source, String label) throws IOException {
        if (!STDIN.equals(source)) {
            return Files.readString(Path.of(source));
        }
        if (stdinUnavailableBecause != null) {
            throw new IOException(label + " cannot be '-': " + stdinUnavailableBecause + " has no caller stdin, "
                    + "so there is nothing to read. Send the JSON as MCP content instead — the same reason a "
                    + "caller-supplied path is refused here.");
        }
        if (stdinClaimedBy != null) {
            throw new IOException("stdin is already being read as " + stdinClaimedBy + ", so " + label
                    + " cannot also be '-' — stdin belongs to the process and is consumed once. Pass a file "
                    + "path for one of them.");
        }
        // Claim BEFORE consuming: a read that throws part-way has still drained the stream, so a retry must be
        // refused rather than served an empty one.
        stdinClaimedBy = label;
        String text = drain(stdinSource.get());
        if (text.isBlank()) {
            throw new IOException(label + " was given as '-' but stdin was empty — pipe the JSON in, e.g. "
                    + "`printf '%s' \"$SPEC\" | " + Cli.TOOL_NAME + " … -`");
        }
        return text;
    }

    /** Read a reader to exhaustion WITHOUT closing it — see the class note on fd 0. */
    private static String drain(Reader in) throws IOException {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[8192];
        for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
            text.append(buffer, 0, n);
        }
        return text.toString();
    }
}
