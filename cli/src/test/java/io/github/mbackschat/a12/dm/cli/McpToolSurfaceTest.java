package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/**
 * The conformance guard for the tool projection (MCP-SPEC §6): the derived surface must agree with the live
 * command tree it is derived from, and neither published fact may be false for any op a tool covers.
 *
 * <p>The oracle is the tool's own {@code manifest}, not a second copy of the partition rule — a test that
 * re-implemented the grouping would agree with the registry by construction and prove nothing.
 */
class McpToolSurfaceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CommandSpec liveRoot() {
        return new CommandLine(new RootCommand()).getCommandSpec();
    }

    private static JsonNode manifest() throws Exception {
        return MAPPER.readTree(CliTestSupport.run("manifest").out());
    }

    /**
     * The tool's ops resolved back to live verb names. A tool name is {@code dm.<group>[.<suffix>]}, and the
     * group is the op-record target for a nested verb; a root verb is its own group, so its op is already a
     * whole verb name.
     */
    private static List<String> verbsOf(McpToolRegistry.McpTool tool, JsonNode manifest) {
        Set<String> live = new HashSet<>();
        manifest.path("verbs").forEach(verb -> live.add(verb.path("verb").asText()));
        String group = tool.name().substring("dm.".length()).split("\\.")[0];
        return tool.ops().stream().map(op -> live.contains(op) ? op : group + " " + op).toList();
    }

    private static JsonNode manifestVerb(JsonNode manifest, String verb) {
        for (JsonNode candidate : manifest.path("verbs")) {
            if (verb.equals(candidate.path("verb").asText())) {
                return candidate;
            }
        }
        throw new AssertionError("the manifest has no verb " + verb);
    }

    /**
     * The op union is the live leaf set minus the stated exclusions — no duplicate, no omission, and no
     * silent hole. This is the assertion that makes the projection derived rather than authored.
     */
    @Test
    void theOpUnionIsExactlyTheLiveTreeMinusTheStatedExclusions() throws Exception {
        Set<String> live = new TreeSet<>();
        for (JsonNode verb : manifest().path("verbs")) {
            live.add(verb.path("verb").asText());
        }
        Set<String> covered = new TreeSet<>();
        List<String> duplicates = new ArrayList<>();
        for (McpToolRegistry.McpTool tool : McpToolRegistry.tools(liveRoot())) {
            for (String verb : verbsOf(tool, manifest())) {
                if (!covered.add(verb)) {
                    duplicates.add(verb);
                }
            }
        }
        assertEquals(List.of(), duplicates, "an op may appear under exactly one tool");

        Set<String> expected = new TreeSet<>(live);
        expected.removeAll(McpToolRegistry.EXCLUDED_VERBS.keySet());
        assertEquals(expected, covered, "the tool surface is the live tree minus the exclusion set");
    }

    /** An exclusion must name a verb that exists and say why — a stale or bare row is a silent hole. */
    @Test
    void everyExclusionResolvesAndCarriesItsReason() throws Exception {
        Set<String> live = new HashSet<>();
        manifest().path("verbs").forEach(verb -> live.add(verb.path("verb").asText()));
        McpToolRegistry.EXCLUDED_VERBS.forEach((verb, reason) -> {
            assertTrue(live.contains(verb), () -> "the exclusion names '" + verb + "', absent from the tree");
            assertFalse(reason.isBlank(), () -> "the exclusion of '" + verb + "' needs a reason");
        });
    }

    /**
     * {@code readOnlyHint} is true only where every profile of every op the tool covers is {@code READ_ONLY}.
     * Checked in both directions against the manifest, so neither an over- nor an under-claim can pass.
     */
    @Test
    void theReadOnlyHintIsTrueExactlyWhereEveryProfileOfEveryOpIsReadOnly() throws Exception {
        JsonNode manifest = manifest();
        for (McpToolRegistry.McpTool tool : McpToolRegistry.tools(liveRoot())) {
            boolean everyOpReadOnly = true;
            for (String verb : verbsOf(tool, manifest)) {
                for (JsonNode profile : manifestVerb(manifest, verb).path("operationalProfiles")) {
                    everyOpReadOnly &= "READ_ONLY".equals(profile.path("effect").asText());
                }
            }
            assertEquals(everyOpReadOnly, tool.readOnly(),
                    () -> tool.name() + " publishes readOnlyHint=" + tool.readOnly()
                            + " over ops " + tool.ops());
        }
    }

    /**
     * MCP requires that a declared {@code outputSchema} be conformed to by <em>every</em> structured result,
     * so it may be declared only where every profile of every op returns the envelope — and it must be the
     * SERVED document, whose referents are bundled, or a consumer cannot resolve it.
     */
    @Test
    void outputSchemaIsDeclaredExactlyWhereEveryRouteReturnsTheEnvelopeAndResolvesAlone() throws Exception {
        JsonNode manifest = manifest();
        for (McpToolRegistry.McpTool tool : McpToolRegistry.tools(liveRoot())) {
            boolean everyRouteEnvelope = true;
            for (String verb : verbsOf(tool, manifest)) {
                for (JsonNode profile : manifestVerb(manifest, verb).path("operationalProfiles")) {
                    everyRouteEnvelope &= "RESULT_ENVELOPE".equals(profile.path("returns").path("kind").asText());
                }
            }
            assertEquals(everyRouteEnvelope, tool.outputSchema() != null,
                    () -> tool.name() + " declares outputSchema over ops " + tool.ops());
            if (tool.outputSchema() != null) {
                assertTrue(tool.outputSchema().path("$defs").has("diagnostic"),
                        () -> tool.name() + " must publish the SERVED schema, with its referents embedded");
            }
        }
    }

    /**
     * <b>Stripping the prose changed nothing that validates.</b> The declared {@code outputSchema} carries no
     * `description`/`examples` — 81 % of the served document, repeated per tool — and MCP requires structured
     * results to conform to what is declared. Annotations assert nothing, so this must still be true, and the
     * only way to know it is to run a real validator against the copy that actually ships.
     *
     * <p>The second case is the control that makes the first non-vacuous: if the embedded `diagnostic`
     * definition stopped being enforced, a malformed diagnostic would pass and the first assertion would
     * still be green.
     */
    @Test
    void theLeanOutputSchemaStillValidatesExactlyWhatTheServedOneDoes() throws Exception {
        JsonNode declared = McpToolRegistry.tools(liveRoot()).stream()
                .filter(tool -> tool.name().equals("dm.model.read")).findFirst().orElseThrow().outputSchema();
        com.networknt.schema.JsonSchema schema = com.networknt.schema.JsonSchemaFactory
                .getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
                .getSchema(MAPPER.writeValueAsString(declared),
                        com.networknt.schema.SchemaValidatorsConfig.builder().build());

        CliTestSupport.Result read = CliTestSupport.run("model", "info",
                "-m", "src/test/resources/models/order-ruled.dm.json");
        assertEquals(0, read.code(), read::err);
        assertTrue(schema.validate(MAPPER.readTree(read.out())).isEmpty(),
                "a real envelope validates against the declared copy, with no external resolution");

        JsonNode malformed = MAPPER.readTree(read.out());
        ((com.fasterxml.jackson.databind.node.ObjectNode) malformed)
                .putArray("diagnostics").addObject().put("severity", "NOT_A_SEVERITY");
        assertFalse(schema.validate(malformed).isEmpty(),
                "the embedded diagnostic definition is still enforced, not inert");
    }

    /** The prose is gone from the definition but not from the tool: the copy says where to find it. */
    @Test
    void theLeanSchemaPointsAtTheFullContract() {
        JsonNode declared = McpToolRegistry.tools(liveRoot()).stream()
                .filter(tool -> tool.outputSchema() != null).findFirst().orElseThrow().outputSchema();

        assertTrue(declared.path("description").asText().contains("dm.catalog"),
                () -> "a caller must be able to reach the field-by-field contract: " + declared.path("description"));
    }

    /**
     * The surface pinned as a denominator. Not a restatement of the rule: it is what makes a grouping change
     * visible in review, the same job {@code ManifestVerbTest}'s verb pin does for the tree.
     */
    @Test
    void theSurfaceIsThirtyToolsOfWhichFourteenAreReadOnly() {
        List<McpToolRegistry.McpTool> tools = McpToolRegistry.tools(liveRoot());
        // 30/14/24 is the whole live tree minus the `mcp` leaf itself: the closed-world verbs rejoined the
        // surface when the authority assertion gave them a safe way to run, so nothing is held back now
        // except the server's own leaf.
        assertEquals(30, tools.size(), () -> "tools: " + tools.stream().map(McpToolRegistry.McpTool::name).toList());
        assertEquals(14, tools.stream().filter(McpToolRegistry.McpTool::readOnly).count(), "read-only tools");
        assertEquals(24, tools.stream().filter(tool -> tool.outputSchema() != null).count(),
                "tools declaring outputSchema");
    }

    /** Names are a public contract: unique, ordered, and within the character set the spec allows. */
    @Test
    void namesAreUniqueDeterministicAndLegal() {
        List<String> names = McpToolRegistry.tools(liveRoot()).stream()
                .map(McpToolRegistry.McpTool::name).toList();
        assertEquals(names.stream().sorted().toList(), names, "tools are returned in deterministic order");
        assertEquals(names.size(), Set.copyOf(names).size(), "tool names are unique within a server");
        names.forEach(name -> {
            assertTrue(name.matches("[A-Za-z0-9_.-]{1,128}"), () -> "illegal tool name: " + name);
            assertTrue(name.startsWith("dm."), () -> "unprefixed tool name: " + name);
        });
    }

    /** A tool's description carries its op list, because that is the text a retrieval strategy ranks. */
    @Test
    void everyToolDescribesItselfAndItsOps() {
        for (McpToolRegistry.McpTool tool : McpToolRegistry.tools(liveRoot())) {
            assertFalse(tool.description().isBlank(), () -> tool.name() + " needs a description");
            tool.ops().forEach(op -> assertTrue(tool.description().contains(op),
                    () -> tool.name() + " does not name its op '" + op + "': " + tool.description()));
        }
    }

    /**
     * The route-level facts MCP cannot put in an annotation ride the {@code op} enum instead, derived from
     * the registry rather than written — a multi-op tool's schema must carry one branch per op.
     */
    @Test
    void aMultiOpToolPublishesEachRoutesEffectOnItsOpEnum() {
        McpToolRegistry.McpTool ruleEdit = McpToolRegistry.tools(liveRoot()).stream()
                .filter(tool -> tool.name().equals("dm.rule.edit")).findFirst().orElseThrow();

        JsonNode branches = ruleEdit.inputSchema().path("properties").path("op").path("oneOf");
        assertEquals(ruleEdit.ops().size(), branches.size(), "one branch per op");
        for (JsonNode branch : branches) {
            assertTrue(branch.path("description").asText().contains("MODEL_WRITE"),
                    () -> "an editing route publishes its effect: " + branch);
            assertTrue(branch.path("description").asText().contains("dryRun="),
                    () -> "an op branch publishes its dry-run behaviour: " + branch);
        }
        assertTrue(ruleEdit.inputSchema().path("properties").has("model"),
                "a model-bound tool asks for the model's content, never its path");
    }

    /**
     * A tool asks for a model only where its ops <b>consume</b> one. `-m` is inherited by every leaf, so the
     * naive test made even a catalogue lookup advertise a `model` property it ignores — inviting a caller to
     * attach an 11 KB document to `operators`.
     */
    @Test
    void onlyModelConsumingToolsAskForAModel() {
        Map<String, Boolean> asksForModel = new java.util.TreeMap<>();
        McpToolRegistry.tools(liveRoot()).forEach(tool ->
                asksForModel.put(tool.name(), tool.inputSchema().path("properties").has("model")));

        assertEquals(false, asksForModel.get("dm.catalog"), "the self-describing verbs consume no model");
        assertEquals(true, asksForModel.get("dm.model.read"), "a read of a model needs the model");
        assertEquals(true, asksForModel.get("dm.rule.edit"), "so does an edit");
        // A mixed cell asks, and must: `dm.model.generate` covers `model new`, which makes a model, together
        // with `expand`/`import-jsonschema`/`export-jsonschema`, which read one. MCP's granularity is the
        // tool, so the property has to be offered for the three — `model new` simply ignores it. The
        // discrimination that matters is that a tool with NO model-reading op does not ask at all.
        assertEquals(true, asksForModel.get("dm.model.generate"),
                "three of its four ops read a model, so the tool must offer it");
        // And where a tool asks for a model it also accepts the models that model references, or a
        // multi-file subject could never resolve.
        McpToolRegistry.tools(liveRoot()).stream()
                .filter(tool -> tool.inputSchema().path("properties").has("model"))
                .forEach(tool -> assertTrue(tool.inputSchema().path("properties").has("references"),
                        () -> tool.name() + " takes a model but no way to carry what it references"));
    }

    /** No tool asks for a filesystem path: that is the whole content rule, checked over the whole surface. */
    @Test
    void noInputSchemaOffersAPathProperty() {
        for (McpToolRegistry.McpTool tool : McpToolRegistry.tools(liveRoot())) {
            JsonNode properties = tool.inputSchema().path("properties");
            for (String forbidden : List.of("path", "file", "model_path", "workspace", "output", "out-dir")) {
                assertFalse(properties.has(forbidden),
                        () -> tool.name() + " offers a path-shaped property '" + forbidden + "'");
            }
        }
    }
}
