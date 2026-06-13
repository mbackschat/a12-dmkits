package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Map;
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
 *   <li><b>demonstrated</b> — every verb carries a worked invocation the agent can see <em>in action</em>:
 *       a {@code verb-examples.json} entry surfaced in the manifest as {@code example}, OR a schema input
 *       example (the rich-input verbs), OR a reasoned row in {@link #DEMONSTRATION_PENDING}. The example is
 *       CI-executed by {@link VerbDemonstrationTest}.</li>
 * </ul>
 * Each obligation is a column; a newly-learned anti-pattern becomes one more column here. {@code
 * ManifestVerbTest} (the verb-list pin) and {@code ParamKindManifestTest} (the kind spot-checks) stay — they
 * pin <em>which</em> verbs/kinds exist; this matrix proves each is fully surfaced.
 */
class CliConformanceMatrixTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** The self-describing verbs ARE the demonstration surface — running them is the demo; no example needed. */
    private static final Set<String> DEMO_EXEMPT = Set.of("manifest", "schema", "operators", "diagnostics");

    /**
     * Reasoned debt: a verb whose <em>happy-path</em> demonstration needs a fixture the corpus doesn't have
     * yet. CI-locked + shrinking, like the MVK ledger — the gate still fires for any <em>new</em> verb (it's
     * neither demonstrated nor listed here), and adding a row is a deliberate, reviewed, reasoned act.
     */
    static final Map<String, String> DEMONSTRATION_PENDING = Map.of(
            "typedef inline", "happy path needs a committed typedef-import fixture pair (a model importing another's type defs)",
            "typedef import", "happy path needs a committed typedef-import fixture pair (a model importing another's type defs)");

    /** Demonstrated via the verb's own schema input example (CI-executed by SchemaExamplesGuardTest), not verb-examples.json. */
    private static boolean schemaDemonstrated(String verb) {
        return SchemaCommand.INPUT_OPS.containsKey(verb) || SchemaCommand.FRAMES.contains(verb);
    }

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
    void everyVerbIsDemonstratedInAction() throws Exception {
        JsonNode manifest = M.readTree(CliTestSupport.run("manifest").out());
        for (JsonNode v : manifest.path("verbs")) {
            String verb = v.path("verb").asText();
            if (DEMO_EXEMPT.contains(verb) || schemaDemonstrated(verb) || DEMONSTRATION_PENDING.containsKey(verb)) {
                continue;
            }
            assertTrue(v.path("example").hasNonNull("command"),
                    () -> verb + " has no worked demonstration — add it to verb-examples.json so the agent sees it "
                            + "in action (or classify it in DEMONSTRATION_PENDING with a reason)");
        }
    }

    @Test
    void theLedgersAreHonest() throws Exception {
        // every exemption / pending row is real (no stale row) and reasoned (documents *why*).
        JsonNode manifest = M.readTree(CliTestSupport.run("manifest").out());
        Set<String> verbs = new HashSet<>();
        manifest.path("verbs").forEach(v -> verbs.add(v.path("verb").asText()));
        SchemaCommand.CONTRACT_EXEMPT.forEach((verb, reason) -> {
            assertTrue(verbs.contains(verb), () -> "CONTRACT_EXEMPT names '" + verb + "', absent from the manifest (stale)");
            assertFalse(reason.isBlank(), () -> "CONTRACT_EXEMPT '" + verb + "' needs a reason");
        });
        DEMONSTRATION_PENDING.forEach((verb, reason) -> {
            assertTrue(verbs.contains(verb), () -> "DEMONSTRATION_PENDING names '" + verb + "', absent from the manifest (stale)");
            assertFalse(reason.isBlank(), () -> "DEMONSTRATION_PENDING '" + verb + "' needs a reason");
        });
    }
}
