package io.github.mbackschat.a12.dm.core;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.MountOptions;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * RV03 — include reference binding happens in the included model's pre-mount context, and residual locations
 * belong to surviving rule/computation elements.
 */
class IncludeReferenceBindingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aCrossGroupShortNameIsBoundBeforeAnIncludingModelCollisionCanRetargetIt() {
        String included = checksWithCrossGroupShortName();
        String policy = policyMounting("checks", "checks", false, false, true);

        ModelReader reader = ModelReader.expand(policy, resolver(Map.of("checks", included)));

        assertTrue(reader.toJson().contains("[/Policy/Cover/Rates/Cap] > 0"),
                () -> "the rule short name is bound to the included target: " + reader.toJson());
        assertTrue(reader.toJson().contains("[/Policy/Cover/Rates/Cap]"),
                () -> "the computation short name is bound to the included target: " + reader.toJson());
        assertFalse(reader.toJson().contains("[Cap]"),
                "the including model's colliding /Policy/Cap cannot retarget the mounted fragments");
    }

    @Test
    void remapsEveryComputationFragmentAgainstThePreMountModel() throws Exception {
        String included = checksWithCrossGroupShortName()
                .replace("[Cap]", "[/Checks/Rates/Cap]");
        ModelReader reader = ModelReader.expand(
                policyMounting("checks", "checks", false, false, false),
                resolver(Map.of("checks", included)));

        JsonNode computation = JSON.readTree(reader.toJson()).findParents("name").stream()
                .filter(element -> "CopyCap".equals(element.path("name").asText()))
                .findFirst().orElseThrow().path("Computation");
        assertTrue(computation.path("commonPrecondition").asText()
                .contains("[/Policy/Cover/Rates/Cap]"));
        assertTrue(computation.path("computationAlternatives").path(0).path("precondition").asText()
                .contains("[/Policy/Cover/Rates/Cap]"));
        assertTrue(computation.path("computationAlternatives").path(0).path("operation").asText()
                .contains("[/Policy/Cover/Rates/Cap]"));
        assertFalse(computation.toString().contains("/Checks/Rates/Cap"),
                "no computation fragment retains its pre-mount absolute target");
    }

    @Test
    void shortNameBindingRemainsFlagGated() {
        EditSession disabled = EditSession.read(checksWithCrossGroupShortName());
        disabled.config().fieldRefByShortNameAllowed(false);

        ModelReader reader = ModelReader.expand(
                policyMounting("checks", "checks", false, false, true),
                resolver(Map.of("checks", disabled.toJson())));

        assertTrue(reader.toJson().contains("[Cap] > 0"),
                "a flag-off cross-group short name remains unresolved for the model-consistency gate to reject");
    }

    /**
     * …and the gate reads every spelling A12 reads. A capitalized {@code "True"} is on to A12 and off to
     * {@code JsonNode.asBoolean}, which is how this site read it until 2026-09-12 — so a provider whose flag
     * was spelled that way had its cross-group short names left unbound, and the mounted rule then failed the
     * model-consistency gate on a model A12 accepts.
     *
     * <p>The control above is the same fixture with the flag genuinely off, so the two rows differ in the
     * SPELLING alone rather than in whether a flag is set. Kernel oracle:
     * {@code rulekit.validate.laws.ShortNameFlagSpellingLawsTest}.
     */
    @Test
    void theShortNameGateTakesEverySpellingA12Takes() {
        for (String spelling : List.of("true", "\"true\"", "\"True\"", "\"TRUE\"", "1")) {
            String member = "\"fieldRefByShortNameAllowed\"\\s*:\\s*true";
            String draft = checksWithCrossGroupShortName();
            assertTrue(draft.matches("(?s).*" + member + ".*"),
                    "the fixture no longer writes the member this varies");
            String included = draft.replaceAll(member,
                    java.util.regex.Matcher.quoteReplacement("\"fieldRefByShortNameAllowed\":" + spelling));

            ModelReader reader = ModelReader.expand(
                    policyMounting("checks", "checks", false, false, true),
                    resolver(Map.of("checks", included)));

            assertTrue(reader.toJson().contains("[/Policy/Cover/Rates/Cap] > 0"),
                    () -> "A12 reads " + spelling + " as on, so the short name must bind: " + reader.toJson());
        }
    }

    @Test
    void outerExclusionDropsNestedResidualsWithTheirElements() {
        String leaf = leafWithResidualRuleAndComputation();
        String middle = middleMountingLeaf();
        String policy = policyMounting("middle", "middle", true, true, false);

        ModelReader reader = ModelReader.expand(policy, resolver(Map.of("middle", middle, "leaf", leaf)));

        assertEquals(List.of(), reader.includedElementsWithUnmappedReferences(),
                "excluded nested rules/computations cannot leave a stale refusal");
    }

    @Test
    void retainedNestedResidualsRefuseAtUnambiguousNamePaths() {
        String leaf = leafWithResidualRuleAndComputation();
        String middle = middleMountingLeaf();
        String policy = policyMounting("middle", "middle", false, false, false);

        ModelReader reader = ModelReader.expand(policy, resolver(Map.of("middle", middle, "leaf", leaf)));

        assertEquals(
                List.of("/Policy/Cover/Nested/ResidualRule", "/Policy/Cover/Nested/ResidualComputation"),
                reader.includedElementsWithUnmappedReferences());
    }

    @Test
    void duplicateElementNamesInDifferentGroupsHaveDistinctResidualLocations() {
        String included = duplicateNamedResidualRules();
        String policy = policyMounting("checks", "checks", false, false, false);

        ModelReader reader = ModelReader.expand(policy, resolver(Map.of("checks", included)));

        assertEquals(
                List.of("/Policy/Cover/A/Check", "/Policy/Cover/B/Check"),
                reader.includedElementsWithUnmappedReferences());
    }

    private static String checksWithCrossGroupShortName() {
        String base = DocumentModelDraft.named("checks").locale("en_US")
                .rootGroup("Checks", checks -> checks
                        .group("Rates", rates -> rates.numberField("Cap", field -> field.fractionDigits(0)))
                        .group("Validation", validation -> validation
                                .numberField("Observed", field -> field.fractionDigits(0))
                                .numberField("Total", field -> field.fractionDigits(0))))
                .build().toJson();
        BoundModel model = BoundModel.ofJson(base);
        String withRule = model.add(model.rule(model.group("/Checks/Validation")).name("CapPositive")
                .errorField(model.num("/Checks/Validation/Observed").field())
                .when(model.num("/Checks/Validation/Observed").filled()
                        .and(model.num("/Checks/Rates/Cap").gt(0)))
                .code("CAP_POS").severity(Severity.ERROR)
                .message(en("Cap must be positive.")).build()).toJson();
        BoundModel ruled = BoundModel.ofJson(withRule);
        String withComputation = ruled.add(ruled.computation(ruled.num("/Checks/Validation/Total"))
                .name("CopyCap")
                .commonPrecondition(ruled.num("/Checks/Validation/Observed").filled()
                        .and(ruled.num("/Checks/Rates/Cap").gt(0)))
                .when(ruled.num("/Checks/Validation/Observed").gt(0)
                        .and(ruled.num("/Checks/Rates/Cap").gt(0)))
                .calc(ruled.num("/Checks/Rates/Cap").value())
                .message(en("Total follows cap.")).build()).toJson();
        return withComputation.replace("[../Rates/Cap]", "[Cap]");
    }

    private static String leafWithResidualRuleAndComputation() {
        String base = DocumentModelDraft.named("leaf").locale("en_US")
                .rootGroup("Leaf", leaf -> leaf
                        .numberField("Source", field -> field.fractionDigits(0))
                        .numberField("Target", field -> field.fractionDigits(0)))
                .build().toJson();
        BoundModel model = BoundModel.ofJson(base);
        String withRule = model.add(model.rule(model.group("/Leaf")).name("ResidualRule")
                .errorField(model.num("/Leaf/Source").field())
                .when(model.num("/Leaf/Source").gt(0))
                .code("RESIDUAL").severity(Severity.ERROR)
                .message(en("Source is positive.")).build()).toJson();
        BoundModel ruled = BoundModel.ofJson(withRule);
        String complete = ruled.add(ruled.computation(ruled.num("/Leaf/Target"))
                .name("ResidualComputation").calc(ruled.num("/Leaf/Source").value())
                .message(en("Target follows source.")).build()).toJson();
        return complete.replace("[Source] > 0", "[/Unknown/Source] > 0")
                .replace("[Source]", "[/Unknown/Source]");
    }

    private static String middleMountingLeaf() {
        return DocumentModelDraft.named("middle").locale("en_US")
                .include("leaf", "leaf")
                .rootGroup("Middle", middle -> middle.mount("Nested", "leaf"))
                .build().toJson();
    }

    private static String duplicateNamedResidualRules() {
        String base = DocumentModelDraft.named("checks").locale("en_US")
                .rootGroup("Checks", checks -> checks
                        .group("A", group -> group.numberField("Value", field -> field.fractionDigits(0)))
                        .group("B", group -> group.numberField("Value", field -> field.fractionDigits(0))))
                .build().toJson();
        BoundModel model = BoundModel.ofJson(base);
        String first = model.add(model.rule(model.group("/Checks/A")).name("Check")
                .errorField(model.num("/Checks/A/Value").field())
                .when(model.num("/Checks/A/Value").gt(0))
                .code("A_CHECK").severity(Severity.ERROR).message(en("A check.")).build()).toJson();
        BoundModel withFirst = BoundModel.ofJson(first);
        String both = withFirst.add(withFirst.rule(withFirst.group("/Checks/B")).name("Check")
                .errorField(withFirst.num("/Checks/B/Value").field())
                .when(withFirst.num("/Checks/B/Value").gt(0))
                .code("B_CHECK").severity(Severity.ERROR).message(en("B check.")).build()).toJson();
        return both.replace("[Value] > 0", "[/Unknown/Value] > 0");
    }

    private static String policyMounting(String alias, String reference, boolean excludeRules,
            boolean excludeComputations, boolean collidingCap) {
        MountOptions options = MountOptions.single();
        if (excludeRules) {
            options = options.excludingRules();
        }
        if (excludeComputations) {
            options = options.excludingComputations();
        }
        MountOptions mountOptions = options;
        return DocumentModelDraft.named("policy").locale("en_US")
                .include(alias, reference)
                .rootGroup("Policy", policy -> {
                    policy.field("PolicyNo", FieldKind.STRING);
                    if (collidingCap) {
                        policy.numberField("Cap", field -> field.fractionDigits(0));
                    }
                    policy.mount("Cover", alias, mountOptions);
                })
                .build().toJson();
    }

    private static ModelResolver resolver(Map<String, String> models) {
        return models::get;
    }
}
