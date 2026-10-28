package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS KF478 — <b>a semantic-index key addresses the level of the group that declares the index,
 * and only that one</b>.
 *
 * <p>[KF476] measured a key on a path crossing one repetition whose own group is the index group. These rows
 * separate that group from the repetition: a path whose index group is the ROOT or a FIXED group above the
 * repetition, a path crossing an unindexed repetition above an indexed one, and a path with a second indexed
 * ancestor. Every probe is {@code Sum(<path> For "X") > 0}, whose own class when its operand is addressed is
 * {@code MVK_PARAMSIZE_INVALIDN} (a lone field).
 *
 * <p>The root and the fixed {@code Cover} carry their index field through a JSON edit, since the typed surface
 * declares one only on a repeatable group; A12 builds such a model ([KF116]). Own domain (insurance claims).
 */
class SemanticIndexPinnedLevelLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String POLICY = DocumentModelDraft.named("policy-pinned-level").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Reference", f -> { })
                    .group("Claims", g -> g.repeatable(20).indexField("ClaimRef")
                            .stringField("ClaimRef", f -> { })
                            .numberField("Amount", f -> f.fractionDigits(2)))
                    .group("Riders", g -> g.repeatable(5)
                            .stringField("RiderRef", f -> { })
                            .group("Legs", l -> l.repeatable(5).indexField("LegRef")
                                    .stringField("LegRef", f -> { })
                                    .numberField("Amount", f -> f.fractionDigits(2))))
                    .group("Cover", c -> c
                            .stringField("CoverRef", f -> { })
                            .stringField("Note", f -> { })
                            .group("Items", i -> i.repeatable(5).numberField("Amount", f -> f.fractionDigits(2)))))
            .build().toJson();

    /** The model with the ROOT also declaring {@code Reference} its index field. */
    private static final String ROOT_INDEXED = withIndexField(POLICY, "Reference");

    /** The model with the fixed {@code Cover} declaring {@code CoverRef} its index field. */
    private static final String COVER_INDEXED = withIndexField(POLICY, "Cover", "CoverRef");

    private static String sum(String model, String group, String errorField, String operand) {
        return KernelLaws.verdictModel(model, group, errorField,
                "FieldFilled(" + errorField.substring(3) + ") And Sum(" + operand + ") > 0");
    }

    @Test
    void theKeyAddressesTheIndexGroupsLevelAndNoOther() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the index group IS the one repetition crossed — the KF476 control",
                sum(POLICY, "/Policy", "../Reference", "Claims/Amount For \"X\""));
        actual.put("a second indexed ancestor: the root",
                sum(ROOT_INDEXED, "/Policy", "../Reference", "Claims/Amount For \"X\""));
        actual.put("no index group on the path — the unindexed control",
                sum(POLICY, "/Policy", "../Reference", "Cover/Items/Amount For \"X\""));
        actual.put("the index group is the ROOT, above the repetition",
                sum(ROOT_INDEXED, "/Policy", "../Reference", "Cover/Items/Amount For \"X\""));
        actual.put("the index group is a FIXED group above the repetition",
                sum(COVER_INDEXED, "/Policy", "../Reference", "Cover/Items/Amount For \"X\""));
        actual.put("an indexed repetition below an unindexed one, from a root rule",
                sum(POLICY, "/Policy", "../Reference", "Riders/Legs/Amount For \"X\""));
        actual.put("the same from a rule the unindexed repetition iterates",
                sum(POLICY, "/Policy/Riders", "../RiderRef", "Legs/Amount For \"X\""));
        actual.put("the same unkeyed — the control that the key is what addresses Legs",
                sum(POLICY, "/Policy/Riders", "../RiderRef", "Legs/Amount"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the index group IS the one repetition crossed — the KF476 control", "MVK_PARAMSIZE_INVALIDN");
        expected.put("a second indexed ancestor: the root", "MVK_MULTIPLE_INDEX_FIELDS");
        expected.put("no index group on the path — the unindexed control", "MVK_NO_INDEX_FIELD");
        expected.put("the index group is the ROOT, above the repetition", "MVK_NO_WILDCARD");
        expected.put("the index group is a FIXED group above the repetition", "MVK_NO_WILDCARD");
        expected.put("an indexed repetition below an unindexed one, from a root rule", "MVK_NO_WILDCARD");
        expected.put("the same from a rule the unindexed repetition iterates", "MVK_PARAMSIZE_INVALIDN");
        expected.put("the same unkeyed — the control that the key is what addresses Legs", "MVK_NO_WILDCARD");
        assertEquals(expected, actual, "which level a semantic-index key addresses");
    }

    /**
     * A key over a ROOT or FIXED-group index addresses no repetition, so A12 admits every read whose repetitions
     * something else addresses: a field of the fixed group itself, its presence, a star on the repetition below,
     * and a read the rule's own iteration binds (a12-kernel-lean SPEC-2026-10-28-02). The unstarred list past the
     * key is the KF478 refusal above, kept here as the control on each carrier.
     */
    @Test
    void aKeyOverARootOrFixedIndexAdmitsWhatElseAddresses() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (var carrier : List.of(Map.entry("root", ROOT_INDEXED), Map.entry("Cover", COVER_INDEXED))) {
            String model = carrier.getValue();
            String on = " (" + carrier.getKey() + " indexed)";
            actual.put("the fixed group's own field" + on, KernelLaws.verdictModel(model, "/Policy", "../Reference",
                    "FieldFilled(Reference) And [Cover/Note For \"X\"] == \"a\""));
            actual.put("its presence" + on, KernelLaws.verdictModel(model, "/Policy", "../Reference",
                    "FieldFilled(Reference) And FieldFilled(Cover/Note For \"X\")"));
            actual.put("a star on the repetition below" + on,
                    sum(model, "/Policy", "../Reference", "Cover/Items*/Amount For \"X\""));
            actual.put("a read the rule's iteration binds" + on, KernelLaws.verdictModel(model, "/Policy/Cover/Items",
                    "../Amount", "[Amount For \"X\"] > 0"));
            actual.put("the unstarred list past the key" + on,
                    sum(model, "/Policy", "../Reference", "Cover/Items/Amount For \"X\""));
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (String on : actual.keySet()) {
            expected.put(on, on.startsWith("the unstarred") ? "MVK_NO_WILDCARD" : KernelLaws.ADMITTED);
        }
        assertEquals(expected, actual, "what a key over a root or fixed-group index admits");
    }

    /**
     * A key does not hide the text around it: A12 names the first defect it reads, so an unaddressed read ahead
     * of a keyed one answers first, and the keyed operand's class answers when it comes first. A key's own
     * reference is the index chain's, which names the key crossing a repetition rather than the walk's class.
     */
    @Test
    void theReadsAroundAKeyAnswerInTextOrder() {
        Map<String, String> actual = new LinkedHashMap<>();
        String keyed = "Sum(Claims/Amount For \"X\") > 0";
        String unaddressed = "[Riders/RiderRef] == \"a\"";
        actual.put("an unaddressed read before a keyed operand", KernelLaws.verdictModel(POLICY, "/Policy",
                "../Reference", "FieldFilled(Reference) And " + unaddressed + " And " + keyed));
        actual.put("the keyed operand first", KernelLaws.verdictModel(POLICY, "/Policy", "../Reference",
                "FieldFilled(Reference) And " + keyed + " And " + unaddressed));
        actual.put("a key that is itself an unaddressed read", sum(POLICY, "/Policy", "../Reference",
                "Claims/Amount For Riders/RiderRef"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an unaddressed read before a keyed operand", "MVK_NO_WILDCARD");
        expected.put("the keyed operand first", "MVK_PARAMSIZE_INVALIDN");
        expected.put("a key that is itself an unaddressed read", "MVK_SEMANTIC_INDEX_INVALID_ITER");
        assertEquals(expected, actual, "which class a text holding a key names first");
    }

    /**
     * A keyed GROUP operand of a group-list quantifier meets the same walk. The duplicate pair is the operator's
     * own class, which A12 asks only once the operands are read: from the root the key addresses `Legs` and
     * leaves `Riders` unaddressed, and from a rule the unindexed repetition iterates it leaves nothing.
     */
    @Test
    void aKeyedGroupOperandIsReadBeforeTheOperatorsOwnGates() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a duplicate the key leaves an unindexed repetition above, from the root",
                KernelLaws.verdictModel(POLICY, "/Policy", "../Reference", "FieldFilled(Reference) And "
                        + "AtLeastOneGroupFilled(Riders/Legs For \"X\", Riders/Legs For \"X\")"));
        actual.put("the same duplicate from a rule the unindexed repetition iterates",
                KernelLaws.verdictModel(POLICY, "/Policy/Riders", "../RiderRef", "FieldFilled(RiderRef) And "
                        + "AtLeastOneGroupFilled(Legs For \"X\", Legs For \"X\")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a duplicate the key leaves an unindexed repetition above, from the root", "MVK_NO_WILDCARD");
        expected.put("the same duplicate from a rule the unindexed repetition iterates", "MVK_DUPLICATE_PARAM1");
        assertEquals(expected, actual, "which class a keyed group-list operand names first");
    }

    /** [model] with the group at [path] (the root when empty) declaring [indexField] its index field. */
    private static String withIndexField(String model, String... pathThenField) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(model);
            ObjectNode group = (ObjectNode) root.path("content").path("modelRoot").path("rootGroups").get(0);
            for (int i = 0; i < pathThenField.length - 1; i++) {
                String name = pathThenField[i];
                group = (ObjectNode) java.util.stream.StreamSupport
                        .stream(group.path("Group").path("elements").spliterator(), false)
                        .filter(element -> name.equals(element.path("name").asText()))
                        .findFirst().orElseThrow(() -> new IllegalStateException("no group " + name));
            }
            ((ObjectNode) group.path("Group")).put("indexFieldName", pathThenField[pathThenField.length - 1]);
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }
}
