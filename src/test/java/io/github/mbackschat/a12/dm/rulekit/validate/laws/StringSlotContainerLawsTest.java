package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * What A12 does when a member it binds to a STRING holds a JSON container instead.
 *
 * <p>The portable checker left this class unjudged and said so on every report — "each is unmeasured, and
 * under-reporting is the only safe direction". This grid is the measurement that retires that sentence. The
 * question is narrow and the same for every member: a checker meeting an object or an array where a string
 * belongs must know whether A12 refuses the source outright, raises something typed, or quietly coerces —
 * and the three answers demand three different things of it.
 *
 * <p><b>Three carriers, measured separately.</b> A rule's own members, a computation alternative's, and a
 * group's are three different readers, and this project has been wrong before by measuring one carrier and
 * transferring the result. So no row here is read off another.
 *
 * <p><b>Both container shapes, because they are not one shape.</b> An object and an array reach a string
 * slot through different Jackson paths, and the {@code guiPatterns} grid already showed a member that admits a
 * scalar into its value slot while refusing a container there. Measuring only {@code
 * {}} would leave the array answer to inference.
 *
 * <p>Own domain (the shared subscription and order fixtures).
 */
class StringSlotContainerLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String GROUP = "/Subscription/Billing";
    /** A literal, because a bare field name is not an operation — the first control here was dead on that. */
    private static final String OPERATION = "42.00";

    /**
     * The containers, named once so every carrier is asked the identical question.
     *
     * <p>The payload is deliberately neutral and identical across carriers: the question is whether A12 binds
     * a CONTAINER into a string slot at all, which it answers before anything reads what the container holds.
     * Each grid's control is what proves the surrounding model is sound, not the payload.
     */
    private static final Map<String, String> CONTAINERS = new LinkedHashMap<>();

    static {
        CONTAINERS.put("an empty object", "{}");
        CONTAINERS.put("an object with content", "{\"value\":\"x\"}");
        CONTAINERS.put("an empty array", "[]");
        CONTAINERS.put("an array holding a string", "[\"x\"]");
    }

    @Test
    void whatA12DoesWithAContainerInARulesStringSlots() {
        Map<String, String> actual = new LinkedHashMap<>();
        // Liveness: the same probe with the member left alone must be admitted, or every row below is a
        // verdict about a model that was broken before the substitution.
        actual.put("errorCondition — the control", KernelLaws.verdictOf(rule("errorCondition", null)));
        actual.put("errorEntityRelPath — the control", KernelLaws.verdictOf(rule("errorEntityRelPath", null)));
        actual.put("errorCode — the control", KernelLaws.verdictOf(rule("errorCode", null)));
        for (var container : CONTAINERS.entrySet()) {
            actual.put("errorCondition — " + container.getKey(),
                    KernelLaws.verdictOf(rule("errorCondition", container.getValue())));
            actual.put("errorEntityRelPath — " + container.getKey(),
                    KernelLaws.verdictOf(rule("errorEntityRelPath", container.getValue())));
            actual.put("errorCode — " + container.getKey(),
                    KernelLaws.verdictOf(rule("errorCode", container.getValue())));
        }
        assertEquals(expectedForRule(), actual, "what A12 does with a container in a rule's string slots");
    }

    @Test
    void whatA12DoesWithAContainerInAComputationAlternativesStringSlots() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("operation — the control", KernelLaws.verdictOf(alternative("operation", null)));
        for (var container : CONTAINERS.entrySet()) {
            actual.put("operation — " + container.getKey(),
                    KernelLaws.verdictOf(alternative("operation", container.getValue())));
            actual.put("precondition — " + container.getKey(),
                    KernelLaws.verdictOf(alternative("precondition", container.getValue())));
        }
        assertEquals(expectedForAlternative(), actual,
                "what A12 does with a container in a computation alternative's string slots");
    }

    @Test
    void whatA12DoesWithAContainerInAGroupsOrderingSlots() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("indexFieldName — the control", KernelLaws.verdictOf(group("indexFieldName", "\"Sku\"")));
        for (var container : CONTAINERS.entrySet()) {
            actual.put("indexFieldName — " + container.getKey(),
                    KernelLaws.verdictOf(group("indexFieldName", container.getValue())));
        }
        // `sortFields` is the mirror question: it is declared as an ARRAY, so the shapes that ask about its
        // binding are the non-array ones rather than the container ones.
        actual.put("sortFields — an object instead of an array", KernelLaws.verdictOf(group("sortFields", "{}")));
        actual.put("sortFields — a string instead of an array",
                KernelLaws.verdictOf(group("sortFields", "\"Sku\"")));
        actual.put("sortFields — a number instead of an array", KernelLaws.verdictOf(group("sortFields", "7")));
        assertEquals(expectedForGroup(), actual, "what A12 does with a container in a group's ordering slots");
    }

    // --- the expectations, filled from the measured run -------------------------------------------------

    private static Map<String, String> expectedForRule() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("errorCondition — the control", KernelLaws.ADMITTED);
        expected.put("errorEntityRelPath — the control", KernelLaws.ADMITTED);
        expected.put("errorCode — the control", KernelLaws.ADMITTED);
        for (String container : CONTAINERS.keySet()) {
            expected.put("errorCondition — " + container, KernelLaws.UNREADABLE);
            expected.put("errorEntityRelPath — " + container, KernelLaws.UNREADABLE);
            expected.put("errorCode — " + container, KernelLaws.UNREADABLE);
        }
        return expected;
    }

    private static Map<String, String> expectedForAlternative() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("operation — the control", KernelLaws.ADMITTED);
        for (String container : CONTAINERS.keySet()) {
            expected.put("operation — " + container, KernelLaws.UNREADABLE);
            expected.put("precondition — " + container, KernelLaws.UNREADABLE);
        }
        return expected;
    }

    private static Map<String, String> expectedForGroup() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("indexFieldName — the control", KernelLaws.ADMITTED);
        for (String container : CONTAINERS.keySet()) {
            expected.put("indexFieldName — " + container, KernelLaws.UNREADABLE);
        }
        expected.put("sortFields — an object instead of an array", KernelLaws.UNREADABLE);
        expected.put("sortFields — a string instead of an array", KernelLaws.UNREADABLE);
        expected.put("sortFields — a number instead of an array", KernelLaws.UNREADABLE);
        return expected;
    }

    // --- probes, one per carrier ------------------------------------------------------------------------

    /** The subscription fixture with a probe rule whose [member] is replaced, or left alone when null. */
    private static String rule(String member, String rawJson) {
        String written = ModelWriter.addRule(Fixtures.subscriptionJson(), "/Subscription", "ContainerProbe",
                "../Tier", "PROBE", "FieldNotFilled(Tier)", "ERROR", List.of(en("x"), de("y")));
        return substitute(written, StringSlotContainerLawsTest::ruleBody, member, rawJson);
    }

    /** The same fixture with a probe computation whose single alternative's [member] is replaced. */
    private static String alternative(String member, String rawJson) {
        DraftComputation draft = DraftComputation.builder()
                .groupPath(GROUP).name("ContainerProbe").computedFieldRelPath("../EffectiveFee")
                .alternatives(List.of(new DraftComputation.Alternative(null, OPERATION)))
                .messages(List.of(en("x"), de("y")))
                .build();
        String written = ModelWriter.addComputation(Fixtures.subscriptionJson(), draft,
                List.of(en("x"), de("y")));
        return substitute(written, StringSlotContainerLawsTest::firstAlternative, member, rawJson);
    }

    /** The order fixture's repeatable group with [member] replaced. */
    private static String group(String member, String rawJson) {
        return substitute(Fixtures.orderJson(),
                root -> groupBody(root, "Items"), member, rawJson);
    }

    private static String substitute(String modelJson, java.util.function.Function<ObjectNode, ObjectNode> locate,
            String member, String rawJson) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(modelJson);
            ObjectNode target = locate.apply(root);
            if (rawJson != null) {
                target.set(member, MAPPER.readTree(rawJson));
            }
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the raw fragment must parse", unreadable);
        }
    }

    private static ObjectNode ruleBody(ObjectNode root) {
        return (ObjectNode) named(root, "ContainerProbe").path("Rule");
    }

    private static ObjectNode firstAlternative(ObjectNode root) {
        ObjectNode computation = (ObjectNode) findComputation(root);
        var alternatives = computation.path("computationAlternatives");
        if (!alternatives.isArray() || alternatives.isEmpty()) {
            throw new IllegalStateException("the writer no longer emits a computationAlternatives array");
        }
        return (ObjectNode) alternatives.get(0);
    }

    /** The probe computation, wherever the writer puts it — refusing rather than returning an unreached node. */
    private static JsonNode findComputation(JsonNode node) {
        if (node.isObject() && "ContainerProbe".equals(node.path("name").asText())
                && node.has("Computation")) {
            return node.path("Computation");
        }
        for (var child : node) {
            if (child.isContainerNode()) {
                try {
                    return findComputation(child);
                } catch (IllegalStateException keepLooking) {
                    // this branch does not hold the probe; the caller's loop continues
                }
            }
        }
        throw new IllegalStateException("the probe computation is no longer where this test writes it");
    }

    private static ObjectNode groupBody(ObjectNode root, String name) {
        return (ObjectNode) named(root, name).path("Group");
    }

    /** The named element under the single root group, refusing rather than returning an unreached node. */
    private static JsonNode named(ObjectNode root, String name) {
        for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                .path("Group").path("elements")) {
            if (name.equals(element.path("name").asText())) {
                return element;
            }
        }
        throw new IllegalStateException("no element named " + name + " where this test writes it");
    }
}
