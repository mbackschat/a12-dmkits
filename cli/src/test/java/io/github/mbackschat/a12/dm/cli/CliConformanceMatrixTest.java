package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * The self-explain conformance matrix (CLI-CONFORMANCE-SPEC §"the matrix") — the single derived gate that
 * makes "is this verb actually surfaced?" one green/red signal, so a newly-added verb cannot ship
 * under-described (AP5) or with an undiscoverable I/O shape. It walks the live {@code manifest} and asserts a
 * per-verb obligation row; each obligation is a column, so a newly-learned anti-pattern is a one-column change.
 *
 * <p><b>Columns (this is the single source — it subsumes the old {@code ManifestCompletenessTest}):</b>
 * <ul>
 *   <li><b>described</b> — every verb has a non-blank capability description.</li>
 *   <li><b>params-described</b> — every param has a non-blank description.</li>
 *   <li><b>schema-resolves</b> — every <em>envelope</em> verb advertises a {@code schema} pointer that
 *       resolves to real JSON. The non-envelope verbs ({@link SchemaCommand#CONTRACT_EXEMPT}) are the only
 *       exemption, and it is a <em>reasoned, honest</em> one — every exempt verb exists and carries a reason.</li>
 * </ul>
 * Each obligation is a column; a newly-learned anti-pattern becomes one more column here (the next is a
 * CI-executed worked invocation per verb — the "in action" demonstration). {@code ManifestVerbTest} (the
 * verb-list pin) and {@code ParamKindManifestTest} (the kind spot-checks) stay — they pin <em>which</em>
 * verbs/kinds exist; this matrix proves each is fully surfaced.
 */
class CliConformanceMatrixTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void everyVerbIsFullyDescribedAndEnvelopeVerbsHaveAResolvableContract() throws Exception {
        JsonNode manifest = M.readTree(CliTestSupport.run("manifest").out());
        for (JsonNode v : manifest.path("verbs")) {
            String verb = v.path("verb").asText();

            assertFalse(v.path("description").asText().isBlank(), () -> verb + " needs a capability description");
            for (JsonNode p : v.path("params")) {
                assertFalse(p.path("description").asText().isBlank(),
                        () -> verb + " param '" + p.path("key").asText() + "' needs a description");
            }

            if (SchemaCommand.CONTRACT_EXEMPT.containsKey(verb)) {
                continue; // a non-envelope verb — discovered by running it; see theExemptionLedgerIsHonest()
            }
            assertTrue(v.hasNonNull("schema"),
                    () -> verb + " returns the result envelope but advertises no `schema` pointer "
                            + "(add it in ManifestCommand, or classify the verb in SchemaCommand.CONTRACT_EXEMPT)");
            String[] schemaArgs = ("schema " + v.path("schema").asText()).split(" ");
            CliTestSupport.Result res = CliTestSupport.run(schemaArgs);
            assertEquals(0, res.code(),
                    () -> verb + " advertises schema '" + v.path("schema").asText() + "' but `"
                            + String.join(" ", schemaArgs) + "` failed: " + res.err());
            M.readTree(res.out()); // a usable contract, real JSON — not an error blob
        }
    }

    @Test
    void theExemptionLedgerIsHonest() throws Exception {
        // every exemption is real (no stale row) and reasoned (the ledger documents *why* it has no contract).
        JsonNode manifest = M.readTree(CliTestSupport.run("manifest").out());
        Set<String> verbs = new HashSet<>();
        manifest.path("verbs").forEach(v -> verbs.add(v.path("verb").asText()));
        SchemaCommand.CONTRACT_EXEMPT.forEach((verb, reason) -> {
            assertTrue(verbs.contains(verb), () -> "CONTRACT_EXEMPT names '" + verb + "', absent from the manifest (stale)");
            assertFalse(reason.isBlank(), () -> "CONTRACT_EXEMPT '" + verb + "' needs a reason");
        });
    }
}
