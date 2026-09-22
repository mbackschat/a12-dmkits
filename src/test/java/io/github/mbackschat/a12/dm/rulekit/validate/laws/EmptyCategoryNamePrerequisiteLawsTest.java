package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * Whether an EMPTY category name preempts the entity and membership gates that precede it.
 *
 * <p>Discharges a12-kernel-lean's {@code SPEC-2026-09-08-08}: an empty name is the LAST question asked, so a
 * parameter that also names an undeclared field or an unread one reports that instead. The doubled-arrow
 * rows are their measured spelling of the same empty first category, and they behave identically — which is
 * what makes "the name is empty" the condition rather than "the suffix is malformed".
 *
 * <p>The named-category control is what keeps the three refusals from being satisfiable by a grid that
 * refuses everything. Sibling: {@link ErrorTextCategorySuffixLawsTest}, which owns the suffix's own classes.
 */
class EmptyCategoryNamePrerequisiteLawsTest {

    private static final String CATALOGUE = DocumentModelDraft.named("catalogue-empty-category")
            .locale("en_US")
            .rootGroup("Catalogue", c -> c
                    .stringField("Reference", x -> { })
                    .enumField("Grade", e -> e.value("NEW").value("USED").category("Band", "NEW", "USED"))
                    .stringField("Unread", x -> { }))
            .build().toJson();

    private static final String READS = "FieldFilled(Reference) And FieldFilled(Grade)";

    private static String reporting(String text) {
        return KernelLaws.messageVerdict(CATALOGUE, "/Catalogue", "../Reference", READS, "en_US", text);
    }

    @Test
    void anEmptyCategoryNameIsAskedAfterEntityResolutionAndMembership() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a referenced enum, empty category", reporting("$Grade ->$"));
        actual.put("a field the condition never reads, empty category", reporting("$Unread ->$"));
        actual.put("a field the model does not declare, empty category", reporting("$NoSuchField ->$"));
        actual.put("a referenced enum, doubled arrow", reporting("$Grade -> -> Band$"));
        actual.put("an unread field, doubled arrow", reporting("$Unread -> -> Band$"));
        actual.put("an undeclared field, doubled arrow", reporting("$NoSuchField -> -> Band$"));
        actual.put("a referenced enum, named category — the control", reporting("$Grade -> Band$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a referenced enum, empty category", "NO_CATEGORY");
        // Membership is resolved first, so an unread field's empty suffix never reaches the category check.
        expected.put("a field the condition never reads, empty category", "INVALID_FIELD");
        // …and entity resolution precedes membership, so an undeclared name outranks both.
        expected.put("a field the model does not declare, empty category", "INVALID_ENTITY");
        expected.put("a referenced enum, doubled arrow", "NO_CATEGORY");
        expected.put("an unread field, doubled arrow", "INVALID_FIELD");
        expected.put("an undeclared field, doubled arrow", "INVALID_ENTITY");
        expected.put("a referenced enum, named category — the control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the prerequisites answer before the empty category name");
    }
}
