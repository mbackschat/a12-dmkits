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
 * KERNEL-FINDINGS §13 — the three MODEL-WIDE name-uniqueness laws, and which a stored model reaches.
 *
 * <p>[MVK-LEDGER] carries {@code MVK_NAME_GROUP_DUPLICATE}, {@code MVK_NAME_ROOT_GROUP_DUPLICATE} and
 * {@code MVK_NAME_FIELDS_DUPLICATE} as {@code n/a-structural}. The portable checker's
 * {@code ELEMENT_NAME_UNIQUENESS} is a different law — no group holds two children of the same name — so if
 * any of these three fires on a stored model it is a coverage gap rather than a duplicate.
 *
 * <p>Own domain (insurance).
 */
class ModelWideNameUniquenessLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String POLICY = DocumentModelDraft.named("policy-names").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Reference", f -> { })
                    .group("Insured", i -> i.stringField("InsuredName", f -> { }))
                    .group("Broker", b -> b.stringField("BrokerName", f -> { })))
            .build().toJson();

    /** The fixture with [patch] applied to its root-group list. */
    private static String patched(java.util.function.Consumer<ArrayNode> patch) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            patch.accept((ArrayNode) root.at("/content/modelRoot/rootGroups"));
            return MAPPER.writeValueAsString(root);
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static ArrayNode policyElements(ArrayNode rootGroups) {
        return (ArrayNode) rootGroups.get(0).at("/Group/elements");
    }

    private static ObjectNode group(String id, String name, String fieldId, String fieldName) throws Exception {
        return (ObjectNode) MAPPER.readTree(
                "{\"type\":\"Group\",\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"Group\":{\"elements\":["
                        + "{\"type\":\"Field\",\"id\":\"" + fieldId + "\",\"name\":\"" + fieldName + "\","
                        + "\"Field\":{\"fieldType\":{\"type\":\"StringType\"}}}]}}");
    }

    @Test
    void whichModelWideNameCollisionsAStoredModelCanReach() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the fixture untouched — the control", KernelLaws.verdictOf(POLICY));
        actual.put("a SECOND root group with a distinct name",
                KernelLaws.verdictOf(patched(roots -> add(roots, "g9", "Claimant", "f9", "ClaimantName"))));
        actual.put("a second root group with the SAME name as the first",
                KernelLaws.verdictOf(patched(roots -> add(roots, "g9", "Policy", "f9", "Other"))));
        actual.put("two subgroups of the SAME parent sharing a name",
                KernelLaws.verdictOf(patched(roots ->
                        addGroup(policyElements(roots), "g10", "Insured", "f10", "Duplicate"))));
        actual.put("two subgroups of DIFFERENT parents sharing a name",
                KernelLaws.verdictOf(patched(roots -> {
                    add(roots, "g9", "Claimant", "f9", "ClaimantName");
                    addGroup((ArrayNode) roots.get(1).at("/Group/elements"), "g10", "Insured", "f10", "Dup");
                })));
        actual.put("two fields of different groups sharing a name",
                KernelLaws.verdictOf(patched(roots ->
                        addGroup(policyElements(roots), "g11", "Agent", "f11", "InsuredName"))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the fixture untouched — the control", KernelLaws.ADMITTED);
        expected.put("a SECOND root group with a distinct name", KernelLaws.ADMITTED);
        // A build refusal with NO code, so `MVK_NAME_ROOT_GROUP_DUPLICATE` is not what a stored model draws
        // — the defect is real and the class is not.
        expected.put("a second root group with the SAME name as the first", KernelLaws.NOT_BUILDABLE);
        // The checker's own `ELEMENT_NAME_UNIQUENESS` law, and the same refusal tier.
        expected.put("two subgroups of the SAME parent sharing a name", KernelLaws.NOT_BUILDABLE);
        // …and the two MODEL-WIDE laws are not laws of a stored model at all: a name may repeat freely
        // across parents. A checker widening the within-parent law to the model would refuse both of these.
        expected.put("two subgroups of DIFFERENT parents sharing a name", KernelLaws.ADMITTED);
        expected.put("two fields of different groups sharing a name", KernelLaws.ADMITTED);

        assertEquals(expected, actual, "which model-wide name collisions a stored A12 model can reach");
    }

    private static void add(ArrayNode roots, String id, String name, String fieldId, String fieldName) {
        addGroup(roots, id, name, fieldId, fieldName);
    }

    private static void addGroup(ArrayNode into, String id, String name, String fieldId, String fieldName) {
        try {
            into.add(group(id, name, fieldId, fieldName));
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }
}
