package io.github.mbackschat.a12.dm.cli;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.MountOptions;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * IF146 — include preparation identifies genuine reference spans, binds them in the included model, and remaps
 * moved targets without touching literal text. The behavioral oracle is kernel include expansion
 * ({@code DocumentModelExpandService.RuleAndComputationConditionUpdaterVisitor} with
 * {@code EntityPathsRelativizer}); this CLI differential covers rule/computation evaluation plus the structured
 * unknown-reference refusal. The dynamic/static strategy lock is {@code IncludeAssemblyStrategyDiffTest}.
 */
class IncludeResidualRefDiffTest {

    private static final ObjectMapper M = new ObjectMapper();

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────────────

    private static String benefits() {
        return DocumentModelDraft.named("benefits").locale("en_US")
                .rootGroup("Benefits", b -> b
                        .numberField("Coverage", n -> n.fractionDigits(2))
                        .field("Kind", FieldKind.STRING))
                .build().toJson();
    }

    /** benefits + its OWN rule (Coverage must not be negative); the condition text is patched per shape. */
    private static String benefitsWithRule() {
        BoundModel m = BoundModel.ofJson(benefits());
        return m.add(m.rule(m.group("/Benefits")).name("CoveragePositive")
                .errorField(m.num("/Benefits/Coverage").field())
                .when(m.num("/Benefits/Coverage").filled().and(m.num("/Benefits/Coverage").lt(0)))
                .code("COV_NEG").severity(Severity.ERROR)
                .message(en("Coverage must not be negative.")).build()).toJson();
    }

    /** benefits(Base, Total) + a computation Total := Base, referencing Base by an ABSOLUTE path. */
    private static String benefitsWithComputation() {
        String base = DocumentModelDraft.named("benefits").locale("en_US")
                .rootGroup("Benefits", b -> b
                        .numberField("Base", n -> n.fractionDigits(2))
                        .numberField("Total", n -> n.fractionDigits(2)))
                .build().toJson();
        BoundModel m = BoundModel.ofJson(base);
        String withComp = m.add(m.computation(m.num("/Benefits/Total")).name("TotalComp")
                .calc(m.num("/Benefits/Base").value())
                .message(en("Total must equal Base.")).build()).toJson();
        // Force the operand to an absolute path naming the pre-mount root ([Base] -> [/Benefits/Base]).
        return withComp.replace("[Base]", "[/Benefits/Base]");
    }

    private static String benefitsWithPathLikeLiteralRule() {
        BoundModel model = BoundModel.ofJson(benefits());
        return model.add(model.rule(model.group("/Benefits")).name("LiteralCheck")
                .errorField(model.str("/Benefits/Kind").field())
                .when(model.str("/Benefits/Kind").eq("(/Unknown/Value"))
                .code("LITERAL").severity(Severity.ERROR)
                .message(en("The literal matches.")).build()).toJson();
    }

    private static String policy(boolean modelRoot) {
        return DocumentModelDraft.named("policy").locale("en_US")
                .include("benefits", "benefits")
                .rootGroup("Policy", p -> {
                    p.field("PolicyNo", FieldKind.STRING);
                    if (modelRoot) {
                        p.mount("Cover", "benefits", MountOptions.single().asModelRoot());
                    } else {
                        p.mount("Cover", "benefits");
                    }
                })
                .build().toJson();
    }

    private static Path ws(Path dir, String policy, String benefits) throws IOException {
        Files.writeString(dir.resolve("benefits.dm.json"), benefits);
        Path base = dir.resolve("policy.dm.json");
        Files.writeString(base, policy);
        return base;
    }

    private static Path doc(Path dir, String json) throws IOException {
        Path f = dir.resolve("doc.json");
        Files.writeString(f, json);
        return f;
    }

    private static boolean fired(String out, String code) throws Exception {
        for (JsonNode c : M.readTree(out).path("data").path("fired")) {
            if (code.equals(c.asText())) {
                return true;
            }
        }
        return false;
    }

    private static String computedValue(String out, String fieldPath) throws Exception {
        for (JsonNode c : M.readTree(out).path("data").path("computed")) {
            if (fieldPath.equals(c.path("field").asText())) {
                return c.path("value").asText(null);
            }
        }
        return null;
    }

    // ── the differential locks ──────────────────────────────────────────────────────────────────────────

    @Test
    void bareRootGroupReferenceEvaluatesLikeTheKernel(@TempDir Path dir) throws Exception {
        // GroupFilled(/Benefits) — a bare root-group ref (no descent) → remapped to the mount group /Policy/Cover.
        String benefits = benefitsWithRule().replace("FieldFilled(Coverage)", "GroupFilled(/Benefits)");
        assertTrue(benefits.contains("GroupFilled(/Benefits)"), "the fixture uses a bare root-group ref");
        Path model = ws(dir, policy(false), benefits);
        Path instance = doc(dir, "{ \"fields\": { \"Policy\": { \"Cover\": { \"Coverage\": \"-5.00\" } } } }");

        CliTestSupport.Result def = CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", instance.toString());
        CliTestSupport.Result ker = CliTestSupport.runKernel("-m", model.toString(), "model", "eval", "--instance", instance.toString());

        assertEquals(0, def.code(), () -> "the default path evaluates the bare-group-ref rule: " + def.out() + def.err());
        assertTrue(fired(def.out(), "COV_NEG"), () -> "the remapped bare group ref fires: " + def.out());
        assertEquals(fired(ker.out(), "COV_NEG"), fired(def.out(), "COV_NEG"), () -> "matches the kernel: " + def.out());
    }

    @Test
    void modelRootAbsoluteReferenceEvaluatesLikeTheKernel(@TempDir Path dir) throws Exception {
        // MODEL_ROOT keeps /Benefits as a named subgroup under the mount → /Policy/Cover/Benefits; the absolute
        // [/Benefits/Coverage] is remapped onto it.
        String benefits = benefitsWithRule()
                .replace("FieldFilled(Coverage)", "FieldFilled(/Benefits/Coverage)")
                .replace("[Coverage] < 0", "[/Benefits/Coverage] < 0");
        Path model = ws(dir, policy(true), benefits);
        Path instance = doc(dir, "{ \"fields\": { \"Policy\": { \"Cover\": { \"Benefits\": { \"Coverage\": \"-5.00\" } } } } }");

        CliTestSupport.Result def = CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", instance.toString());
        CliTestSupport.Result ker = CliTestSupport.runKernel("-m", model.toString(), "model", "eval", "--instance", instance.toString());

        assertEquals(0, def.code(), () -> "the default path evaluates the MODEL_ROOT absolute rule: " + def.out() + def.err());
        assertTrue(fired(def.out(), "COV_NEG"), () -> "the remapped MODEL_ROOT absolute ref fires: " + def.out());
        assertEquals(fired(ker.out(), "COV_NEG"), fired(def.out(), "COV_NEG"), () -> "matches the kernel: " + def.out());
    }

    @Test
    void includedComputationWithAnAbsoluteOperandComputesLikeTheKernel(@TempDir Path dir) throws Exception {
        // An included computation Total := [/Benefits/Base] — the absolute operand is remapped to /Policy/Cover/Base.
        String benefits = benefitsWithComputation();
        assertTrue(benefits.contains("[/Benefits/Base]"), "the fixture computation uses an absolute operand");
        Path model = ws(dir, policy(false), benefits);
        Path instance = doc(dir, "{ \"fields\": { \"Policy\": { \"Cover\": { \"Base\": \"42.00\" } } } }");

        CliTestSupport.Result def = CliTestSupport.run("-m", model.toString(), "model", "compute", "--instance", instance.toString());
        CliTestSupport.Result ker = CliTestSupport.runKernel("-m", model.toString(), "model", "compute", "--instance", instance.toString());

        assertEquals(0, def.code(), () -> "the default path computes the included computation: " + def.out() + def.err());
        String defTotal = computedValue(def.out(), "/Policy/Cover/Total");
        assertEquals("42", defTotal, () -> "the remapped computation computes Total from Base: " + def.out());
        assertEquals(computedValue(ker.out(), "/Policy/Cover/Total"), defTotal, () -> "matches the kernel: " + def.out());
    }

    @Test
    void aGenuineUnknownAbsoluteReferenceProducesAStructuredRefusal(@TempDir Path dir) throws Exception {
        String benefits = benefitsWithRule().replace("[Coverage] < 0", "[/Unknown/Coverage] < 0");
        Path model = ws(dir, policy(false), benefits);
        Path instance = doc(dir, "{ \"fields\": { \"Policy\": { \"Cover\": { \"Coverage\": \"-5.00\" } } } }");

        CliTestSupport.Result result = CliTestSupport.run(
                "-m", model.toString(), "model", "eval", "--instance", instance.toString());
        JsonNode envelope = M.readTree(result.out());

        assertEquals("refused", envelope.path("outcome").asText(), () -> result.out() + result.err());
        assertEquals("RK_INTERPRETER_INCLUDE_RULES_UNSUPPORTED",
                envelope.path("diagnostics").path(0).path("code").asText());
        assertTrue(envelope.path("diagnostics").path(0).path("summary").asText()
                .contains("/Policy/Cover/CoveragePositive"), "the refusal identifies the surviving element");
    }

    @Test
    void aPathLikeStringLiteralNeverProducesAFalseRefusal(@TempDir Path dir) throws Exception {
        Path model = ws(dir, policy(false), benefitsWithPathLikeLiteralRule());
        Path instance = doc(dir,
                "{ \"fields\": { \"Policy\": { \"Cover\": { \"Kind\": \"(/Unknown/Value\" } } } }");

        CliTestSupport.Result result = CliTestSupport.run(
                "-m", model.toString(), "model", "eval", "--instance", instance.toString());

        assertEquals(0, result.code(), () -> result.out() + result.err());
        assertTrue(fired(result.out(), "LITERAL"), () -> "the literal comparison evaluates: " + result.out());
    }
}
