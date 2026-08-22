package io.github.mbackschat.a12.dm.cli;

import java.io.BufferedReader;
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
 * <p><b>Why one shared reader.</b> Sixteen positionals across thirteen verbs take a JSON file, and only the
 * config-less field kinds can be authored with flags alone; every other kind needed a temporary file on disk,
 * so a scripted author wrote one temp file per field and cleaned it up. {@code -} removes that without adding
 * a second authoring shape: the rich-spec schema stays the single source for the config, exactly as it is for
 * the file form. (A {@code --config <json>} flag was the alternative and is deliberately not built — it would
 * duplicate that schema as a parallel input surface, on sixteen call sites.)
 *
 * <p><b>Stdin is consumed once, and a second {@code -} is refused rather than silently starved.</b> A verb
 * that takes two JSON inputs ({@code profile compare}) would otherwise hand the second reader an exhausted
 * stream and compare a document against nothing — a wrong answer dressed as a real one. The text is cached so
 * the refusal can be precise about what already claimed it.
 */
public final class JsonInput {

    /** The stdin marker. A file genuinely named {@code -} is reachable as {@code ./-}. */
    public static final String STDIN = "-";

    /**
     * Where stdin is read from. A seam, not a parameter — same reason as {@code McpCommand.stdinSource}: the
     * process supplies its own stdin, and a test needs to drive it without a subprocess.
     */
    static Supplier<Reader> stdinSource = () -> new InputStreamReader(System.in, StandardCharsets.UTF_8);

    private static String stdinText;
    private static String stdinClaimedBy;

    private JsonInput() {
    }

    /** Clear the per-invocation stdin state. Called by {@link Cli#run} so one process can serve many runs. */
    static void resetForInvocation() {
        stdinText = null;
        stdinClaimedBy = null;
    }

    /**
     * The text at {@code source} — the file's contents, or all of stdin when {@code source} is {@code -}.
     * {@code label} names the parameter for the double-claim message (e.g. {@code "<spec.json>"}).
     */
    public static String read(String source, String label) throws IOException {
        if (!STDIN.equals(source)) {
            return Files.readString(Path.of(source));
        }
        if (stdinClaimedBy != null) {
            throw new IOException("stdin is already being read as " + stdinClaimedBy + ", so " + label
                    + " cannot also be '-' — stdin can only be consumed once. Pass a file path for one of them.");
        }
        try (BufferedReader in = new BufferedReader(stdinSource.get())) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[8192];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                text.append(buffer, 0, n);
            }
            stdinText = text.toString();
        }
        stdinClaimedBy = label;
        if (stdinText.isBlank()) {
            throw new IOException(label + " was given as '-' but stdin was empty — pipe the JSON in, e.g. "
                    + "`printf '%s' \"$SPEC\" | " + Cli.TOOL_NAME + " … -`");
        }
        return stdinText;
    }

    /** Whether {@code source} names stdin — for a verb that must report its input's origin. */
    public static boolean isStdin(String source) {
        return STDIN.equals(source);
    }
}
