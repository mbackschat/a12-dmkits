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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Multiple callers at once (MCP-SPEC §7 "concurrency"). The thread-safety claim is <b>established here</b>
 * rather than asserted in prose: the hosted endpoint is the first place two callers can be in flight
 * together, and "it should be fine" is exactly the class of claim this project does not ship.
 */
class McpHttpConcurrencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static CommandSpec liveRoot() {
        return new CommandLine(new RootCommand()).getCommandSpec();
    }

    private static String call(String tool, String op, String model) throws IOException {
        return MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("params", MAPPER.createObjectNode()
                        .put("name", tool)
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("arguments",
                                MAPPER.createObjectNode().put("op", op).put("model", model))
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("_meta", MAPPER.createObjectNode()
                                .put(McpProtocol.META_PROTOCOL_VERSION, McpProtocol.REVISION)
                                .<com.fasterxml.jackson.databind.node.ObjectNode>set(
                                        McpProtocol.META_CLIENT_CAPABILITIES, MAPPER.createObjectNode()))));
    }

    private static HttpResponse<String> post(URI endpoint, String payload, String toolName) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(endpoint)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", McpProtocol.REVISION)
                .header("Mcp-Method", "tools/call")
                .header("Mcp-Name", toolName)
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Two callers, two <b>different</b> models, in flight together — each must get the answer to its own
     * request. A shared temp tree, a shared command instance, or a leaked option would show up here as one
     * caller reading the other's model, which is the failure this whole content design exists to prevent.
     */
    @Test
    void concurrentCallersDoNotSeeEachOthersModels() throws Exception {
        String valid = Files.readString(Path.of("src/test/resources/models/order-ruled.dm.json"));
        String other = Files.readString(Path.of("src/test/resources/models/person.dm.json"));
        McpHttpConfig config = McpHttpConfig.builder().port(0).workers(4).queueDepth(32).build();

        try (McpHttpBinding server = McpHttpBinding.start(config, new McpDispatcher(liveRoot()))) {
            URI endpoint = URI.create("http://127.0.0.1:" + server.port() + McpHttpBinding.ENDPOINT);
            List<Callable<JsonNode>> calls = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                String model = index % 2 == 0 ? valid : other;
                String expected = index % 2 == 0 ? "Order" : "Person";
                calls.add(() -> {
                    HttpResponse<String> response = post(endpoint, call("dm.model.read", "describe", model),
                            "dm.model.read");
                    assertEquals(200, response.statusCode(), response::body);
                    JsonNode envelope = MAPPER.readTree(response.body()).path("result")
                            .path("structuredContent");
                    assertTrue(MAPPER.writeValueAsString(envelope).contains(expected),
                            () -> "a caller got someone else's model back: " + envelope);
                    return envelope;
                });
            }
            ExecutorService callers = Executors.newFixedThreadPool(6);
            try {
                for (Future<JsonNode> answer : callers.invokeAll(calls)) {
                    assertFalse(answer.get().isMissingNode(), "every caller got an answer");
                }
            } finally {
                callers.shutdownNow();
                assertTrue(callers.awaitTermination(30, TimeUnit.SECONDS));
            }
        }
    }

    /**
     * At capacity the caller is told so <b>immediately</b>, not queued behind the wall. Driven with an
     * executor that always rejects, because "make the server slow enough to fill up" is a race, and the
     * behaviour under test is the refusal, not the arithmetic that reaches it.
     */
    @Test
    void afullServerAnswersBusyRatherThanQueueing() throws Exception {
        McpHttpConfig config = McpHttpConfig.builder().port(0).workers(1).queueDepth(1).build();
        Runnable noop = () -> { };

        try (McpHttpBinding server = McpHttpBinding.start(config, new McpDispatcher(liveRoot()),
                task -> { throw new RejectedExecutionException("at capacity"); }, noop)) {
            URI endpoint = URI.create("http://127.0.0.1:" + server.port() + McpHttpBinding.ENDPOINT);

            HttpResponse<String> response = post(endpoint,
                    call("dm.model.read", "check", "{}"), "dm.model.read");

            assertEquals(503, response.statusCode());
            assertEquals("1", response.headers().firstValue("Retry-After").orElseThrow(),
                    "a busy answer says when to come back");
            JsonNode error = MAPPER.readTree(response.body()).path("error");
            assertEquals(503, error.path("code").asInt(),
                    "the code is outside the JSON-RPC reserved range, as the spec directs");
            assertTrue(error.path("message").asText().contains("retry"), error::toString);
        }
    }

    /** The surface is derived once per process, so a second `tools/list` cannot see a different one. */
    @Test
    void theToolSurfaceIsStableAcrossCalls() throws Exception {
        McpHttpConfig config = McpHttpConfig.builder().port(0).build();
        String listing = MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("jsonrpc", "2.0").put("id", 1).put("method", "tools/list")
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("params", MAPPER.createObjectNode()
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("_meta", MAPPER.createObjectNode()
                                .put(McpProtocol.META_PROTOCOL_VERSION, McpProtocol.REVISION)
                                .<com.fasterxml.jackson.databind.node.ObjectNode>set(
                                        McpProtocol.META_CLIENT_CAPABILITIES, MAPPER.createObjectNode()))));

        try (McpHttpBinding server = McpHttpBinding.start(config, new McpDispatcher(liveRoot()))) {
            URI endpoint = URI.create("http://127.0.0.1:" + server.port() + McpHttpBinding.ENDPOINT);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .POST(HttpRequest.BodyPublishers.ofString(listing, StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json")
                    .header("MCP-Protocol-Version", McpProtocol.REVISION)
                    .header("Mcp-Method", "tools/list").build();

            String first = CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body();
            String second = CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body();

            assertEquals(first, second, "the derived surface is identical call to call");
        }
    }
}
