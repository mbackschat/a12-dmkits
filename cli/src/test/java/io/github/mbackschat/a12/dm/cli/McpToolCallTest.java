package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/**
 * `tools/call` end to end (MCP-SPEC §7): content in, content out, nothing kept. Each case drives the real
 * dispatcher against a real fixture, because the whole point of this layer is that it runs the actual verb —
 * a test with a stubbed invocation would prove only that the plumbing compiles.
 */
class McpToolCallTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path FIXTURE = Path.of("src/test/resources/models/order-ruled.dm.json");

    private static CommandSpec liveRoot() {
        return new CommandLine(new RootCommand()).getCommandSpec();
    }

    private static String fixture() throws IOException {
        return Files.readString(FIXTURE);
    }

    /** One `tools/call`, answered by the real dispatcher. */
    private static JsonNode call(String tool, ObjectNode arguments) throws Exception {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", tool);
        params.set("arguments", arguments);
        ObjectNode meta = params.putObject("_meta");
        meta.put(McpProtocol.META_PROTOCOL_VERSION, McpProtocol.REVISION);
        meta.putObject(McpProtocol.META_CLIENT_CAPABILITIES);
        return new McpDispatcher(liveRoot())
                .dispatch(new McpRequest("tools/call", MAPPER.getNodeFactory().numberNode(1), params))
                .payload();
    }

    private static ObjectNode arguments() {
        return MAPPER.createObjectNode();
    }

    private static String textOf(JsonNode result) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : result.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        return text.toString();
    }

    @Test
    void aReadRunsTheVerbOnCarriedContentAndReturnsTheEnvelope() throws Exception {
        ObjectNode arguments = arguments();
        arguments.put("op", "check");
        arguments.put("model", fixture());

        JsonNode result = call("dm.model.read", arguments);

        assertFalse(result.path("isError").asBoolean(), () -> "a valid model is not an error: " + result);
        assertEquals("model", result.path("structuredContent").path("target").asText());
        assertEquals("check", result.path("structuredContent").path("op").asText());
        assertTrue(result.path("structuredContent").path("valid").asBoolean(),
                () -> "the fixture is kernel-valid: " + result.path("structuredContent"));
        assertFalse(textOf(result).isBlank(), "the same answer rides content as human-readable text");
    }

    /**
     * The editing round trip: the caller sends content, receives the edited model back, and the fixture on
     * disk is untouched — the caller owns persistence, so the server writing anything durable would be a
     * defect, not a convenience.
     */
    @Test
    void anEditReturnsTheMutatedModelAndLeavesTheCallersFixtureAlone() throws Exception {
        String before = fixture();
        ObjectNode arguments = arguments();
        arguments.put("op", "add");
        arguments.put("model", before);
        arguments.putObject("files").put("rule-spec.json", """
                { "field": "/Order/Items/Count", "group": "/Order",
                  "condition": "GroupFilled(Items) And [Count] <= 0", "code": "MCP_COUNT_NOT_POSITIVE",
                  "messages": [ {"locale":"en_US","text":"Item count must be positive."} ] }""");

        JsonNode result = call("dm.rule.edit", arguments);

        assertFalse(result.path("isError").asBoolean(), () -> "the rule is valid: " + result);
        assertEquals("applied", result.path("structuredContent").path("outcome").asText());

        JsonNode edited = Stream.iterate(0, index -> index + 1).limit(result.path("content").size())
                .map(index -> result.path("content").get(index))
                .filter(block -> "resource".equals(block.path("type").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("no edited model came back: " + result));
        assertTrue(edited.path("resource").path("uri").asText().startsWith("dm:///"),
                () -> "an artifact is addressed in the request's own namespace: " + edited);
        assertTrue(edited.path("resource").path("text").asText().contains("MCP_COUNT_NOT_POSITIVE"),
                "the returned model carries the new rule");
        assertEquals(before, fixture(), "the caller's file is never touched");
    }

    /** A raw route has no envelope to structure, so the payload rides content and nothing is claimed. */
    @Test
    void aRawRouteReturnsItsPayloadAsTextWithNoStructuredContent() throws Exception {
        ObjectNode arguments = arguments();
        arguments.put("op", "operators");

        JsonNode result = call("dm.catalog", arguments);

        assertFalse(result.has("structuredContent"),
                "a raw payload is not the envelope the outputSchema describes");
        assertTrue(textOf(result).contains("GroupFilled"), "the catalogue itself comes back");
    }

    @Test
    void anUnknownToolIsInvalidParams() {
        McpFailure failure = org.junit.jupiter.api.Assertions.assertThrows(McpFailure.class,
                () -> call("dm.nope", arguments()));
        assertEquals(McpProtocol.INVALID_PARAMS, failure.code());
        assertTrue(failure.getMessage().contains("dm.nope"), failure::getMessage);
    }

    /**
     * The content rule, enforced rather than documented: a path-bearing parameter is not caller-settable, and
     * the refusal says why rather than letting the argument through to write somewhere on the server.
     */
    @Test
    void aPathBearingArgumentIsRefusedWithItsReason() throws Exception {
        ObjectNode arguments = arguments();
        arguments.put("op", "add");
        arguments.put("model", fixture());
        arguments.putObject("args").put("output", "/tmp/somewhere.json");

        McpFailure failure = org.junit.jupiter.api.Assertions.assertThrows(McpFailure.class,
                () -> call("dm.rule.edit", arguments));

        assertEquals(McpProtocol.INVALID_PARAMS, failure.code());
        assertTrue(failure.getMessage().contains("output"), failure::getMessage);
        assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("path"), failure::getMessage);
    }

    /** The inherited globals are path parameters too, so the same refusal covers `-m` and `-w`. */
    @Test
    void theInheritedModelAndWorkspaceOptionsAreRefusedAsPathsToo() throws Exception {
        for (String key : List.of("model", "workspace")) {
            ObjectNode arguments = arguments();
            arguments.put("op", "check");
            arguments.put("model", fixture());
            arguments.putObject("args").put(key, "/etc");

            McpFailure failure = org.junit.jupiter.api.Assertions.assertThrows(McpFailure.class,
                    () -> call("dm.model.read", arguments));

            assertEquals(McpProtocol.INVALID_PARAMS, failure.code(), () -> "for args." + key);
            assertTrue(failure.getMessage().contains(key), failure::getMessage);
        }
    }

    /**
     * A malformed invocation answers with empty stdout and the whole corrective surface on stderr. A design
     * reading only stdout would drop it silently, on the likeliest outcome of a generated call.
     */
    @Test
    void aMalformedInvocationComesBackAsAToolErrorCarryingTheCorrective() throws Exception {
        ObjectNode arguments = arguments();
        arguments.put("op", "read");
        arguments.put("model", fixture());
        arguments.putObject("args").put("no-such-flag", "x");

        JsonNode result = call("dm.rule.read", arguments);

        assertTrue(result.path("isError").asBoolean(), () -> "a usage error is a tool error: " + result);
        assertFalse(textOf(result).isBlank(), "the corrective surface reaches the caller");
    }

    /** A refusal the verb itself produces is a tool error too, but it still carries its structured envelope. */
    @Test
    void aDomainRefusalIsATooolErrorThatKeepsItsEnvelope() throws Exception {
        ObjectNode arguments = arguments();
        arguments.put("op", "read");
        arguments.put("model", fixture());
        arguments.putObject("args").put("rulePathInModel", "/Order/NoSuchRule");

        JsonNode result = call("dm.rule.read", arguments);

        assertTrue(result.path("isError").asBoolean(), () -> "a refusal is actionable feedback: " + result);
        assertEquals("refused", result.path("structuredContent").path("outcome").asText());
        assertFalse(result.path("structuredContent").path("diagnostics").isEmpty(),
                "the diagnostics come with it, so the caller can self-correct");
    }

    /**
     * A directory-shaped content parameter is refused as such. It is also what keeps {@code CustomJsWorker}
     * — the one component that registers a per-call shutdown hook and leaves a {@code node} child running to
     * process exit — unreachable from a session, since the two custom-JS directories are its only entry.
     */
    @Test
    void aDirectoryShapedContentParameterIsRefusedAsADirectory() throws Exception {
        ObjectNode arguments = arguments();
        arguments.put("op", "eval");
        arguments.put("model", fixture());
        arguments.putObject("files").put("custom-conditions-js", "export function check() { return true; }");

        McpFailure failure = org.junit.jupiter.api.Assertions.assertThrows(McpFailure.class,
                () -> call("dm.model.read", arguments));

        assertEquals(McpProtocol.INVALID_PARAMS, failure.code());
        assertTrue(failure.getMessage().contains("DIRECTORY"), failure::getMessage);
    }

    /** Nothing a call creates outlives it — the lifetime guarantee, checked rather than asserted in prose. */
    @Test
    void noRequestTreeSurvivesTheCall() throws Exception {
        List<Path> before = requestTrees();
        ObjectNode arguments = arguments();
        arguments.put("op", "check");
        arguments.put("model", fixture());

        call("dm.model.read", arguments);

        assertEquals(before, requestTrees(), "the per-request tree is deleted before the call answers");
    }

    private static List<Path> requestTrees() throws IOException {
        Path temp = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> entries = Files.list(temp)) {
            return entries.filter(path -> path.getFileName().toString().startsWith(McpInvoker.REQUEST_TREE_PREFIX))
                    .sorted().toList();
        }
    }
}
