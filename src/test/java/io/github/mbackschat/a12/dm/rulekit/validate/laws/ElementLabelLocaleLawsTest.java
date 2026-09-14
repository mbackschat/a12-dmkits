package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * KERNEL-FINDINGS §8 — which locales a FIELD's or a GROUP's display label owes, if any.
 *
 * <p>[KF321] measured the same question for an enumeration's VALUE labels, where the law is "started and not
 * finished": once any value answers a DECLARED locale, every value owes every declared locale, and labels
 * written only for an undeclared locale are admitted. Our builder applies that law to element labels too, by
 * analogy and with the analogy written into its comment — which is the shape this project treats as a defect
 * until measured, because a refusal broader than its basis makes a legal model unauthorable AND unreadable.
 *
 * <p>The rows below are the measurement that analogy never had.
 */
class ElementLabelLocaleLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BILINGUAL_MISS = "a label for only ONE of two declared locales";

    /** The order fixture with [labelJson] written onto the named element, under [locales]. */
    private static String labelled(String element, String labelJson, String... locales) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            var declared = ((ObjectNode) root.path("header")).putArray("locales");
            for (String locale : locales.length == 0 ? new String[] {"en_US"} : locales) {
                declared.addObject().put("code", locale);
            }
            ObjectNode group = (ObjectNode) root.path("content").path("modelRoot").path("rootGroups").get(0);
            if ("group".equals(element)) {
                ((ObjectNode) group.path("Group")).set("label", MAPPER.readTree(labelJson));
                return root.toString();
            }
            for (var member : group.path("Group").path("elements")) {
                if ("CustomerName".equals(member.path("name").asText()) && member.has("Field")) {
                    ((ObjectNode) member.path("Field")).set("label", MAPPER.readTree(labelJson));
                    return root.toString();
                }
            }
            throw new IllegalStateException("the fixture no longer declares the root field `CustomerName`");
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    private static String label(String... localeAndText) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < localeAndText.length; i += 2) {
            json.append(i == 0 ? "" : ",")
                    .append("{\"locale\":\"").append(localeAndText[i])
                    .append("\",\"text\":\"").append(localeAndText[i + 1]).append("\"}");
        }
        return json.append("]").toString();
    }

    @Test
    void whichLocalesAnElementLabelOwes() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a FIELD with no label — the control", KernelLaws.verdictOf(labelled("field", "[]")));
        actual.put("a FIELD label for the declared locale",
                KernelLaws.verdictOf(labelled("field", label("en_US", "Customer"))));
        actual.put("a FIELD label for an UNDECLARED locale only",
                KernelLaws.verdictOf(labelled("field", label("fr_FR", "Client"))));
        actual.put("a FIELD " + BILINGUAL_MISS,
                KernelLaws.verdictOf(labelled("field", label("en_US", "Customer"), "en_US", "de_DE")));
        actual.put("a GROUP label for an UNDECLARED locale only",
                KernelLaws.verdictOf(labelled("group", label("fr_FR", "Commande"))));
        actual.put("a GROUP " + BILINGUAL_MISS,
                KernelLaws.verdictOf(labelled("group", label("en_US", "Order"), "en_US", "de_DE")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a FIELD with no label — the control", KernelLaws.ADMITTED);
        expected.put("a FIELD label for the declared locale", KernelLaws.ADMITTED);
        expected.put("a FIELD label for an UNDECLARED locale only", KernelLaws.ADMITTED);
        expected.put("a FIELD " + BILINGUAL_MISS, KernelLaws.ADMITTED);
        expected.put("a GROUP label for an UNDECLARED locale only", KernelLaws.ADMITTED);
        expected.put("a GROUP " + BILINGUAL_MISS, KernelLaws.ADMITTED);
        assertEquals(expected, actual, "which locales an element label owes");
    }
}
