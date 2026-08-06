package io.github.mbackschat.a12.dm.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

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
        description = "Serve this tool over the Model Context Protocol (revision " + McpProtocol.REVISION + "), "
                + "for a caller that CANNOT execute this binary — a hosted agent platform, a web client, a "
                + "sandboxed integration. Two transports: by default stdin/stdout, for a host that launches "
                + "the binary itself (`" + Cli.TOOL_NAME + " mcp`, serving until stdin closes); with --http a "
                + "bound port, for a host the caller reaches over the network. Where the binary can be run, "
                + "invoke the verbs directly instead: they edit a model in place, while every MCP call carries "
                + "the model's content both ways and this server keeps nothing.")
final class McpCommand extends BaseCommand {

    /**
     * Where the session reads its requests. A seam, not a parameter: the process supplies its own stdin, and a
     * test supplies a scripted session, so the wiring is exercised without a spawned process.
     */
    static Supplier<Reader> stdinSource = () -> new InputStreamReader(System.in, StandardCharsets.UTF_8);

    /**
     * What a served HTTP endpoint does after it is up: nothing, until the process is stopped. A seam for the
     * same reason as {@link #stdinSource} — a test needs the verb to start, bind, and return.
     */
    static Runnable httpBlock = () -> {
        try {
            Thread.currentThread().join();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    };

    @Option(names = "--http", description = "serve Streamable HTTP on a bound port instead of stdin/stdout — "
            + "the hosted shape, for callers that reach this tool over the network rather than by launching it")
    boolean http;

    // A hosting platform ASSIGNS the address it wants the program to listen on and passes it in the
    // environment — alwaysdata's user-program type sets IP and PORT, and most platforms set at least PORT.
    // So the defaults read the environment before falling back, and picocli interpolates them, which means
    // `--help` on the host shows the address that environment actually resolves to rather than a guess.
    @Option(names = "--host", paramLabel = "<host>", defaultValue = "${env:IP:-${env:HOST:-127.0.0.1}}",
            description = "the interface the HTTP endpoint binds — the platform's IP or HOST environment "
                    + "variable when one is set, else ${DEFAULT-VALUE}. Pass 0.0.0.0 for any interface")
    String host;

    @Option(names = "--port", paramLabel = "<port>", defaultValue = "${env:PORT:-8080}",
            description = "the HTTP port to bind — the platform's PORT environment variable when one is set, "
                    + "else ${DEFAULT-VALUE}; 0 asks the operating system for a free one")
    int port;

    @Option(names = "--allow-origin", paramLabel = "<origin>", description = "a browser Origin permitted to "
            + "call the endpoint (repeatable). A request carrying any other Origin is refused 403, the "
            + "protocol's required DNS-rebinding defense; a non-browser client sends none and is unaffected")
    List<String> allowOrigin = new ArrayList<>();

    @Option(names = "--max-request-bytes", paramLabel = "<bytes>", description = "the request-body ceiling "
            + "(default ${DEFAULT-VALUE}); a larger body is refused with a corrective instead of being read")
    int maxRequestBytes = McpHttpConfig.DEFAULT_MAX_REQUEST_BYTES;

    @Option(names = "--workers", paramLabel = "<n>", description = "how many calls may run at once (default "
            + "${DEFAULT-VALUE}). Memory scales with this, not just CPU: authoring is flat at tens of MiB "
            + "while evaluating a large document has been measured in the hundreds, so raise it only with "
            + "the heap to match")
    int workers = McpHttpConfig.DEFAULT_WORKERS;

    @Option(names = "--queue-depth", paramLabel = "<n>", description = "how many calls may wait for a worker "
            + "(default ${DEFAULT-VALUE}). Past it a caller is told to retry immediately rather than queued: "
            + "a timed-out request is re-issued as a new one, so a deep queue invites a retry storm")
    int queueDepth = McpHttpConfig.DEFAULT_QUEUE_DEPTH;

    @Override
    public Integer call() throws IOException {
        // `--text` renders a result envelope as human text by buffering stdout until the verb returns. On a
        // session that never returns until stdin closes, that would withhold every frame — the client blocks
        // forever on its first request. A protocol stream has no human rendering, so refuse rather than hang.
        if (Boolean.TRUE.equals(spec.findOption("--text").getValue())) {
            return fail("`--text` renders a result envelope for a human reader; this verb writes MCP protocol "
                    + "frames, which have no text rendering. Run it without `--text`.");
        }
        McpDispatcher dispatcher = new McpDispatcher(spec.root());
        if (http) {
            return serveHttp(dispatcher);
        }
        try (BufferedReader in = new BufferedReader(stdinSource.get())) {
            McpStdioBinding.serve(in, out(), dispatcher);
        }
        return 0;
    }

    /**
     * The hosted shape: bind a port and serve until the process is stopped. The startup line goes to
     * <b>stderr</b> — this verb's stdout carries protocol frames on the stdio route and nothing at all here,
     * and a hosting platform reads stderr for logs anyway.
     */
    private Integer serveHttp(McpDispatcher dispatcher) throws IOException {
        McpHttpConfig config = McpHttpConfig.builder()
                .host(host)
                .port(port)
                .allowedOrigins(allowOrigin)
                .maxRequestBytes(maxRequestBytes)
                .workers(workers)
                .queueDepth(queueDepth)
                .build();
        try (McpHttpBinding binding = McpHttpBinding.start(config, dispatcher)) {
            err().println(McpHttpBinding.describe(config, binding.port()));
            err().flush();
            httpBlock.run();
        }
        return 0;
    }
}
