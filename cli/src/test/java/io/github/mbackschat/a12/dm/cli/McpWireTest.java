package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The wire contract, asserted as <b>bytes in → bytes out</b> (MCP-SPEC "the wire"). Deliberately not a test of
 * internals: a frame test does not know which binding produced the answer, so it survives a later substitution
 * of the hand-rolled transport for an SDK and becomes that SDK's acceptance suite rather than something to
 * rewrite.
 *
 * <p>Every assertion here is a specification MUST or SHOULD of revision {@code 2026-07-28}, cited at the case.
 */
class McpWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The same tree the running command hands the dispatcher. */
    private static picocli.CommandLine.Model.CommandSpec liveRoot() {
        return new picocli.CommandLine(new RootCommand()).getCommandSpec();
    }

    private static final String META = """
            "_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28",\
            "io.modelcontextprotocol/clientCapabilities":{}}""";

    /** Feed the framer these lines; return the response frames it wrote, one per line. */
    private static List<JsonNode> exchange(String... lines) throws Exception {
        StringWriter sink = new StringWriter();
        try (PrintWriter out = new PrintWriter(sink)) {
            McpStdioBinding.serve(new BufferedReader(new StringReader(String.join("\n", lines) + "\n")),
                    out, new McpDispatcher(liveRoot()));
        }
        String written = sink.toString();
        if (written.isEmpty()) {
            return List.of();
        }
        // Every frame is one line: the stdio binding forbids embedded newlines in a message.
        List<JsonNode> frames = new java.util.ArrayList<>();
        for (String line : written.split("\n", -1)) {
            if (!line.isEmpty()) {
                frames.add(MAPPER.readTree(line));
            }
        }
        return frames;
    }

    private static JsonNode only(String... lines) throws Exception {
        List<JsonNode> frames = exchange(lines);
        assertEquals(1, frames.size(), () -> "expected exactly one response frame, got " + frames);
        return frames.get(0);
    }

    private static String request(String id, String method) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":{" + META + "}}";
    }

    @Test
    void serverDiscoverAnswersWithTheSupportedRevisionAndItsIdentity() throws Exception {
        JsonNode frame = only(request("\"d1\"", "server/discover"));

        assertEquals("2.0", frame.path("jsonrpc").asText());
        assertEquals("d1", frame.path("id").asText(), "a result MUST carry the request's id");
        JsonNode result = frame.path("result");
        assertEquals("complete", result.path("resultType").asText(), "every result MUST carry a resultType");
        assertEquals(List.of("2026-07-28"),
                MAPPER.convertValue(result.path("supportedVersions"), List.class));
        assertTrue(result.path("capabilities").has("tools"), "the tools capability is declared");
        assertFalse(result.path("instructions").asText().isBlank(), "discover carries the router instructions");
        assertTrue(result.path("ttlMs").isNumber(), "a cacheable result carries ttlMs");
        assertEquals("public", result.path("cacheScope").asText());
        // Servers SHOULD identify themselves in the result's _meta rather than in a handshake that no longer exists.
        JsonNode serverInfo = result.path("_meta").path("io.modelcontextprotocol/serverInfo");
        assertEquals("dmtool", serverInfo.path("name").asText());
        assertFalse(serverInfo.path("version").asText().isBlank());
    }

    @Test
    void anUnknownMethodIsMethodNotFound() throws Exception {
        JsonNode error = only(request("1", "tools/nope")).path("error");
        assertEquals(-32601, error.path("code").asInt());
        assertTrue(error.path("message").asText().contains("tools/nope"), () -> "names the method: " + error);
    }

    /** JSON-RPC: the receiver MUST NOT respond to a notification. */
    @Test
    void aNotificationIsAnsweredWithSilence() throws Exception {
        assertEquals(List.of(), exchange(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":1}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
    }

    /**
     * A request missing a required {@code _meta} field is malformed and the server MUST reject it with
     * {@code -32602}. This is the one place the parked proposal's refusal table was wrong: it specified
     * {@code -32022} for the absent field, reasoning that absence is the signature of a pre-revision client.
     * The diagnosis is right and rides the message; the code is the specification's.
     */
    @Test
    void aRequestMissingARequiredMetaFieldIsInvalidParams() throws Exception {
        for (String params : List.of(
                "{}",
                "{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}",
                "{\"_meta\":{\"io.modelcontextprotocol/clientCapabilities\":{}}}")) {
            JsonNode error = only("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"server/discover\",\"params\":"
                    + params + "}").path("error");
            assertEquals(-32602, error.path("code").asInt(), () -> "for params " + params);
            assertTrue(error.path("message").asText().contains("2026-07-28"),
                    () -> "the refusal names the revision this server implements: " + error);
        }
    }

    @Test
    void anOlderProtocolVersionIsRefusedWithTheSupportedSet() throws Exception {
        JsonNode error = only("""
                {"jsonrpc":"2.0","id":3,"method":"tools/list","params":{"_meta":{\
                "io.modelcontextprotocol/protocolVersion":"2025-11-25",\
                "io.modelcontextprotocol/clientCapabilities":{}}}}""").path("error");

        assertEquals(-32022, error.path("code").asInt());
        assertEquals("Unsupported protocol version", error.path("message").asText());
        assertEquals(List.of("2026-07-28"), MAPPER.convertValue(error.path("data").path("supported"), List.class));
        assertEquals("2025-11-25", error.path("data").path("requested").asText());
    }

    /**
     * A legacy client opens with {@code initialize}, which this revision removed. The spec leaves the code
     * implementation-defined and says a modern-only server SHOULD name its supported versions in the error,
     * because a legacy client has no fall-forward mechanism and this may be its only diagnostic.
     */
    @Test
    void theRemovedHandshakeIsRefusedWithARevisionNamingMessage() throws Exception {
        JsonNode error = only("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}").path("error");
        assertEquals(-32601, error.path("code").asInt());
        assertTrue(error.path("message").asText().contains("initialize"), () -> "names the removed method: " + error);
        assertTrue(error.path("message").asText().contains("2026-07-28"), () -> "names the revision served: " + error);
        assertTrue(error.path("message").asText().contains("server/discover"),
                () -> "points at where the supported set is published: " + error);
    }

    @Test
    void amalformedLineIsAParseErrorAndTheSessionKeepsServing() throws Exception {
        List<JsonNode> frames = exchange("{not json", request("\"after\"", "server/discover"));
        assertEquals(2, frames.size(), () -> "the framer answers the bad line and keeps reading: " + frames);
        assertEquals(-32700, frames.get(0).path("error").path("code").asInt());
        assertTrue(frames.get(0).path("id").isNull(), "an unreadable id is reported as null");
        assertEquals("after", frames.get(1).path("id").asText());
    }

    /**
     * The stdio binding: messages are delimited by newlines and MUST NOT contain embedded ones. A refusal
     * message carrying a newline is the case that would break the frame, so it is the one asserted.
     */
    @Test
    void noFrameContainsAnEmbeddedNewline() throws Exception {
        StringWriter sink = new StringWriter();
        try (PrintWriter out = new PrintWriter(sink)) {
            McpStdioBinding.serve(new BufferedReader(new StringReader(
                    "{\"jsonrpc\":\"2.0\",\"id\":\"nl\",\"method\":\"a\\nb\",\"params\":{" + META + "}}\n")),
                    out, new McpDispatcher(liveRoot()));
        }
        String written = sink.toString();
        assertEquals(1, written.chars().filter(c -> c == '\n').count(),
                () -> "exactly one newline, the frame delimiter: " + written);
        assertTrue(written.endsWith("\n"), "the frame is newline-terminated");
        assertTrue(MAPPER.readTree(written).path("error").path("message").asText().contains("\n"),
                "the newline survives escaped inside the JSON string");
    }

    @Test
    void closedInputEndsTheSession() throws Exception {
        StringWriter sink = new StringWriter();
        try (PrintWriter out = new PrintWriter(sink)) {
            McpStdioBinding.serve(new BufferedReader(new StringReader("")), out, new McpDispatcher(liveRoot()));
        }
        assertEquals("", sink.toString(), "EOF is the graceful-shutdown signal; nothing is written");
    }

    /** The handler table is data the binding consumes, so a later SDK binding registers the same entries. */
    @Test
    void theHandlerTableIsExposedAsData() {
        assertTrue(new McpDispatcher(liveRoot()).handlers().containsKey("server/discover"),
                "the dispatch table is addressable by method name");
    }
}
