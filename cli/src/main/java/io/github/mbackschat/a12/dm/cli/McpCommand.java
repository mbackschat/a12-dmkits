package io.github.mbackschat.a12.dm.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import picocli.CommandLine.Command;

/**
 * The {@code mcp} verb — serve the tool surface over the Model Context Protocol on stdin/stdout (MCP-SPEC).
 *
 * <p><b>The positioning rides the verb, not only a doc</b>, because this help text is where a cold agent meets
 * the surface and the wrong choice here is silent: an agent that can run the binary and reaches for MCP anyway
 * pays a content round trip for every call and gives up in-place editing.
 *
 * <p>Placed in the root package deliberately: the registry walks the live {@link RootCommand} tree, reads
 * {@link CommandContracts}, and renders through {@link TextRenderer} — all package-private, so a subpackage
 * would need three visibility widenings to buy nothing.
 */
@Command(name = "mcp", mixinStandardHelpOptions = true,
        description = "Serve this tool over the Model Context Protocol (revision " + McpProtocol.REVISION + ") "
                + "on stdin/stdout, for a caller that CANNOT execute this binary — a hosted agent platform, a "
                + "web client, a sandboxed integration. Where the binary can be run, invoke the verbs directly "
                + "instead: they edit a model in place, while every MCP call carries the model's content both "
                + "ways. Register it with your host as the command `" + Cli.TOOL_NAME + " mcp`; it serves one "
                + "request at a time and exits when stdin closes.")
final class McpCommand extends BaseCommand {

    /**
     * Where the session reads its requests. A seam, not a parameter: the process supplies its own stdin, and a
     * test supplies a scripted session, so the wiring is exercised without a spawned process.
     */
    static Supplier<Reader> stdinSource = () -> new InputStreamReader(System.in, StandardCharsets.UTF_8);

    @Override
    public Integer call() throws IOException {
        // `--text` renders a result envelope as human text by buffering stdout until the verb returns. On a
        // session that never returns until stdin closes, that would withhold every frame — the client blocks
        // forever on its first request. A protocol stream has no human rendering, so refuse rather than hang.
        if (Boolean.TRUE.equals(spec.findOption("--text").getValue())) {
            return fail("`--text` renders a result envelope for a human reader; this verb writes MCP protocol "
                    + "frames, which have no text rendering. Run it without `--text`.");
        }
        try (BufferedReader in = new BufferedReader(stdinSource.get())) {
            McpStdioBinding.serve(in, out(), new McpDispatcher(spec.root()));
        }
        return 0;
    }
}
