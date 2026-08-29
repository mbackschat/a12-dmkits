package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.rulekit.catalog.PatternCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.PatternEntry;

/**
 * {@code patterns} (FEATURE-GAPS G2/R1) — the idiom catalog. Lists idioms, details one, and scaffolds a
 * kernel-valid artifact: a <b>rule</b> (correct polarity + a referenced error field) or a <b>computation</b>
 * (a mutually-exclusive precondition table), auto-checking the result and, with {@code --apply}, persisting it.
 * Fixture: {@code order-ruled} (OrderDate/DeliveryDate/Quantity/BackorderQuantity/…).
 *
 * <p><b>Every assertion here moved to the result envelope (2026-07-31).</b> This verb used to print a raw Jackson
 * node with keys of its own invention — {@code valid}/{@code applied}/{@code previewed}/{@code reason} — even
 * though it runs the real kernel and, under {@code --apply}, writes the model. An agent had to learn a second
 * output contract for one verb. The payload keys are unchanged and now ride {@code data}; the outcome is the
 * standard {@code outcome}/{@code ok}/{@code valid}/{@code verification} vocabulary.
 */
class PatternsVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static Path model(Path dir) throws IOException {
        return CliTestSupport.orderRuled(dir);
    }

    @Test
    void listsTheIdioms() throws Exception {
        var res = CliTestSupport.run("patterns");
        assertEquals(0, res.code(), res::err);
        JsonNode j = M.readTree(res.out().isBlank() ? "{}" : res.out()).path("data");
        JsonNode ids = j.path("patterns");
        assertTrue(ids.toString().contains("date-order") && ids.toString().contains("mutually-exclusive"),
                () -> "lists the built-in idioms: " + res.out());
        assertTrue(j.path("count").asInt() >= 5, res::out);
        ids.forEach(pattern -> {
            assertTrue(pattern.path("paramNames").isArray(),
                    () -> "the catalogue names its lean string projection `paramNames`: " + pattern);
            assertFalse(pattern.has("params"),
                    () -> "`params` is reserved for the object-valued detail projection: " + pattern);
        });
    }

    @Test
    void detailsOneIdiom() throws Exception {
        var res = CliTestSupport.run("patterns", "date-order");
        assertEquals(0, res.code(), res::err);
        JsonNode j = M.readTree(res.out()).path("data");
        assertEquals("date-order", j.path("id").asText());
        assertFalse(j.path("skeleton").asText().isBlank(), "carries the skeleton");
        assertFalse(j.path("polarityNote").asText().isBlank(), "carries the polarity note");
        assertTrue(j.path("params").isArray() && j.path("params").size() == 2, res::out);
    }

    @Test
    void unknownIdIsACorrectiveError() throws Exception {
        var res = CliTestSupport.run("patterns", "no-such-idiom");
        assertNotEquals(0, res.code(), "an unknown id must fail");
        // a coded, structured refusal like the rest of the read family — never a bare stderr line
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.path("outcome").asText(), res::out);
        assertEquals("RK_NO_SUCH_ELEMENT", j.path("diagnostics").get(0).path("code").asText(), res::out);
        assertTrue(j.path("diagnostics").get(0).path("fix").asText().contains("date-order"),
                () -> "names the known patterns: " + res.out());
    }

    @Test
    void scaffoldsAKernelValidRuleSpec(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", model(dir).toString(), "patterns", "date-order",
                "--arg", "earlier=/Order/OrderDate", "--arg", "later=/Order/DeliveryDate");
        assertEquals(0, res.code(), res::err);
        JsonNode j = M.readTree(res.out());
        assertEquals("/Order/DeliveryDate", j.path("data").path("spec").path("field").asText(), res::out);
        assertTrue(j.path("data").path("spec").path("condition").asText().contains("DifferenceInDays"), res::out);
        // a review is a kernel-backed VERDICT about the candidate, so it carries valid AND its arbitrator
        assertTrue(j.path("valid").asBoolean(), () -> "the scaffolded rule is kernel-valid: " + res.out());
        assertEquals("KERNEL_CONFIRMED", j.path("verification").asText(), res::out);
    }

    @Test
    void missingArgIsACorrectiveError(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", model(dir).toString(), "patterns", "date-order",
                "--arg", "earlier=/Order/OrderDate");   // no `later`
        assertNotEquals(0, res.code(), "a missing parameter must fail");
        // The corrective moved into the result envelope on 2026-08-30: scaffolding refusals share the input
        // gate's contract with the spec verbs, because several of them carry MVK_* codes and prose on stderr
        // cannot say who decided (SpecValueRefusalEnvelopeTest).
        assertTrue(res.out().contains("later"), () -> "names the missing parameter: " + res.out() + res.err());
    }

    @Test
    void generationOptionsWithoutAPatternIdAreRejectedInsteadOfIgnored() {
        for (List<String> args : List.of(
                List.of("patterns", "--arg", "earlier=/Order/OrderDate"),
                List.of("patterns", "--apply"),
                List.of("patterns", "--dry-run"),
                List.of("patterns", "-o", "ignored.json"))) {
            var res = CliTestSupport.run(args.toArray(String[]::new));
            assertEquals(2, res.code(), () -> "unsupported mode must not fall through to the catalog: " + res.out());
            assertTrue(res.err().contains("patternId"), res::err);
        }
    }

    @Test
    void applyOnlyOptionsWithoutArgumentsAreRejectedInsteadOfIgnored() {
        for (List<String> args : List.of(
                List.of("patterns", "date-order", "--apply"),
                List.of("patterns", "date-order", "--dry-run"),
                List.of("patterns", "date-order", "-o", "ignored.json"))) {
            var res = CliTestSupport.run(args.toArray(String[]::new));
            assertEquals(2, res.code(), () -> "unsupported mode must not fall through to detail: " + res.out());
            assertTrue(res.err().contains("--arg"), res::err);
        }
    }

    @Test
    void dryRunAndOutputRequireApply(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        for (List<String> tail : List.of(
                List.of("--dry-run"),
                List.of("-o", dir.resolve("ignored.json").toString()))) {
            List<String> args = new ArrayList<>(List.of("-m", model.toString(), "patterns", "date-order",
                    "--arg", "earlier=/Order/OrderDate", "--arg", "later=/Order/DeliveryDate"));
            args.addAll(tail);
            var res = CliTestSupport.run(args.toArray(String[]::new));
            assertEquals(2, res.code(), res::out);
            assertTrue(res.err().contains("--apply"), res::err);
        }
    }

    @Test
    void applyPersistsTheScaffoldedRule(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        var res = CliTestSupport.run("-m", model.toString(), "patterns", "mutually-exclusive",
                "--arg", "a=/Order/CustomerName", "--arg", "b=/Order/ProductCode",
                "--code", "BOTH_SET", "--name", "NameXorCode", "--apply");
        assertEquals(0, res.code(), res::err);
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.path("outcome").asText(), res::out);
        assertTrue(j.path("written").asBoolean(), res::out);
        assertEquals("/Order/NameXorCode", j.path("changed").path("rule").asText(),
                () -> "on --apply the scaffold payload is the mutation DELTA, so it rides `changed`: " + res.out());
        assertTrue(Files.readString(model).contains("NameXorCode"), "the rule is persisted to the model");
    }

    @Test
    void scaffoldsAKernelValidComputationSpec(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", model(dir).toString(), "patterns", "tiered-amount",
                "--arg", "computed=/Order/BackorderQuantity", "--arg", "driver=/Order/Quantity",
                "--arg", "threshold=10", "--arg", "below=0", "--arg", "atOrAbove=5");
        assertEquals(0, res.code(), res::err);
        JsonNode j = M.readTree(res.out());
        JsonNode spec = j.path("data").path("spec");
        assertEquals("/Order/BackorderQuantity", spec.path("computedField").asText(), res::out);
        // the two preconditions partition the driver — mutually exclusive + exhaustive by construction
        assertEquals(2, spec.path("alternatives").size(), res::out);
        assertEquals("FieldFilled(Quantity)", spec.path("commonPrecondition").asText(), res::out);
        assertTrue(j.path("valid").asBoolean(), () -> "the scaffolded computation is kernel-valid: " + res.out());
    }

    @Test
    void applyPersistsTheScaffoldedComputation(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        var res = CliTestSupport.run("-m", model.toString(), "patterns", "tiered-amount",
                "--arg", "computed=/Order/BackorderQuantity", "--arg", "driver=/Order/Quantity",
                "--arg", "threshold=10", "--arg", "below=0", "--arg", "atOrAbove=5",
                "--name", "BackorderTier", "--message", "en_US=backorder tier mismatch", "--apply");
        assertEquals(0, res.code(), res::err);
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.path("outcome").asText(), res::out);
        assertEquals("/Order/BackorderTier", j.path("changed").path("computation").asText(), res::out);
        assertTrue(Files.readString(model).contains("BackorderTier"), "the computation is persisted to the model");
    }

    @Test
    void scaffoldsAKernelValidFieldSpec(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", model(dir).toString(), "patterns", "bounded-number",
                "--arg", "group=/Order", "--arg", "name=RiskScore", "--arg", "min=0", "--arg", "max=100");
        assertEquals(0, res.code(), res::err);
        JsonNode j = M.readTree(res.out());
        JsonNode spec = j.path("data").path("spec");
        assertEquals("/Order", spec.path("group").asText(), res::out);
        assertEquals("RiskScore", spec.path("name").asText(), res::out);
        assertEquals("NUMBER", spec.path("kind").asText(), res::out);
        // the spec is the field add shape — the bound (number.max) R2's seeAlso bridge names lives in the config
        assertEquals(100, spec.path("number").path("max").asInt(), res::out);
        assertTrue(j.path("valid").asBoolean(), () -> "the scaffolded field is kernel-valid: " + res.out());
    }

    /**
     * The CLI-layer <b>no-rot</b> guard for FIELD idioms — the peer of {@code PatternCatalogKernelTest} (which
     * covers rule/computation kernel-validity in rulekit). FIELD idioms produce a modelkit-free spec the rulekit
     * guard can't kernel-check, so every field idiom's example is run through the real CLI here and must be
     * kernel-valid. A field idiom that no longer scaffolds a valid field fails the build.
     */
    @Test
    void everyFieldIdiomExampleScaffoldsAKernelValidField(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        for (PatternEntry p : PatternCatalog.load().entries()) {
            if (p.kind() != PatternEntry.PatternKind.FIELD) {
                continue;
            }
            List<String> args = new ArrayList<>(List.of("-m", model.toString(), "patterns", p.id()));
            p.example().forEach((k, v) -> {
                args.add("--arg");
                args.add(k + "=" + v);
            });
            var res = CliTestSupport.run(args.toArray(String[]::new));
            assertEquals(0, res.code(), () -> p.id() + ": " + res.err());
            assertTrue(M.readTree(res.out()).path("valid").asBoolean(),
                    () -> "field idiom '" + p.id() + "' scaffolds a kernel-INVALID field: " + res.out());
            assertEquals("KERNEL_CONFIRMED", M.readTree(res.out()).path("verification").asText(),
                    () -> "the scaffold verdict states which gate reached it: " + res.out());
        }
    }

    @Test
    void applyPersistsTheScaffoldedField(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        var res = CliTestSupport.run("-m", model.toString(), "patterns", "value-set-enum",
                "--arg", "group=/Order", "--arg", "name=Channel", "--arg", "values=WEB,PHONE,STORE", "--apply");
        assertEquals(0, res.code(), res::err);
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.path("outcome").asText(), res::out);
        assertEquals("/Order/Channel", j.path("changed").path("field").asText(), res::out);
        assertTrue(Files.readString(model).contains("Channel"), "the field is persisted to the model");
    }

    @Test
    void applyDryRunWritesNothing(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        String before = Files.readString(model);
        var res = CliTestSupport.run("-m", model.toString(), "patterns", "mutually-exclusive",
                "--arg", "a=/Order/CustomerName", "--arg", "b=/Order/ProductCode", "--apply", "--dry-run");
        assertEquals(0, res.code(), res::err);
        JsonNode j = M.readTree(res.out());
        assertEquals("preview", j.path("outcome").asText(), res::out);
        assertFalse(j.path("written").asBoolean(), res::out);
        assertEquals(before, Files.readString(model), "--dry-run writes nothing");
    }
}
