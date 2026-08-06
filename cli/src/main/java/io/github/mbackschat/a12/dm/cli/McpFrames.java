package io.github.mbackschat.a12.dm.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The JSON-RPC envelope both bindings write. Shared deliberately: a result must carry the same
 * {@code resultType}, the same cache fields, and the same {@code serverInfo} whether it left over stdio or
 * over HTTP, and two copies of that shape would be two things to keep true.
 *
 * <p>What is <b>not</b> here is anything transport-specific — framing, status codes, headers. That stays in
 * the binding, which is the only layer allowed to know it.
 */
final class McpFrames {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpFrames() {
    }

    /** A successful answer: the handler's payload, plus the facts every result carries. */
    static ObjectNode result(JsonNode id, McpResult result) {
        ObjectNode frame = frame(id);
        ObjectNode body = frame.putObject("result");
        body.put("resultType", result.resultType().wireName());
        body.setAll(result.payload());
        if (result.ttlMs() != null) {
            body.put("ttlMs", result.ttlMs());
            body.put("cacheScope", result.cacheScope().wireName());
        }
        // Servers SHOULD identify themselves on every result; with no session there is nowhere else to say it.
        body.putObject(McpProtocol.META).set(McpProtocol.META_SERVER_INFO, serverInfo());
        return frame;
    }

    /** A refusal. {@code data} is omitted rather than nulled when the message says everything. */
    static ObjectNode error(JsonNode id, int code, String message, ObjectNode data) {
        ObjectNode frame = frame(id);
        ObjectNode error = frame.putObject("error");
        error.put("code", code);
        error.put("message", message);
        if (data != null) {
            error.set("data", data);
        }
        return frame;
    }

    private static ObjectNode frame(JsonNode id) {
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("jsonrpc", "2.0");
        frame.set("id", id == null ? MAPPER.nullNode() : id);
        return frame;
    }

    private static ObjectNode serverInfo() {
        ObjectNode info = MAPPER.createObjectNode();
        info.put("name", Cli.TOOL_NAME);
        info.put("version", VersionInfo.current().rulekit());
        return info;
    }
}
