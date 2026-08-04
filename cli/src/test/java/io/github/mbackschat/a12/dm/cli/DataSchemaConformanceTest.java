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
        Path reportModel = CliTestSupport.modelWithRuleAndComputation(dir);   // rule + computation → both report catalogs
        Path docDir = Files.createDirectories(dir.resolve("docs"));
        Path doc = docDir.resolve("doc.json");
        Files.writeString(doc, """
                { "Order": { "CustomerName": "Acme", "Quantity": "5",
                                          "OrderDate": "2026-01-01", "DeliveryDate": "2026-01-10" } }""");
        // `rule eval` selects its payload keys by VERDICT, so one document cannot reach them: a firing carries the
        // message channels, a formally-invalid operand carries `suppressedBy`, and an unmodeled construct carries
        // `unsupported`. Three documents, one per branch.
        Path violation = docDir.resolve("violation.json");
        Files.writeString(violation, """
                { "Order": { "OrderDate": "2026-01-10", "DeliveryDate": "2026-01-01" } }""");
        Path malformedDate = docDir.resolve("malformed-date.json");
        Files.writeString(malformedDate, """
                { "Order": { "OrderDate": "2026-01-10", "DeliveryDate": "not-a-date" } }""");

        // Populate OPTIONAL read properties so the forward drift check covers them, not just the lean branch.
        Path fieldProps = dir.resolve("field-props.json");
        Files.writeString(fieldProps, """
                {"group":"/Order","name":"Quantity","helperText":{"en_US":"whole units"}}""");
        assertTrue(CliTestSupport.run("-m", order.toString(), "field", "modify", fieldProps.toString()).code() == 0);
        assertTrue(CliTestSupport.run("-m", order.toString(), "meta", "/Order/Quantity",
                "--label", "en_US=Quantity", "--annotation", "owner=sales",
                "--internal-description", "en_US=field note",
                "--external-description", "en_US=field hint").code() == 0);
        assertTrue(CliTestSupport.run("-m", order.toString(), "meta", "/Order/Items",
                "--label", "en_US=Items", "--annotation", "owner=sales",
                "--internal-description", "en_US=group note",
                "--external-description", "en_US=group hint").code() == 0);

        // a model carrying a type definition, so `typedef read <id>` / `typedef read` produce both branches
        Path td = dir.resolve("td.dm.json");
        CliTestSupport.run("model", "new", "--id", "td", "--locale", "en_US", "--root", "R", "-o", td.toString());
        CliTestSupport.run("-m", td.toString(), "typedef", "add", "--id", "Money", "--kind", "NUMBER");

        // a renamed copy of order-ruled, so `model diff` produces the refactor overlay (RENAMED + a rewritten
        // referrer folded into `data.refactors[]`, and the referrer carries `partOfRefactor`) — MR4's shape
        Path renamed = dir.resolve("order-renamed.dm.json");
        Files.copy(order, renamed);
        CliTestSupport.run("-m", renamed.toString(), "field", "rename", "/Order/OrderDate", "--to", "PlacedDate");

        Path targetProfile = dir.resolve("target.profile.json");
        Path actualProfile = dir.resolve("actual.profile.json");
        CliTestSupport.Result extracted = CliTestSupport.run(
                "-m", order.toString(), "profile", "extract", "-o", targetProfile.toString());
        assertTrue(extracted.code() == 0, () -> "profile fixture extraction failed: " + extracted.err());
        Files.copy(targetProfile, actualProfile);

        // schemaName -> the data payloads real runs produced (one per documented mode)
        Map<String, List<JsonNode>> emitted = new LinkedHashMap<>();
        List<String> violations = new ArrayList<>();

        record Run(String schema, String[] args) {}
        List<Run> runs = List.of(
                new Run("model-info", new String[]{"-m", order.toString(), "model", "info"}),
                new Run("describe", new String[]{"-m", order.toString(), "model", "describe"}),
                new Run("model-usage", new String[]{"-m", order.toString(), "model", "usage"}),
                new Run("model-diff", new String[]{"model", "diff", order.toString(), sub.toString()}),
                new Run("model-diff", new String[]{"model", "diff", order.toString(), renamed.toString()}),
                new Run("model-report", new String[]{"-m", reportModel.toString(), "model", "report"}),
                new Run("model-report", new String[]{"-m", reportModel.toString(), "model", "report",
                        "--tree", "--root", "/Subscription"}),
                new Run("model-report", new String[]{"-m", reportModel.toString(), "model", "report",
                        "--tree", "--root", "/Subscription",
                        "--include", "rules,computations,descriptions,annotations,enum"}),
                new Run("profile", new String[]{"-m", order.toString(), "profile", "extract"}),
                new Run("profile-comparison", new String[]{"profile", "compare",
                        "--target", targetProfile.toString(), "--actual", actualProfile.toString()}),
                new Run("read", new String[]{"-m", sub.toString(), "rule", "read", CliTestSupport.RULE_FULL_NAME}),
                new Run("read", new String[]{"-m", sub.toString(), "rule", "read"}),
                new Run("explain", new String[]{"-m", sub.toString(), "rule", "explain", CliTestSupport.RULE_FULL_NAME}),
                new Run("where-used", new String[]{"-m", order.toString(), "where-used", "/Order/Quantity"}),
                new Run("meta-read", new String[]{"-m", order.toString(), "meta", "/Order/Quantity"}),
                new Run("meta-read", new String[]{"-m", reportModel.toString(), "meta",
                        "/Subscription/Billing/EffectiveFeeComp"}),
                new Run("field-read", new String[]{"-m", order.toString(), "field", "read", "/Order/Quantity"}),
                new Run("field-read", new String[]{"-m", order.toString(), "field", "read"}),
                new Run("group-read", new String[]{"-m", order.toString(), "group", "read", "/Order/Items"}),
                new Run("group-read", new String[]{"-m", order.toString(), "group", "read"}),
                new Run("typedef-read", new String[]{"-m", td.toString(), "typedef", "read", "Money"}),
                new Run("typedef-read", new String[]{"-m", td.toString(), "typedef", "read"}),
                new Run("eval-doc", new String[]{"-m", order.toString(), "model", "eval", "--instance", doc.toString()}),
                new Run("compute-doc", new String[]{"-m", order.toString(), "model", "compute", "--instance", doc.toString()}),
                new Run("rule-eval", new String[]{"-m", order.toString(), "rule", "eval",
                        "/Order/DeliveryNotBeforeOrder", "--instance", doc.toString()}),
                new Run("rule-eval", new String[]{"-m", order.toString(), "rule", "eval",
                        "/Order/DeliveryNotBeforeOrder", "--instance", violation.toString()}),
                new Run("rule-eval", new String[]{"-m", order.toString(), "rule", "eval",
                        "/Order/DeliveryNotBeforeOrder", "--instance", malformedDate.toString()}),
                new Run("rule-eval", new String[]{"-m", order.toString(), "rule", "eval",
                        "/Order/EligibilityCheck", "--instance", doc.toString()}),
                new Run("workspace-list", new String[]{"workspace", "list", dir.toString()}),
                // --validate is a SEPARATE shape (per-entry checked/valid/verification or checked/problem), and
                // running only the bare form is why three undeclared keys shipped against an
                // additionalProperties:false schema — the closed-keys walk had nothing to walk.
                new Run("workspace-list", new String[]{"workspace", "list", dir.toString(), "--validate"}),
                // `patterns` selects its payload by ARGUMENTS, not a flag, so all three shapes need a run or the
                // `oneOf` has unreachable branches — the trap the CG6 row named in advance (CATALOG-SPEC §6's
                // sibling concern). The two scaffold runs additionally reach the two `$ref`s the scaffold's `spec`
                // delegates to: a rule idiom lands on `rule-add.schema.json`, a field idiom on `field-add.schema.json`.
                // A kernel-REJECTED scaffold is deliberately NOT a run here: it exits 1 (this harness requires 0)
                // and returns the SAME shape, so it would add no branch — `PatternsScaffoldVerdictTest` owns that
                // verdict.
                new Run("patterns", new String[]{"patterns"}),
                new Run("patterns", new String[]{"patterns", "date-order"}),
                new Run("patterns", new String[]{"-m", order.toString(), "patterns", "date-order",
                        "--arg", "earlier=/Order/OrderDate", "--arg", "later=/Order/DeliveryDate"}),
                new Run("patterns", new String[]{"-m", order.toString(), "patterns", "bounded-number",
                        "--arg", "group=/Order", "--arg", "name=Weight", "--arg", "min=1", "--arg", "max=99"}));

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
                // The locus rides `where.arg` (SpecKeys builds `Where.arg(null, path)`); reading `where.path`
                // rendered every violation as "?", so the guard could name that it failed but not where.
                violations.add(run.schema + ".schema.json disagrees with real output — " + d.summary()
                        + " at " + d.where().arg().orElse("?") + "  (run: " + String.join(" ", run.args()) + ")");
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
        // Restates the schema names exercised above. The duplication is deliberate and tolerable ONLY because
        // this test fires loudly the moment the two disagree — which is how `patterns` was caught on the way in.
        // If it ever needs to be silent-safe, derive it from the run list instead of widening this set.
        Set<String> covered = Set.of("model-info", "describe", "model-diff", "model-report", "model-usage", "profile",
                "profile-comparison", "read", "explain", "where-used", "meta-read", "field-read", "group-read", "typedef-read",
                "eval-doc", "compute-doc", "rule-eval", "workspace-list", "patterns");
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
