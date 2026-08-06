package io.github.mbackschat.a12.dm.cli;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One inbound call, in <b>our</b> vocabulary rather than the transport's. Everything inside the binding speaks
 * this record, which is what lets the wire be replaced without touching a handler.
 *
 * @param method the JSON-RPC method name — the dispatch table's key
 * @param id     the request id, or {@code null} for a notification (which is answered with silence)
 * @param params the request parameters, never {@code null} (an absent {@code params} reads as empty)
 */
record McpRequest(String method, JsonNode id, ObjectNode params) {

    /** A notification carries no id, and JSON-RPC forbids responding to one. */
    boolean isNotification() {
        return id == null;
    }

    /** The revision this request declares, absent if the required field is missing. */
    Optional<String> protocolVersion() {
        JsonNode version = meta().path(McpProtocol.META_PROTOCOL_VERSION);
        return version.isTextual() ? Optional.of(version.asText()) : Optional.empty();
    }

    /**
     * Whether the client declared its capabilities. Required on every request even when empty, because a
     * stateless server may not infer them from anything earlier on the connection.
     */
    boolean declaresClientCapabilities() {
        return meta().path(McpProtocol.META_CLIENT_CAPABILITIES).isObject();
    }

    private JsonNode meta() {
        return params.path(McpProtocol.META);
    }
}
