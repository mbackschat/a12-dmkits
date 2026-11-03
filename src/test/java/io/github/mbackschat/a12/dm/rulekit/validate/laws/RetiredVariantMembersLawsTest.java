package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * A rule's, field's or computation's {@code variantFilter}, and a rule's {@code variantErrorMessages}, change
 * nothing on kernel 31.1.1, whatever their shape ([KERNEL-FINDINGS KF299], corrected 2026-11-02).
 *
 * <p>DM 29.1 retired both members (A12K-3986, the migrator's {@code RemoveVariantPropertiesStep}), and the
 * 31.1.1 {@code Rule}, {@code Field} and {@code Computation} model classes declare neither, so the reader passes
 * over them as it passes over any member it does not know. On 30.8.1 an {@code ONLY} filter took a rule or
 * computation out of the check and deleted a field, and five shapes left no model; the rows below are those
 * shapes.
 *
 * <p>Each row is compared with the same model without the member, on a twin that A12 refuses and one it
 * admits: "the member is ignored" predicts both verdicts unchanged, while the 30.8.1 account predicts the
 * refused twin admitted under {@code ONLY} and the unreadable shapes refused. The field carrier's twin is a rule
 * that names the field, because deleting a field shows up only as a reference that no longer resolves. An
 * unknown member is the last row's control: it shows the reader's general policy that the retired members
 * now fall under.
 *
 * <p>Own domain (the shared subscription fixture).
 */
class RetiredVariantMembersLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TEXT_MAP = "[{\"locale\":\"en_US\",\"text\":\"premium wording\"}]";

    private static final Map<String, String> FILTERS = shapes(
            "ONLY one variant", "{\"filterType\":\"ONLY\",\"variants\":[\"premium\"]}",
            "ONLY the default variant", "{\"filterType\":\"ONLY\",\"variants\":[\"\"]}",
            "ONLY an empty variants list", "{\"filterType\":\"ONLY\",\"variants\":[]}",
            "ONLY with no variants member", "{\"filterType\":\"ONLY\"}",
            "ALL_EXCEPT one variant", "{\"filterType\":\"ALL_EXCEPT\",\"variants\":[\"premium\"]}",
            "an unknown filterType", "{\"filterType\":\"SOMETIMES\",\"variants\":[\"premium\"]}",
            "no filterType", "{\"variants\":[\"premium\"]}",
            "a filterType that is JSON null", "{\"filterType\":null}",
            "a filterType that is an object", "{\"filterType\":{}}",
            "a filterType that is a number", "{\"filterType\":9}",
            "an explicit null", "null",
            "a string instead of an object", "\"ONLY\"");

    private static final Map<String, String> VARIANT_MESSAGES = shapes(
            "an empty map", "{}",
            "a message for a variant", "{\"premium\":" + TEXT_MAP + "}",
            "an explicit null", "null",
            "a text entry with no locale", "{\"premium\":[{\"text\":\"x\"}]}",
            "a JSON array instead of a map", "[]",
            "a null value", "{\"premium\":null}",
            "a single entry object rather than an array of them", "{\"premium\":{\"locale\":\"en_US\",\"text\":\"x\"}}",
            "a text entry with no text", "{\"premium\":[{\"locale\":\"en_US\"}]}");

    @Test
    void aRetiredVariantMemberLeavesEveryVerdictAsWithoutIt() {
        Map<String, Function<String, String>> twins = new LinkedHashMap<>();
        twins.put("a sound rule", member -> ruleProbe("FieldFilled(Tier)", member));
        twins.put("a rule naming no field", member -> ruleProbe("FieldFilled(NoSuchFieldAnywhere)", member));
        twins.put("a sound computation", member -> computationProbe("42.00", member));
        twins.put("a computation naming no field", member -> computationProbe("[NoSuchFieldAnywhere]", member));
        twins.put("a field a rule names", this::fieldProbe);

        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        twins.forEach((twin, probe) -> {
            String control = KernelLaws.verdictOf(probe.apply(null));
            expected.put(twin + " — the control", twin.contains("naming no field") ? "MVK_INVALID_ENTITY" : KernelLaws.ADMITTED);
            actual.put(twin + " — the control", control);
            Map<String, String> rows = new LinkedHashMap<>();
            FILTERS.forEach((shape, json) -> rows.put("variantFilter: " + shape, "{\"variantFilter\":" + json + "}"));
            if (twin.contains("rule")) {
                VARIANT_MESSAGES.forEach((shape, json) ->
                        rows.put("variantErrorMessages: " + shape, "{\"variantErrorMessages\":" + json + "}"));
            }
            rows.put("an unknown member", "{\"colourScheme\":{\"tone\":\"warm\"}}");
            rows.forEach((row, member) -> {
                expected.put(twin + " / " + row, control);
                actual.put(twin + " / " + row, KernelLaws.verdictOf(probe.apply(member)));
            });
        });
        assertEquals(expected, actual, "each retired member's verdict against the twin without it");
    }

    private static String ruleProbe(String condition, String member) {
        String written = ModelWriter.addRule(Fixtures.subscriptionJson(), "/Subscription", "VariantProbe",
                "../Tier", "PROBE", condition, "ERROR", List.of(en("x"), de("y")));
        return withMembers(written, "VariantProbe", "Rule", member);
    }

    private static String computationProbe(String operation, String member) {
        DraftComputation draft = DraftComputation.builder()
                .groupPath("/Subscription/Billing").name("VariantProbe").computedFieldRelPath("../EffectiveFee")
                .alternatives(List.of(new DraftComputation.Alternative(null, operation, null)))
                .messages(List.of(en("x"), de("y")))
                .build();
        String written = ModelWriter.addComputation(Fixtures.subscriptionJson(), draft, List.of(en("x"), de("y")));
        return withMembers(written, "VariantProbe", "Computation", member);
    }

    private String fieldProbe(String member) {
        String written = ModelWriter.addRule(Fixtures.subscriptionJson(), "/Subscription", "TierProbe",
                "../Tier", "PROBE", "FieldNotFilled(Tier)", "ERROR", List.of(en("x"), de("y")));
        return withMembers(written, "Tier", "Field", member);
    }

    /** [json] with every member of [members] (absent when null) set on the body of the declaration [name]. */
    private static String withMembers(String json, String name, String kind, String members) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(json);
            if (members != null) {
                ObjectNode body = bodyOf(root, name, kind);
                MAPPER.readTree(members).fields().forEachRemaining(entry -> body.set(entry.getKey(), entry.getValue()));
            }
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the writer's own output and the raw fragment must parse", unreadable);
        }
    }

    /** The body of the declaration [name] of [kind], wherever the writer put it. */
    private static ObjectNode bodyOf(ObjectNode root, String name, String kind) {
        for (JsonNode top : root.path("content").path("modelRoot").path("rootGroups")) {
            ObjectNode found = find(top, name, kind);
            if (found != null) {
                return found;
            }
        }
        throw new IllegalStateException("no " + kind + " named " + name + " where this test writes it");
    }

    private static ObjectNode find(JsonNode node, String name, String kind) {
        if (name.equals(node.path("name").asText()) && node.has(kind)) {
            return (ObjectNode) node.path(kind);
        }
        for (JsonNode child : node.path("Group").path("elements")) {
            ObjectNode found = find(child, name, kind);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Map<String, String> shapes(String... labelThenJson) {
        Map<String, String> shapes = new LinkedHashMap<>();
        for (int i = 0; i < labelThenJson.length; i += 2) {
            shapes.put(labelThenJson[i], labelThenJson[i + 1]);
        }
        return shapes;
    }
}
