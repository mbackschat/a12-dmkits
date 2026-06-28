package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;

/**
 * Anti-rot guard #3 (the output-shape seam): a served data-schema must describe what the verb actually
 * emits. {@code schema <target> <op>} surfaces a hand-authored {@code dataSchema} for a read's payload, but
 * nothing validated that payload against real output — so a schema could describe a <em>mode the verb never
 * produces</em>. That is the shape half of the {@code rule read --rule/--all} rot: read.schema declared the
 * whole-model {@code rules}/{@code computations}/{@code summary} shape that only {@code model read} emits.
 * The flag-parity guard caught the flag mentions; this catches the shape:
 * <ul>
 *   <li><b>forward</b> — every key the verb emits in {@code data} is a property the schema declares
 *       (via {@link SpecKeys}, the same closed-keys walk input validation uses);</li>
 *   <li><b>reverse</b> — every {@code oneOf} branch the schema declares is satisfiable by a real run (no dead
 *       mode), or is an explicitly reasoned {@link #EXEMPT_BRANCHES} (e.g. the tolerant-Opaque branch that
 *       needs an unmodeled construct to trigger).</li>
 * </ul>
 * Verbs whose payload needs a heavier fixture than this audit builds are listed in {@link #EXEMPT_SCHEMAS}
 * with the test that does exercise their output.
 */
class DataSchemaConformanceTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** Data-schemas whose verb needs a fixture beyond this audit's scope — exercised by their own verb test. */
    private static final Map<String, String> EXEMPT_SCHEMAS = Map.of(
            "workspace-graph", "needs a multi-model inheritance-graph dir; shape exercised by WorkspaceVerbTest",
            "workspace-roles", "needs roles/users file fixtures; shape exercised by WorkspaceRolesTest");
    // (`model check` / `rule check` used to map here as `validate` but emit NO `data` — they are now
    // VERDICT_OPS in SchemaCommand: their result rides the envelope's valid + diagnostics, so they're out of
    // this data-shape audit entirely, not exempt-within-it.)

    /** oneOf branch titles that can't be produced from a well-formed fixture (need an unmodeled construct). */
    private static final Set<String> EXEMPT_BRANCHES = Set.of("not explained (tolerant Opaque)");

    @Test
    void everyServedDataSchemaConformsToRealOutput(@TempDir Path dir) throws Exception {
        Path sub = CliTestSupport.modelWithRule(dir);
        Path order = CliTestSupport.orderRuled(dir);
        Path docDir = Files.createDirectories(dir.resolve("docs"));
        Path doc = docDir.resolve("doc.json");
        Files.writeString(doc, """
                { "fields": { "Order": { "CustomerName": "Acme", "Quantity": "5",
                                          "OrderDate": "2026-01-01", "DeliveryDate": "2026-01-10" } } }""");

        // a model carrying a type definition, so `typedef read <id>` / `typedef read` produce both branches
        Path td = dir.resolve("td.dm.json");
        CliTestSupport.run("model", "new", "--id", "td", "--locale", "en_US", "--root", "R", "-o", td.toString());
        CliTestSupport.run("-m", td.toString(), "typedef", "add", "--id", "Money", "--kind", "NUMBER");

        // schemaName -> the data payloads real runs produced (one per documented mode)
        Map<String, List<JsonNode>> emitted = new LinkedHashMap<>();
        List<String> violations = new ArrayList<>();

        record Run(String schema, String[] args) {}
        List<Run> runs = List.of(
                new Run("model-info", new String[]{"-m", order.toString(), "model", "info"}),
                new Run("describe", new String[]{"-m", order.toString(), "model", "describe"}),
                new Run("model-usage", new String[]{"-m", order.toString(), "model", "usage"}),
                new Run("read", new String[]{"-m", sub.toString(), "rule", "read", CliTestSupport.RULE_FULL_NAME}),
                new Run("read", new String[]{"-m", sub.toString(), "rule", "read"}),
                new Run("explain", new String[]{"-m", sub.toString(), "rule", "explain", CliTestSupport.RULE_FULL_NAME}),
                new Run("where-used", new String[]{"-m", order.toString(), "where-used", "/Order/Quantity"}),
                new Run("field-read", new String[]{"-m", order.toString(), "field", "read", "/Order/Quantity"}),
                new Run("field-read", new String[]{"-m", order.toString(), "field", "read"}),
                new Run("group-read", new String[]{"-m", order.toString(), "group", "read", "/Order/Items"}),
                new Run("group-read", new String[]{"-m", order.toString(), "group", "read"}),
                new Run("typedef-read", new String[]{"-m", td.toString(), "typedef", "read", "Money"}),
                new Run("typedef-read", new String[]{"-m", td.toString(), "typedef", "read"}),
                new Run("eval-doc", new String[]{"-m", order.toString(), "model", "eval", "--instance", doc.toString()}),
                new Run("compute-doc", new String[]{"-m", order.toString(), "model", "compute", "--instance", doc.toString()}),
                new Run("workspace-list", new String[]{"workspace", "list", dir.toString()}));

        for (Run run : runs) {
            CliTestSupport.Result res = CliTestSupport.run(run.args());
            if (res.code() != 0) {
                violations.add(run.schema + ": the run exited " + res.code() + " — " + String.join(" ", run.args())
                        + "\n      " + res.err());
                continue;
            }
            JsonNode data = M.readTree(res.out()).path("data");
            if (data.isMissingNode() || !data.isObject()) {
                violations.add(run.schema + ": no `data` object in the output of " + String.join(" ", run.args()));
                continue;
            }
            // forward: every emitted key is declared by the schema (closed-keys walk)
            List<Diagnostic> undeclared = SpecKeys.forSchema(run.schema()).validate(data);
            for (Diagnostic d : undeclared) {
                violations.add(run.schema + ".schema.json does not declare a key the verb emits — "
                        + d.where().path().orElse("?") + "  (run: " + String.join(" ", run.args()) + ")");
            }
            emitted.computeIfAbsent(run.schema(), k -> new ArrayList<>()).add(data);
        }

        // reverse: every declared oneOf branch must be satisfiable by some run (no dead mode)
        for (String schema : emitted.keySet()) {
            for (String branch : unsatisfiedBranches(schema, emitted.get(schema))) {
                violations.add(schema + ".schema.json declares a mode no run produces: \"" + branch + "\"");
            }
        }

        assertTrue(violations.isEmpty(),
                () -> "served data-schemas that don't match real output:\n  " + String.join("\n  ", violations));
    }

    /** Does-fire: a schema declaring a mode the run can't produce must be reported. */
    @Test
    void theReverseCheckFiresOnADeadMode() {
        JsonNode oneRule = M.createObjectNode().put("rule", "/X/Y").put("converted", true);
        // read.schema's two branches: {rule,converted} and {rules,count}. A run of only the first leaves the
        // second unsatisfied — the check must report it.
        List<String> unsatisfied = unsatisfiedBranches("read", List.of(oneRule));
        assertFalse(unsatisfied.isEmpty(), "a never-produced branch must be flagged");
        assertTrue(unsatisfied.stream().anyMatch(b -> b.contains("name-index")),
                () -> "the unproduced name-index branch must be named, got: " + unsatisfied);
    }

    /** The schema's oneOf branch titles whose {@code required} keys no payload in {@code datas} provides
     *  (minus {@link #EXEMPT_BRANCHES}). A schema with no {@code oneOf} has one implicit branch (its top
     *  {@code required}). */
    private static List<String> unsatisfiedBranches(String schema, List<JsonNode> datas) {
        JsonNode root = schemaJson(schema);
        List<String> out = new ArrayList<>();
        JsonNode oneOf = root.path("oneOf");
        List<JsonNode> branches = new ArrayList<>();
        if (oneOf.isArray() && !oneOf.isEmpty()) {
            oneOf.forEach(branches::add);
        } else {
            branches.add(root);                                     // single-shape: the top required is the lone mode
        }
        for (JsonNode branch : branches) {
            String title = branch.path("title").asText("(mode)");
            if (EXEMPT_BRANCHES.contains(title)) {
                continue;
            }
            Set<String> required = new HashSet<>();
            branch.path("required").forEach(r -> required.add(r.asText()));
            if (required.isEmpty()) {
                continue;                                           // nothing to satisfy
            }
            boolean satisfied = datas.stream().anyMatch(d -> {
                Set<String> keys = new HashSet<>();
                d.fieldNames().forEachRemaining(keys::add);
                return keys.containsAll(required);
            });
            if (!satisfied) {
                out.add(title);
            }
        }
        return out;
    }

    private static JsonNode schemaJson(String name) {
        try (InputStream in = DataSchemaConformanceTest.class.getResourceAsStream("/schema/" + name + ".schema.json")) {
            if (in == null) {
                throw new IllegalStateException("schema resource missing: " + name);
            }
            return M.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Tripwire: if a new {@code DATA_SCHEMA} verb is added, it must be covered above or reasoned-exempt. */
    @Test
    void everyDataSchemaIsCoveredOrExempt() {
        Set<String> covered = Set.of("model-info", "describe", "model-usage", "read", "explain",
                "where-used", "field-read", "group-read", "typedef-read", "eval-doc", "compute-doc", "workspace-list");
        List<String> uncovered = new ArrayList<>();
        for (String schema : SchemaCommand.DATA_SCHEMA.values()) {
            if (!covered.contains(schema) && !EXEMPT_SCHEMAS.containsKey(schema)) {
                uncovered.add(schema);
            }
        }
        assertTrue(uncovered.isEmpty(),
                () -> "a served data-schema is neither conformance-checked nor reasoned-exempt: " + uncovered
                        + " — add a run to everyServedDataSchemaConformsToRealOutput or EXEMPT_SCHEMAS");
    }
}
