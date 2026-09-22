package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
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

    /**
     * <b>A request carries as many models as the subject needs</b> — and the server names every file itself,
     * because a document model's identity is <em>inside</em> its content (`header.id`, which
     * {@code ReferenceResolvers} indexes on) rather than in its file name. That is what lets `references`
     * exist at all, and it is the exact property a directory of {@code <Name>.js} implementations lacks
     * ([MCP-GAPS MG8]).
     *
     * <p>The first half is the positive control: without the referenced model the same call must FAIL
     * legibly, or "it resolved" would prove nothing about what was carried.
     */
    @Test
    void carriedReferencesResolveByTheirContentNotTheirFileName() throws Exception {
        String subject = Files.readString(Path.of("src/test/resources/models/multifile/app/storefront.dm.json"));
        String referenced = Files.readString(Path.of("src/test/resources/models/multifile/lib/catalog.dm.json"));

        ObjectNode alone = arguments();
        alone.put("op", "check");
        alone.put("model", subject);
        JsonNode unresolved = call("dm.model.read", alone);
        assertTrue(unresolved.path("isError").asBoolean(), () -> "an absent reference must fail: " + unresolved);
        assertEquals("RK_UNRESOLVED_REFERENCE",
                unresolved.path("structuredContent").path("diagnostics").get(0).path("code").asText(),
                "and it fails as a missing reference, not as something else");

        ObjectNode carried = arguments();
        carried.put("op", "check");
        carried.put("model", subject);
        carried.putArray("references").add(referenced);
        JsonNode resolved = call("dm.model.read", carried);

        assertFalse(resolved.path("isError").asBoolean(), () -> "the carried model resolves it: " + resolved);
        assertTrue(resolved.path("structuredContent").path("valid").asBoolean(),
                () -> "and the whole multi-model subject is kernel-valid: " + resolved.path("structuredContent"));
    }

    /**
     * <b>Type definitions resolve from a carried model, and an edit may INTRODUCE the reference.</b> This is
     * the case the design record singled out: `typedef import` mounts a model the subject does not yet
     * reference, so the needed model is by construction outside the subject's pre-edit closure. A design that
     * carried only "the subject and what it already references" could not express it at all.
     */
    @Test
    void aReferenceIntroducingEditResolvesAgainstACarriedModel() throws Exception {
        String subject = Files.readString(Path.of("src/test/resources/models/order.dm.json"));
        String types = Files.readString(Path.of("src/test/resources/models/catalogtypes.dm.json"));

        ObjectNode arguments = arguments();
        arguments.put("op", "import");
        arguments.put("model", subject);
        arguments.putArray("references").add(types);
        arguments.putObject("args").put("reference", "catalogtypes");

        JsonNode result = call("dm.typedef.edit", arguments);

        assertFalse(result.path("isError").asBoolean(), () -> "the import applies: " + result);
        assertEquals("catalogtypes", result.path("structuredContent").path("changed").path("imported").asText());
        assertTrue(MAPPER.writeValueAsString(result).contains("dm:///out/subject.dm.json"),
                "and the edited model comes back");
    }

    /** A model that imports type definitions validates only when the definitions are carried with it. */
    @Test
    void aTypeDefinitionImportNeedsItsDefinitionsCarried() throws Exception {
        String subject = Files.readString(Path.of("src/test/resources/models/order-imports-types.dm.json"));
        String types = Files.readString(Path.of("src/test/resources/models/catalogtypes.dm.json"));

        ObjectNode alone = arguments();
        alone.put("op", "check");
        alone.put("model", subject);
        assertEquals("RK_UNRESOLVED_REFERENCE",
                call("dm.model.read", alone).path("structuredContent").path("diagnostics").get(0)
                        .path("code").asText(),
                "without them it fails as a missing reference, loudly");

        ObjectNode carried = arguments();
        carried.put("op", "check");
        carried.put("model", subject);
        carried.putArray("references").add(types);
        assertTrue(call("dm.model.read", carried).path("structuredContent").path("valid").asBoolean(),
                "with them the imported types resolve and the model is kernel-valid");
    }

    /**
     * <b>The differential that settles content-carrying: a carried workspace expands to the same bytes as a
     * real one.</b> `model expand` resolves the whole include closure into one self-contained model, so it
     * reads everything a multi-file subject depends on. Running it over MCP against carried `references` and
     * on disk against the fixture directory must produce identical output — anything else would mean the
     * carried path resolves differently from the path every other consumer uses, which no assertion about
     * individual fields would reliably catch.
     */
    @Test
    void aCarriedWorkspaceExpandsToTheSameBytesAsARealOne() throws Exception {
        Path app = Path.of("src/test/resources/models/multifile/app/storefront.dm.json");
        Path lib = Path.of("src/test/resources/models/multifile/lib/catalog.dm.json");

        ObjectNode arguments = arguments();
        arguments.put("op", "expand");
        arguments.put("model", Files.readString(app));
        arguments.putArray("references").add(Files.readString(lib));
        String overMcp = textOf(call("dm.model.generate", arguments));

        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        int exit = Cli.run(new String[] {"-m", app.toString(), "-w", "src/test/resources/models/multifile",
                "model", "expand"}, out, err);

        assertEquals(0, exit, err::toString);
        assertEquals(out.toString(), overMcp,
                "the carried closure must expand exactly as the on-disk one");
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

    /**
     * A <b>variadic</b> positional takes an array. `schema <schema>...` addresses a verb contract as two
     * tokens of one parameter, so stringifying the array — which is what a single-value path does to a JSON
     * array — silently passed an empty argument and made every per-verb schema unreachable from a session.
     * That is the path MCP-SPEC §8 relies on for a caller's field-level facts.
     */
    @Test
    void aVariadicPositionalTakesEachElementAsItsOwnToken() throws Exception {
        ObjectNode arguments = arguments();
        arguments.put("op", "schema");
        arguments.putObject("args").putArray("schema").add("rule").add("add");

        JsonNode contract = MAPPER.readTree(textOf(call("dm.catalog", arguments)));

        assertEquals("rule add", contract.path("op").asText(), "the two tokens addressed one verb contract");
        assertTrue(contract.path("input").isObject(), "and it is the verb's real input schema");
    }

    @Test
    void anUnknownToolIsInvalidParams() {
        McpFailure failure = Assertions.assertThrows(McpFailure.class,
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

        McpFailure failure = Assertions.assertThrows(McpFailure.class,
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

            McpFailure failure = Assertions.assertThrows(McpFailure.class,
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

        McpFailure failure = Assertions.assertThrows(McpFailure.class,
                () -> call("dm.model.read", arguments));

        assertEquals(McpProtocol.INVALID_PARAMS, failure.code());
        assertTrue(failure.getMessage().contains("DIRECTORY"), failure::getMessage);
    }

    /**
     * The framer owns the output stream: a verb's own output must never reach it, or one stray line
     * corrupts a frame and — on a resident server — kills the whole session rather than one call. Supplying
     * {@code Cli.run} its own sinks is what prevents it; this is the check that the wiring actually does.
     *
     * <p>Deliberately driven by a verb that writes a large payload to stdout, so a leak would be unmissable.
     */
    @Test
    void aCallWritesNothingToTheProcessStdout() throws Exception {
        PrintStream real = System.out;
        ByteArrayOutputStream leaked = new ByteArrayOutputStream();
        ObjectNode arguments = arguments();
        arguments.put("op", "operators");
        try {
            System.setOut(new PrintStream(leaked, true, StandardCharsets.UTF_8));
            call("dm.catalog", arguments);
        } finally {
            System.setOut(real);
        }
        assertEquals("", leaked.toString(StandardCharsets.UTF_8),
                "the verb's payload belongs in the response frame, never on the framer's stream");
    }

    /**
     * <b>The closed-world gate FIRES.</b> A verb whose correctness needs the whole workspace is refused
     * without the authority assertion — the does-fire half the refactoring-safety rule requires, because a
     * gate that only works on the happy path is not a gate.
     *
     * <p>`model rename` is the sharp case: it refuses when a sibling includes the old id, so on a partial
     * tree it would find nothing and PERMIT the rename. That failure is invisible — it looks like success.
     */
    @Test
    void aClosedWorldVerbIsRefusedWithoutTheAuthorityAssertion() throws Exception {
        ObjectNode arguments = arguments();
        arguments.put("op", "rename");
        arguments.put("model", fixture());
        arguments.putObject("args").put("to", "renamed");

        McpFailure refusal = Assertions.assertThrows(McpFailure.class,
                () -> call("dm.model.edit", arguments));

        assertEquals(McpProtocol.INVALID_PARAMS, refusal.code());
        assertTrue(refusal.getMessage().contains("authoritativeWorkspace"),
                () -> "the refusal names what to assert: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("COMPLETE"),
                () -> "and what the assertion means: " + refusal.getMessage());
    }

    /** Under the assertion it runs — otherwise the gate would be a permanent exclusion wearing a flag. */
    @Test
    void underTheAssertionAWorkspaceVerbAnswersOverTheCarriedTree() throws Exception {
        ObjectNode arguments = arguments();
        arguments.put("op", "check");
        arguments.put("model", fixture());
        arguments.put(McpToolRegistry.AUTHORITY_ASSERTION, true);

        JsonNode result = call("dm.workspace.read", arguments);

        assertFalse(result.path("isError").asBoolean(), () -> "the carried tree checks out: " + result);
        assertEquals("read", result.path("structuredContent").path("outcome").asText());
    }

    /**
     * <b>A multi-file refactor returns every file it produced, and names none of them by a server path.</b>
     * `group extract` writes a NEW sub-model beside the subject, so both come back — and its receipt used to
     * publish the sub-model's absolute location inside the request tree, which is meaningless to a caller who
     * supplied no path and discloses the server's layout. The receipt now names the artifact the response
     * already carries.
     */
    @Test
    void aRefactorThatCreatesAModelReturnsBothAndLeaksNoServerPath() throws Exception {
        ObjectNode arguments = arguments();
        arguments.put("op", "extract");
        arguments.put("model", Files.readString(Path.of("src/test/resources/models/order.dm.json")));
        arguments.putObject("args").put("groupPathInModel", "/Order/ShippingAddress")
                .put("reference", "shipping-address");

        JsonNode result = call("dm.group.edit", arguments);

        assertFalse(result.path("isError").asBoolean(), () -> "the extract applies: " + result);
        List<String> returned = new java.util.ArrayList<>();
        result.path("content").forEach(block -> {
            if ("resource".equals(block.path("type").asText())) {
                returned.add(block.path("resource").path("uri").asText());
            }
        });
        assertEquals(List.of("dm:///out/shipping-address.dm.json", "dm:///out/subject.dm.json"),
                returned.stream().sorted().toList(),
                "the new sub-model AND the rewritten subject both come back");

        String whole = MAPPER.writeValueAsString(result);
        assertFalse(whole.contains(McpInvoker.REQUEST_TREE_PREFIX),
                () -> "no response may name the server's request tree: " + whole);
        assertEquals("dm:///out/shipping-address.dm.json",
                result.path("structuredContent").path("changed").path("subModel").asText(),
                "the receipt names the artifact the caller received");
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
