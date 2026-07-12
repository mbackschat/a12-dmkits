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

import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * A4 (decoupling Stage 2) — the silent-includes-drop defect is closed on the DEFAULT (kernel-free interpreter)
 * eval path. Before this, {@code model eval}/{@code rule eval}/{@code model compute} handed the RAW model to the
 * interpreter, which does not expand includes, so an includes-bearing model lost its mounted content and a rule
 * over a mounted field silently mis-reported. Now the CLI pre-expands kernel-free (via {@code ModelReader}), so
 * the default path agrees with the kernel. An INCLUDED model's OWN rules evaluate too — relative and
 * single-root-group-absolute conditions are relativized on mount (Set U); only a rare residual shape (IF122, the
 * G1′ caveat) makes the default path <b>refuse loudly</b> rather than mis-evaluate. The kernel leg here is the
 * {@code KernelEval} harness (adapter API). Own domain (an insurance policy including a benefits sub-model).
 */
class IncludeEvalDefaultPathTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** The included benefits sub-model: a Coverage amount + a Kind, no rules. */
    private static String benefits() {
        return DocumentModelDraft.named("benefits").locale("en_US")
                .rootGroup("Benefits", b -> b
                        .numberField("Coverage", n -> n.fractionDigits(2))
                        .field("Kind", FieldKind.STRING))
                .build().toJson();
    }

    /** benefits + a rule of its OWN (references the included root) — the relativization-refusal case. */
    private static String benefitsWithRule() {
        BoundModel m = BoundModel.ofJson(benefits());
        return m.add(m.rule(m.group("/Benefits")).name("CoveragePositive")
                .errorField(m.num("/Benefits/Coverage").field())
                .when(m.num("/Benefits/Coverage").filled().and(m.num("/Benefits/Coverage").lt(0)))
                .code("COV_NEG").severity(Severity.ERROR)
                .message(en("Coverage must not be negative.")).build()).toJson();
    }

    /** A policy mounting benefits at /Policy/Cover, with a BASE rule over the mounted Coverage field. */
    private static String policyWithBaseRule(ModelResolver resolver) {
        String policy = DocumentModelDraft.named("policy").locale("en_US")
                .include("benefits", "benefits")
                .rootGroup("Policy", p -> p
                        .field("PolicyNo", FieldKind.STRING)
                        .mount("Cover", "benefits"))
                .build().toJson();
        BoundModel m = BoundModel.ofJson(policy, resolver);
        return m.add(m.rule(m.group("/Policy")).name("CoverageCapped")
                .errorField(m.num("/Policy/Cover/Coverage").field())
                .when(m.num("/Policy/Cover/Coverage").filled().and(m.num("/Policy/Cover/Coverage").gt(100000)))
                .code("COV_CAP").severity(Severity.ERROR)
                .message(en("Coverage exceeds the cap.")).build()).toJson();
    }

    /** benefitsWithRule, but the rule names its field by an ABSOLUTE path ([/Benefits/Coverage]) — depth 0
     *  under the mount, so the relativizer must rewrite it to the relative [Coverage]. */
    private static String benefitsWithAbsoluteRule() {
        return benefitsWithRule()
                .replace("FieldFilled(Coverage)", "FieldFilled(/Benefits/Coverage)")
                .replace("[Coverage] < 0", "[/Benefits/Coverage] < 0");
    }

    /** A benefits sub-model with a Tier subgroup whose OWN rule references the root field by an ABSOLUTE path
     *  ([/Benefits/Amount]) — depth 1 below the mount, so the relativizer must produce ../Amount. */
    private static String nestedBenefitsWithAbsoluteRule() {
        String nested = DocumentModelDraft.named("benefits").locale("en_US")
                .rootGroup("Benefits", b -> b
                        .numberField("Amount", n -> n.fractionDigits(2))
                        .group("Tier", t -> t.numberField("Value", n -> n.fractionDigits(2))))
                .build().toJson();
        BoundModel m = BoundModel.ofJson(nested);
        // The rule lives in the Tier subgroup and reads its own Value — but by an ABSOLUTE path naming the root
        // (/Benefits/Tier/Value). At depth 1 the relativizer must produce ../Tier/Value for it to resolve post-mount.
        String withRule = m.add(m.rule(m.group("/Benefits/Tier")).name("ValuePositive")
                .errorField(m.num("/Benefits/Tier/Value").field())
                .when(m.num("/Benefits/Tier/Value").filled().and(m.num("/Benefits/Tier/Value").lt(0)))
                .code("VAL_NEG").severity(Severity.ERROR)
                .message(en("Value must not be negative.")).build()).toJson();
        // modelkit renders Value as a relative sibling ([Value]); make it ABSOLUTE so the depth-1 rewrite is exercised.
        return withRule
                .replace("FieldFilled(Value)", "FieldFilled(/Benefits/Tier/Value)")
                .replace("[Value] < 0", "[/Benefits/Tier/Value] < 0");
    }

    /** A policy mounting the benefits sub-model at /Policy/Cover (no base rule). */
    private static String policyMountingBenefitsAtCover() {
        return DocumentModelDraft.named("policy").locale("en_US")
                .include("benefits", "benefits")
                .rootGroup("Policy", p -> p.field("PolicyNo", FieldKind.STRING).mount("Cover", "benefits"))
                .build().toJson();
    }

    private static ModelResolver benefitsResolver(String benefitsJson) {
        return ref -> "benefits".equals(ref) ? benefitsJson : null;
    }

    /** Write the base model + its included model to {@code dir}; return the base model path. */
    private static Path workspace(Path dir, String baseJson, String includedJson) throws IOException {
        Files.writeString(dir.resolve("benefits.dm.json"), includedJson);
        Path base = dir.resolve("policy.dm.json");
        Files.writeString(base, baseJson);
        return base;
    }

    private static Path instance(Path dir, String coverage) throws IOException {
        return instanceField(dir, "Coverage", coverage);
    }

    private static Path instanceField(Path dir, String field, String value) throws IOException {
        Path f = dir.resolve("doc.json");
        Files.writeString(f, "{ \"fields\": { \"Policy\": { \"Cover\": { \"" + field + "\": \"" + value + "\" } } } }");
        return f;
    }

    private static boolean fired(String out, String code) throws Exception {
        JsonNode j = M.readTree(out);
        for (JsonNode c : j.path("data").path("fired")) {
            if (code.equals(c.asText())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aRuleOverAMountedFieldFiresOnTheDefaultPathJustLikeTheKernel(@TempDir Path dir) throws Exception {
        String benefits = benefits();
        Path model = workspace(dir, policyWithBaseRule(benefitsResolver(benefits)), benefits);
        Path doc = instance(dir, "250000.00");   // over the 100000 cap

        CliTestSupport.Result def = CliTestSupport.run(
                "-m", model.toString(), "model", "eval", "--instance", doc.toString());
        CliTestSupport.Result ker = CliTestSupport.runKernel(
                "-m", model.toString(), "model", "eval", "--instance", doc.toString());

        assertEquals(0, def.code(), () -> "default eval succeeds: " + def.out() + def.err());
        assertTrue(fired(def.out(), "COV_CAP"),
                () -> "the base rule sees the MOUNTED field on the default path (was silently dropped): " + def.out());
        assertEquals(fired(ker.out(), "COV_CAP"), fired(def.out(), "COV_CAP"),
                () -> "the default path agrees with --kernel");
    }

    @Test
    void aCompliantMountedValueFiresNothingOnTheDefaultPath(@TempDir Path dir) throws Exception {
        String benefits = benefits();
        Path model = workspace(dir, policyWithBaseRule(benefitsResolver(benefits)), benefits);
        Path doc = instance(dir, "50000.00");   // under the cap

        CliTestSupport.Result def = CliTestSupport.run(
                "-m", model.toString(), "model", "eval", "--instance", doc.toString());
        assertEquals(0, def.code(), () -> def.out() + def.err());
        assertTrue(!fired(def.out(), "COV_CAP"), () -> "a compliant mounted value fires nothing: " + def.out());
    }

    @Test
    void anIncludedModelsOwnRelativeRuleEvaluatesOnTheDefaultPathLikeTheKernel(@TempDir Path dir) throws Exception {
        String benefits = benefitsWithRule();
        String policy = DocumentModelDraft.named("policy").locale("en_US")
                .include("benefits", "benefits")
                .rootGroup("Policy", p -> p.field("PolicyNo", FieldKind.STRING).mount("Cover", "benefits"))
                .build().toJson();
        Path model = workspace(dir, policy, benefits);
        Path doc = instance(dir, "-5.00");   // negative → the included model's OWN rule (Coverage must not be negative) fires

        // The included rule references its field RELATIVELY ([Coverage]); the assembly layer carries it verbatim
        // (no relativization needed) and the interpreter resolves [Coverage] against the rule's POST-MOUNT group
        // /Policy/Cover — so the default path evaluates the mounted included rule, matching --kernel (was: refused).
        CliTestSupport.Result def = CliTestSupport.run(
                "-m", model.toString(), "model", "eval", "--instance", doc.toString());
        CliTestSupport.Result ker = CliTestSupport.runKernel(
                "-m", model.toString(), "model", "eval", "--instance", doc.toString());

        assertEquals(0, def.code(), () -> "the default path evaluates the included rule (no longer refuses): " + def.out() + def.err());
        assertTrue(fired(def.out(), "COV_NEG"),
                () -> "the mounted included model's OWN rule fires on the default path: " + def.out());
        assertEquals(fired(ker.out(), "COV_NEG"), fired(def.out(), "COV_NEG"),
                () -> "the default path agrees with --kernel on the included rule");
    }

    @Test
    void anIncludedRuleWithAnAbsolutePathIsRelativizedAndEvaluatesLikeTheKernel(@TempDir Path dir) throws Exception {
        String benefits = benefitsWithAbsoluteRule();
        assertTrue(benefits.contains("[/Benefits/Coverage]"), "the fixture rule uses an absolute path");
        Path model = workspace(dir, policyMountingBenefitsAtCover(), benefits);
        Path doc = instance(dir, "-5.00");   // negative → the included rule fires (once its absolute ref is relativized)

        // /Benefits/Coverage names the root group that vanishes on mount; the assembly layer rewrites it to the
        // relative [Coverage] (depth 0), so the default path resolves it against /Policy/Cover and matches --kernel.
        CliTestSupport.Result def = CliTestSupport.run(
                "-m", model.toString(), "model", "eval", "--instance", doc.toString());
        CliTestSupport.Result ker = CliTestSupport.runKernel(
                "-m", model.toString(), "model", "eval", "--instance", doc.toString());

        assertEquals(0, def.code(), () -> "the default path evaluates the relativized absolute rule: " + def.out() + def.err());
        assertTrue(fired(def.out(), "COV_NEG"), () -> "the relativized absolute reference fires: " + def.out());
        assertEquals(fired(ker.out(), "COV_NEG"), fired(def.out(), "COV_NEG"), () -> "matches --kernel: " + def.out());
    }

    @Test
    void aNestedIncludedRuleWithAnAbsolutePathIsRelativizedByDepthLikeTheKernel(@TempDir Path dir) throws Exception {
        String benefits = nestedBenefitsWithAbsoluteRule();
        assertTrue(benefits.contains("[/Benefits/Tier/Value]"), "the fixture rule uses an absolute path");
        Path model = workspace(dir, policyMountingBenefitsAtCover(), benefits);
        Path doc = tierValueInstance(dir, "-5.00");   // negative → the depth-1 nested rule fires

        // The rule lives one group deep (/Benefits/Tier), so /Benefits/Tier/Value must relativize to ../Tier/Value —
        // the depth-1 form — for the interpreter to resolve it against the rule's post-mount group /Policy/Cover/Tier.
        CliTestSupport.Result def = CliTestSupport.run(
                "-m", model.toString(), "model", "eval", "--instance", doc.toString());
        CliTestSupport.Result ker = CliTestSupport.runKernel(
                "-m", model.toString(), "model", "eval", "--instance", doc.toString());

        assertEquals(0, def.code(), () -> "the default path evaluates the depth-1 relativized rule: " + def.out() + def.err());
        assertTrue(fired(def.out(), "VAL_NEG"), () -> "the depth-1 relativized reference fires: " + def.out());
        assertEquals(fired(ker.out(), "VAL_NEG"), fired(def.out(), "VAL_NEG"), () -> "matches --kernel: " + def.out());
    }

    private static Path tierValueInstance(Path dir, String value) throws IOException {
        Path f = dir.resolve("doc.json");
        Files.writeString(f, "{ \"fields\": { \"Policy\": { \"Cover\": { \"Tier\": { \"Value\": \"" + value + "\" } } } } }");
        return f;
    }
}
