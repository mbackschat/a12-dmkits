package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * <b>The consumer claim, against a real validator.</b> {@code CLI-SPEC} §5a states that a 2020-12 validator
 * handed only {@code schema result} — with no pre-registered siblings and no network — accepts the document and
 * enforces the embedded {@code diagnostic} definition. That was measured by hand once, which is exactly the kind
 * of claim that rots: {@code SchemaResourceSurfaceTest} only checks that an {@code $id} string appears under
 * some {@code $defs}, so it would still pass if the actual <em>resolution</em> semantics broke.
 *
 * <p>This locks it. The factory is given no schema mappings and no resource loader, so any attempt to resolve a
 * reference outside the served document fails rather than silently succeeding — which is what makes the positive
 * control below meaningful.
 */
class ServedSchemaResolvesStandaloneTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = "src/test/resources/models/order-ruled.dm.json";

    private static JsonSchema servedResultSchema() throws Exception {
        CliTestSupport.Result served = CliTestSupport.run("schema", "result");
        assertEquals(0, served.code(), served::err);
        // No mappings, no external loader: resolution must be satisfied from the document itself.
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(served.out(), SchemaValidatorsConfig.builder().build());
    }

    @Test
    void aRealEnvelopeValidatesAgainstTheServedSchemaAlone() throws Exception {
        JsonSchema schema = servedResultSchema();
        CliTestSupport.Result read = CliTestSupport.run("model", "info", "-m", MODEL);
        assertEquals(0, read.code(), read::err);
        Set<ValidationMessage> errors = schema.validate(MAPPER.readTree(read.out()));
        assertTrue(errors.isEmpty(), () -> "a real envelope must validate against the served schema with no "
                + "external resolution: " + errors);
    }

    /**
     * The control that makes the claim non-vacuous: if the embedded {@code diagnostic} definition were NOT being
     * resolved, a malformed diagnostic would pass unnoticed. It must be rejected.
     */
    @Test
    void theEmbeddedDiagnosticDefinitionIsEnforcedNotInert() throws Exception {
        JsonSchema schema = servedResultSchema();
        JsonNode malformed = MAPPER.readTree("""
                {"tool":"dmtool","outcome":"ok","valid":true,
                 "diagnostics":[{"severity":"NOT_A_SEVERITY"}]}
                """);
        Set<ValidationMessage> errors = schema.validate(malformed);
        assertFalse(errors.isEmpty(),
                "the embedded `diagnostic` definition is inert — a malformed diagnostics entry was accepted, so "
                        + "the bundled `$ref` is not actually being resolved");
    }

    /**
     * The other half of the same property: strip the bundle and the very same validator can no longer resolve
     * the reference. This is what proves the bundling is load-bearing rather than incidental — and it is the
     * measured basis for CLI-SPEC's claim that an unbundled document makes a consumer fail rather than degrade.
     */
    @Test
    void withoutTheBundleTheSameDocumentNoLongerResolves() throws Exception {
        CliTestSupport.Result served = CliTestSupport.run("schema", "result");
        assertEquals(0, served.code(), served::err);
        var stripped = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(served.out());
        assertTrue(stripped.has("$defs"), "the served document must carry the bundle for this control to mean anything");
        stripped.remove("$defs");

        JsonNode malformed = MAPPER.readTree("""
                {"tool":"dmtool","outcome":"ok","valid":true,
                 "diagnostics":[{"severity":"NOT_A_SEVERITY"}]}
                """);
        boolean unresolvable;
        try {
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(stripped, SchemaValidatorsConfig.builder().build());
            // Either construction or validation must fail; a clean pass would mean the reference was never
            // being enforced in the first place, making the bundle pointless.
            unresolvable = !schema.validate(malformed).isEmpty();
        } catch (RuntimeException e) {
            unresolvable = true;
        }
        assertTrue(unresolvable, "stripping the bundle left the document still validating cleanly, so the "
                + "embedded referent was never load-bearing");
    }
}
