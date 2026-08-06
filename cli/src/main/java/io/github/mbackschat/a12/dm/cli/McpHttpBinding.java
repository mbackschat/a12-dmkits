package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The hosted wire: Streamable HTTP (MCP-SPEC "hosted"). The <b>second</b> binding over the same seam — it
 * reuses {@link McpDispatcher}, {@link McpToolRegistry}, and {@link McpInvoker} unchanged, which is what the
 * content-carrying request model was built for: with no session and no server-side state, a transport swap
 * touches only this class.
 *
 * <p>Built on the JDK's own {@code com.sun.net.httpserver}, so the zero-new-dependency rule survives hosting.
 *
 * <p><b>The status mapping is the specification's, not a convention.</b> JSON-RPC over HTTP is often served
 * as 200-with-an-error-body; this revision is explicit instead, and each row below is a MUST:
 *
 * <table>
 *   <caption>outcome → status</caption>
 *   <tr><td>a result</td><td>{@code 200} {@code application/json}</td></tr>
 *   <tr><td>a notification</td><td>{@code 202} with no body</td></tr>
 *   <tr><td>header missing, or not matching the body</td><td>{@code 400} + {@code HeaderMismatch} (-32020)</td></tr>
 *   <tr><td>a protocol version this server does not implement</td><td>{@code 400} + -32022</td></tr>
 *   <tr><td>an RPC method it does not implement</td><td>{@code 404} + -32601</td></tr>
 *   <tr><td>{@code Origin} present and not allowed</td><td>{@code 403}</td></tr>
 *   <tr><td>{@code GET}/{@code DELETE} (the removed session mechanics)</td><td>{@code 405}</td></tr>
 * </table>
 *
 * <p>A <i>tool execution</i> error is none of these: it rides a {@code 200} as {@code isError} inside the
 * result, because the call reached the verb and the answer is actionable feedback.
 */
final class McpHttpBinding implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The single MCP endpoint. The specification requires exactly one path, accepting POST. */
    static final String ENDPOINT = "/mcp";

    private static final String HEADER_VERSION = "MCP-Protocol-Version";
    private static final String HEADER_METHOD = "Mcp-Method";
    private static final String HEADER_NAME = "Mcp-Name";

    /** Methods whose {@code Mcp-Name} is required; only the first is served here. */
    private static final List<String> NAMED_METHODS = List.of("tools/call", "resources/read", "prompts/get");

    /** A header value that could not be sent as plain ASCII arrives wrapped in this sentinel. */
    private static final String BASE64_PREFIX = "=?base64?";
    private static final String BASE64_SUFFIX = "?=";

    private final HttpServer server;

    private McpHttpBinding(HttpServer server) {
        this.server = server;
    }

    /** Bind and begin serving. The caller owns the returned handle and closes it to stop. */
    static McpHttpBinding start(McpHttpConfig config, McpDispatcher dispatcher, Executor executor)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        server.createContext(ENDPOINT, exchange -> {
            try (exchange) {
                handle(exchange, config, dispatcher);
            }
        });
        server.setExecutor(executor);
        server.start();
        return new McpHttpBinding(server);
    }

    int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static void handle(HttpExchange exchange, McpHttpConfig config, McpDispatcher dispatcher)
            throws IOException {
        // GET opened the standalone SSE stream and DELETE terminated a session; this revision removed both,
        // and answering 405 is what tells an older client it is talking to a modern server.
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().add("Allow", "POST");
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        Optional<String> origin = header(exchange, "Origin");
        if (origin.isPresent() && !config.allowedOrigins().contains(origin.get())) {
            // Required DNS-rebinding defense: a browser page on an unlisted origin must not reach the server.
            respond(exchange, 403, error(null, McpProtocol.INVALID_REQUEST,
                    "origin `" + origin.get() + "` is not allowed"));
            return;
        }
        byte[] body;
        try {
            body = read(exchange.getRequestBody(), config.maxRequestBytes());
        } catch (RequestTooLarge tooLarge) {
            respond(exchange, 413, error(null, McpProtocol.INVALID_REQUEST, tooLarge.getMessage()));
            return;
        }
        JsonNode message;
        try {
            message = MAPPER.readTree(body);
        } catch (JacksonException malformed) {
            respond(exchange, 400, error(null, McpProtocol.PARSE_ERROR,
                    "malformed JSON-RPC message: " + malformed.getOriginalMessage()));
            return;
        }
        if (!message.isObject() || !message.path("method").isTextual()) {
            respond(exchange, 400, error(null, McpProtocol.INVALID_REQUEST,
                    "a JSON-RPC message must be an object naming a method"));
            return;
        }
        JsonNode id = message.get("id");
        if (id == null) {
            // A notification is accepted and answered with nothing — the transport's own rule, and the reason
            // header validation does not apply to one: this revision defines no header requirements for them.
            exchange.sendResponseHeaders(202, -1);
            return;
        }
        Optional<String> mismatch = headerMismatch(exchange, message);
        if (mismatch.isPresent()) {
            respond(exchange, 400, error(id, McpProtocol.HEADER_MISMATCH, mismatch.get()));
            return;
        }
        JsonNode params = message.path("params");
        McpRequest request = new McpRequest(message.path("method").asText(), id,
                params.isObject() ? (ObjectNode) params : MAPPER.createObjectNode());
        try {
            respond(exchange, 200, McpFrames.result(id, dispatcher.dispatch(request)));
        } catch (McpFailure refusal) {
            respond(exchange, statusFor(refusal.code()),
                    McpFrames.error(id, refusal.code(), refusal.getMessage(), refusal.data()));
        } catch (RuntimeException unexpected) {
            respond(exchange, 500, error(id, McpProtocol.INTERNAL_ERROR, "internal server error: " + unexpected));
        }
    }

    /**
     * The mirrored headers, checked against the body. The point is not ceremony: an intermediary may route on
     * the header while this server executes the body, so a disagreement between them is a real vulnerability
     * and the specification makes rejecting it a MUST.
     */
    private static Optional<String> headerMismatch(HttpExchange exchange, JsonNode message) {
        Optional<String> version = header(exchange, HEADER_VERSION);
        if (version.isEmpty()) {
            return Optional.of("every POST must carry the `" + HEADER_VERSION + "` header");
        }
        String declared = message.path("params").path(McpProtocol.META)
                .path(McpProtocol.META_PROTOCOL_VERSION).asText(null);
        if (declared != null && !version.get().equals(declared)) {
            return Optional.of("`" + HEADER_VERSION + "` is `" + version.get() + "` but the body declares `"
                    + declared + "`");
        }
        Optional<String> method = header(exchange, HEADER_METHOD);
        if (method.isEmpty()) {
            return Optional.of("every POST must carry the `" + HEADER_METHOD + "` header");
        }
        String actual = message.path("method").asText();
        if (!method.get().equals(actual)) {
            return Optional.of("`" + HEADER_METHOD + "` is `" + method.get() + "` but the body calls `"
                    + actual + "`");
        }
        if (!NAMED_METHODS.contains(actual)) {
            return Optional.empty();
        }
        Optional<String> name = header(exchange, HEADER_NAME);
        if (name.isEmpty()) {
            return Optional.of("`" + actual + "` must carry the `" + HEADER_NAME + "` header");
        }
        String target = message.path("params").path("name").asText(
                message.path("params").path("uri").asText(""));
        String decoded = decodeSentinel(name.get());
        return decoded.equals(target) ? Optional.empty()
                : Optional.of("`" + HEADER_NAME + "` is `" + decoded + "` but the body names `" + target + "`");
    }

    /**
     * A value that is not header-safe travels Base64-wrapped, and a server that compares before decoding
     * would reject every legitimate non-ASCII tool name.
     */
    private static String decodeSentinel(String value) {
        if (!value.startsWith(BASE64_PREFIX) || !value.endsWith(BASE64_SUFFIX)) {
            return value;
        }
        String encoded = value.substring(BASE64_PREFIX.length(), value.length() - BASE64_SUFFIX.length());
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            return value; // let the comparison fail, and report what actually arrived
        }
    }

    /** Which HTTP status carries which refusal — every row a specification MUST (see the class javadoc). */
    private static int statusFor(int code) {
        return switch (code) {
            case McpProtocol.METHOD_NOT_FOUND -> 404;
            case McpProtocol.INTERNAL_ERROR -> 500;
            default -> 400;
        };
    }

    private static Optional<String> header(HttpExchange exchange, String name) {
        Headers headers = exchange.getRequestHeaders();
        // Header names are case-insensitive; the JDK's Headers already folds them, but a caller's exact
        // spelling must never decide the answer, so the lookup is explicit about it.
        String value = headers.getFirst(name);
        if (value == null) {
            for (String candidate : headers.keySet()) {
                if (candidate.equalsIgnoreCase(name)) {
                    value = headers.getFirst(candidate);
                    break;
                }
            }
        }
        return Optional.ofNullable(value).map(String::trim).filter(text -> !text.isEmpty());
    }

    /** Read at most {@code max} bytes, refusing rather than allocating whatever a caller chose to send. */
    private static byte[] read(InputStream in, int max) throws IOException {
        byte[] body = in.readNBytes(max);
        if (in.read() != -1) {
            throw new RequestTooLarge("the request body exceeds the " + max + "-byte cap this server accepts; "
                    + "send a smaller model, or raise --max-request-bytes on the server");
        }
        return body;
    }

    private static final class RequestTooLarge extends IOException {
        private static final long serialVersionUID = 1L;

        private RequestTooLarge(String message) {
            super(message);
        }
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        return McpFrames.error(id, code, message, null);
    }

    private static void respond(HttpExchange exchange, int status, ObjectNode frame) throws IOException {
        byte[] payload = MAPPER.writeValueAsBytes(frame);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /** Lower-cased for a log line; the binding itself never writes to stdout. */
    static String describe(McpHttpConfig config, int port) {
        return String.format(Locale.ROOT, "MCP endpoint http://%s:%d%s (revision %s)",
                config.host(), port, ENDPOINT, McpProtocol.REVISION);
    }
}
