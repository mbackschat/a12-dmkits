package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import picocli.CommandLine;

/**
 * The hosted wire, asserted as <b>HTTP in → HTTP out</b> (MCP-SPEC "hosted"). Every case is a specification
 * MUST for a Streamable HTTP server, and each names it — because this revision is explicit about status codes
 * where JSON-RPC over HTTP is usually served as 200-with-an-error-body, and guessing would be conformant-
 * looking and wrong.
 */
class McpHttpWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private McpHttpBinding server;
    private URI endpoint;

    @BeforeEach
    void start() throws IOException {
        McpHttpConfig config = McpHttpConfig.builder().port(0).allowedOrigins(List.of("https://allowed.example"))
                .maxRequestBytes(32 * 1024).build();
        // The direct executor keeps these protocol cases single-threaded and deterministic; the bounded pool
        // is exercised by McpHttpConcurrencyTest, which is where concurrency is the thing under test.
        server = McpHttpBinding.start(config,
                new McpDispatcher(new CommandLine(new RootCommand()).getCommandSpec()), Runnable::run, () -> { });
        endpoint = URI.create("http://127.0.0.1:" + server.port() + McpHttpBinding.ENDPOINT);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static String body(String id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\"" + (id == null ? "" : ",\"id\":" + id) + ",\"method\":\"" + method
                + "\",\"params\":{" + params + (params.isEmpty() ? "" : ",")
                + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
    }

    private HttpResponse<String> post(String payload, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json");
        headers.forEach(request::header);
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The headers a conforming client always sends, for a plain (unnamed) request. */
    private static Map<String, String> standard(String method) {
        return Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", method);
    }

    @Test
    void aRequestIsAnsweredWithASingleJsonObject() throws Exception {
        HttpResponse<String> response = post(body("1", "server/discover", ""), standard("server/discover"));

        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());
        JsonNode result = MAPPER.readTree(response.body()).path("result");
        assertEquals("complete", result.path("resultType").asText());
        assertEquals("2026-07-28", result.path("supportedVersions").get(0).asText());
    }

    /** A tool call over HTTP is the same call: the model's content in, the envelope out. */
    @Test
    void aToolCallCarriesContentOverHttp() throws Exception {
        String model = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/test/resources/models/order-ruled.dm.json"));
        String payload = MAPPER.writeValueAsString(MAPPER.readTree(body("2", "tools/call",
                "\"name\":\"dm.model.read\",\"arguments\":" + MAPPER.writeValueAsString(
                        MAPPER.createObjectNode().put("op", "check").put("model", model)))));

        HttpResponse<String> response = post(payload, Map.of("MCP-Protocol-Version", "2026-07-28",
                "Mcp-Method", "tools/call", "Mcp-Name", "dm.model.read"));

        assertEquals(200, response.statusCode());
        JsonNode result = MAPPER.readTree(response.body()).path("result");
        assertFalse(result.path("isError").asBoolean(), () -> "the fixture is valid: " + result);
        assertTrue(result.path("structuredContent").path("valid").asBoolean());
    }

    /** "If the body is a JSON-RPC notification … the server MUST return HTTP status code 202 with no body." */
    @Test
    void aNotificationIsAcceptedWithTwoOhTwoAndNoBody() throws Exception {
        HttpResponse<String> response = post(body(null, "notifications/cancelled", "\"requestId\":1"),
                standard("notifications/cancelled"));

        assertEquals(202, response.statusCode());
        assertEquals("", response.body());
    }

    /** GET opened the standalone SSE stream and DELETE terminated a session; this revision removed both. */
    @Test
    void theRemovedSessionMethodsAreMethodNotAllowed() throws Exception {
        for (String method : List.of("GET", "DELETE")) {
            HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(endpoint)
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(405, response.statusCode(), () -> "for " + method);
        }
    }

    /** "Servers MUST validate the Origin header … If present and invalid, servers MUST respond with 403." */
    @Test
    void anUnlistedBrowserOriginIsForbidden() throws Exception {
        HttpResponse<String> refused = post(body("1", "server/discover", ""),
                Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "server/discover",
                        "Origin", "https://evil.example"));
        assertEquals(403, refused.statusCode());

        HttpResponse<String> allowed = post(body("1", "server/discover", ""),
                Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "server/discover",
                        "Origin", "https://allowed.example"));
        assertEquals(200, allowed.statusCode(), "the allowlisted origin still gets through");
    }

    /**
     * The mirrored headers must agree with the body, and a missing required one is the same failure: an
     * intermediary may route on the header while this server executes the body.
     */
    @Test
    void everyHeaderValidationFailureIsFourHundredHeaderMismatch() throws Exception {
        record Case(String why, Map<String, String> headers) {
        }
        List<Case> cases = List.of(
                new Case("no protocol version", Map.of("Mcp-Method", "server/discover")),
                new Case("version disagrees with the body",
                        Map.of("MCP-Protocol-Version", "2025-11-25", "Mcp-Method", "server/discover")),
                new Case("no method header", Map.of("MCP-Protocol-Version", "2026-07-28")),
                new Case("method disagrees with the body",
                        Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/list")));

        for (Case testCase : cases) {
            HttpResponse<String> response = post(body("1", "server/discover", ""), testCase.headers());
            assertEquals(400, response.statusCode(), testCase::why);
            assertEquals(-32020, MAPPER.readTree(response.body()).path("error").path("code").asInt(),
                    testCase::why);
        }
    }

    @Test
    void aToolCallWithoutItsNameHeaderIsHeaderMismatch() throws Exception {
        HttpResponse<String> response = post(
                body("1", "tools/call", "\"name\":\"dm.catalog\",\"arguments\":{\"op\":\"operators\"}"),
                standard("tools/call"));

        assertEquals(400, response.statusCode());
        assertEquals(-32020, MAPPER.readTree(response.body()).path("error").path("code").asInt());
    }

    /**
     * A name that is not header-safe travels Base64-wrapped in the sentinel, and the server MUST decode it
     * before comparing — a server that compared first would reject every legitimate encoded name.
     */
    @Test
    void aBase64SentinelNameIsDecodedBeforeItIsCompared() throws Exception {
        String encoded = "=?base64?" + Base64.getEncoder().encodeToString(
                "dm.catalog".getBytes(StandardCharsets.UTF_8)) + "?=";

        HttpResponse<String> response = post(
                body("1", "tools/call", "\"name\":\"dm.catalog\",\"arguments\":{\"op\":\"operators\"}"),
                Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/call", "Mcp-Name", encoded));

        assertEquals(200, response.statusCode(), response::body);
    }

    /** "If the server does not implement the requested protocol version … MUST respond with 400 Bad Request." */
    @Test
    void anOlderRevisionIsFourHundredWithTheSupportedSet() throws Exception {
        String payload = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"_meta\":{"
                + "\"io.modelcontextprotocol/protocolVersion\":\"2025-11-25\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";

        HttpResponse<String> response = post(payload,
                Map.of("MCP-Protocol-Version", "2025-11-25", "Mcp-Method", "tools/list"));

        assertEquals(400, response.statusCode());
        JsonNode error = MAPPER.readTree(response.body()).path("error");
        assertEquals(-32022, error.path("code").asInt());
        assertEquals("2026-07-28", error.path("data").path("supported").get(0).asText());
    }

    /** "If the server does not implement the requested RPC method, it MUST respond with 404 Not Found." */
    @Test
    void anUnimplementedMethodIsFourOhFour() throws Exception {
        HttpResponse<String> response = post(body("1", "prompts/list", ""), standard("prompts/list"));

        assertEquals(404, response.statusCode());
        assertEquals(-32601, MAPPER.readTree(response.body()).path("error").path("code").asInt());
    }

    /** The removed session mechanics are ignored rather than honoured — no id is minted, nothing resumes. */
    @Test
    void legacySessionHeadersAreIgnored() throws Exception {
        HttpResponse<String> response = post(body("1", "server/discover", ""),
                Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "server/discover",
                        "Mcp-Session-Id", "abc", "Last-Event-ID", "7"));

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty(), "no session id is echoed");
    }

    /** The payload cap is refused with a corrective rather than read into memory (H12). */
    @Test
    void anOversizedBodyIsRefusedWithACorrective() throws Exception {
        String oversized = body("1", "server/discover", "\"pad\":\"" + "x".repeat(64 * 1024) + "\"");

        HttpResponse<String> response = post(oversized, standard("server/discover"));

        assertEquals(413, response.statusCode());
        assertTrue(MAPPER.readTree(response.body()).path("error").path("message").asText()
                .contains("max-request-bytes"), () -> "the refusal says how to raise it: " + response.body());
    }
}
