package io.github.mbackschat.a12.dm.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The wire: newline-delimited JSON-RPC over a byte stream (MCP-SPEC "the wire"). The outermost layer, and the
 * <b>only</b> one that may name a transport shape — the JSON-RPC envelope, the framing, the id correlation.
 * Everything it calls speaks {@link McpRequest}/{@link McpResult}.
 *
 * <p>Three binding rules are specification MUSTs and each is discharged here rather than trusted:
 *
 * <ul>
 *   <li><b>one message per line, no embedded newlines.</b> Frames are serialised compactly, so a newline
 *       inside any string is escaped by the serializer and cannot break the frame;</li>
 *   <li><b>nothing on this stream that is not an MCP message.</b> The writer handed in is the server's only
 *       output, and no verb ever writes to it — {@code Cli.run} is given its own sinks;</li>
 *   <li><b>never respond to a notification.</b> A message without an id is consumed and answered with
 *       silence, which is also what makes {@code notifications/cancelled} safe to accept.</li>
 * </ul>
 *
 * <p>End of input is the graceful-shutdown signal: the loop returns and the process exits promptly.
 */
final class McpStdioBinding {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpStdioBinding() {
    }

    /**
     * Serve one session: read framed requests until end of input, answering each in turn. Dispatch is
     * <b>serialised</b> — one request at a time — which is the only posture whose thread-safety is
     * established, and which no verb in this tool is long enough to make costly for a single caller.
     */
    static void serve(BufferedReader in, PrintWriter out, McpDispatcher dispatcher) throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode message;
            try {
                message = MAPPER.readTree(line);
            } catch (JacksonException malformed) {
                // The id is unreadable, so the error frame reports it as null — the one case JSON-RPC allows.
                write(out, McpFrames.error(null, McpProtocol.PARSE_ERROR,
                        "malformed JSON-RPC message: " + malformed.getOriginalMessage(), null));
                continue;
            }
            respond(out, dispatcher, message);
        }
    }

    private static void respond(PrintWriter out, McpDispatcher dispatcher, JsonNode message) throws IOException {
        if (!message.isObject()) {
            write(out, McpFrames.error(null, McpProtocol.INVALID_REQUEST,
                    "a JSON-RPC message must be an object", null));
            return;
        }
        JsonNode id = message.get("id");
        if (id != null && id.isNull()) {
            // A notification omits the id entirely; an explicit null is neither a request nor a notification.
            write(out, McpFrames.error(null, McpProtocol.INVALID_REQUEST,
                    "a request id must not be null; omit `id` entirely to send a notification", null));
            return;
        }
        JsonNode method = message.path("method");
        if (!method.isTextual()) {
            write(out, McpFrames.error(id, McpProtocol.INVALID_REQUEST, "a JSON-RPC message must name a method", null));
            return;
        }
        JsonNode params = message.path("params");
        McpRequest request = new McpRequest(method.asText(), id,
                params.isObject() ? (ObjectNode) params : MAPPER.createObjectNode());
        if (request.isNotification()) {
            return; // JSON-RPC: the receiver MUST NOT respond to a notification.
        }
        try {
            write(out, McpFrames.result(id, dispatcher.dispatch(request)));
        } catch (McpFailure refusal) {
            write(out, McpFrames.error(id, refusal.code(), refusal.getMessage(), refusal.data()));
        } catch (RuntimeException unexpected) {
            // A handler bug is this server's fault, never the caller's: report it as an internal error rather
            // than letting it kill the session, which would take every other in-flight request with it.
            write(out, McpFrames.error(id, McpProtocol.INTERNAL_ERROR,
                    "internal server error: " + unexpected, null));
        }
    }

    /** One frame, one line. Flushed per message: a client blocks on the answer to the request it just sent. */
    private static void write(PrintWriter out, ObjectNode frame) throws IOException {
        String encoded;
        try {
            encoded = MAPPER.writeValueAsString(frame);
        } catch (JsonProcessingException impossible) {
            throw new IOException("cannot serialize an MCP frame", impossible);
        }
        out.print(encoded);
        out.print('\n');
        out.flush();
    }
}
