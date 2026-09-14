package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §13 — which of the model's own HEADER laws a stored A12 model can reach.
 *
 * <p>[MVK-LEDGER] carries three: the model-wide digit budget, the codegen target language, and unique
 * supported languages. Two of them name members A12's metamodel document carries and DM-JSON does not —
 * {@code DataCollectorUtils.addMaxDigits} writes a CONSTANT and
 * {@code addProgrammingLanguageToModelProperties} writes the codegen parameter — so this grid is what
 * separates "the collector always fills it" from "a stored model can leave it out".
 *
 * <p><b>It also settles a claim already published.</b> [KF362] records that a hand-written model draws these
 * two codes, which is why its fixtures build through {@code DocumentModelDraft}. That claim and the collector
 * read above cannot both be right, so the hand-written shape is measured here rather than argued about.
 *
 * <p>Own domain (insurance).
 */
class ModelHeaderSlotLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String POLICY = DocumentModelDraft.named("policy-header").locale("en_US")
            .rootGroup("Policy", p -> p.stringField("Reference", f -> { }))
            .build().toJson();

    /** The built fixture with [patch] applied to its parsed tree. */
    private static String patched(java.util.function.Consumer<ObjectNode> patch) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            patch.accept(root);
            return MAPPER.writeValueAsString(root);
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    /** The shape a grid reaches for when it writes a model by hand rather than building one. */
    private static final String HAND_WRITTEN = """
            {"header":{"id":"policy-hand","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}}
              ]}}
             ]}}}
            """;

    @Test
    void whichHeaderLawsAStoredModelCanReach() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the built fixture — the control", KernelLaws.verdictOf(POLICY));
        actual.put("no `modelConfig` at all",
                KernelLaws.verdictOf(patched(root -> ((ObjectNode) root.path("content")).remove("modelConfig"))));
        actual.put("a header declaring the same locale twice",
                KernelLaws.verdictOf(patched(root -> ((ArrayNode) root.path("header").path("locales"))
                        .add(MAPPER.createObjectNode().put("code", "en_US")))));
        actual.put("no `locales` at all",
                KernelLaws.verdictOf(patched(root -> ((ObjectNode) root.path("header")).remove("locales"))));
        for (String member : new String[] {"id", "modelType", "modelVersion", "labels", "annotations",
                "modelReferences"}) {
            actual.put("no `" + member + "`",
                    KernelLaws.verdictOf(patched(root -> ((ObjectNode) root.path("header")).remove(member))));
        }
        actual.put("a model written by hand", KernelLaws.verdictOf(HAND_WRITTEN));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the built fixture — the control", KernelLaws.ADMITTED);
        // The two "header" laws are the collector's constants, so nothing a stored model omits reaches them:
        // `maxDigits` is `IProjectSpecificSettings.MAXDIGITS_IN_NUMBERS` and the programming language is the
        // codegen parameter. A model with no `modelConfig` is admitted outright.
        expected.put("no `modelConfig` at all", KernelLaws.ADMITTED);
        // …and a repeated locale is refused at the BUILD tier with no class, so
        // `MVK_SUPP_LANGUAGES_DUPLICATE` has nothing to fire on either — the collector reads a language SET.
        expected.put("a header declaring the same locale twice", KernelLaws.NOT_BUILDABLE);
        expected.put("no `locales` at all", KernelLaws.NOT_BUILDABLE);
        // Of the header's other members exactly ONE is load-bearing, and it is not the one a reader expects:
        // the model builds with no `id` and no `modelType`, and not without a `modelVersion`.
        expected.put("no `id`", KernelLaws.ADMITTED);
        expected.put("no `modelType`", KernelLaws.ADMITTED);
        expected.put("no `modelVersion`", KernelLaws.NOT_BUILDABLE);
        expected.put("no `labels`", KernelLaws.ADMITTED);
        expected.put("no `annotations`", KernelLaws.ADMITTED);
        expected.put("no `modelReferences`", KernelLaws.ADMITTED);
        // **The correction.** A hand-written model IS unbuildable, as [KF362] recorded — but for one
        // member and no MVK class: it omits `modelVersion`. Neither header code is what refuses it, and both
        // are unreachable, their metamodel members being the collector's own constants. So the practical
        // advice stands and its reason changes: hand-write a fixture if you like, but carry a
        // `header.modelVersion`.
        expected.put("a model written by hand", KernelLaws.NOT_BUILDABLE);

        assertEquals(expected, actual, "which model-header laws a stored A12 model can reach");
    }
}
