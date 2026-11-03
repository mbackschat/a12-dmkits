package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * Whether an EMPTY category name preempts the entity and membership gates that precede it.
 *
 * <p>Discharges a12-kernel-lean's {@code SPEC-2026-09-08-08}: entity resolution answers before an empty name, so a
 * parameter that also names an undeclared field reports that instead. <i>Corrected 2026-11-03:</i> on kernel 30.8.1
 * the empty name was also asked after condition membership; 31.1.1 asks it before, so an unread field with an empty
 * category is refused for the empty name (KERNEL-FINDINGS KF450). The doubled-arrow
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
                    .stringField("Unread", x -> { })
                    .enumField("Shelved", e -> e.value("NEW").value("USED").category("Band", "NEW", "USED")))
            .build().toJson();

    private static final String READS = "FieldFilled(Reference) And FieldFilled(Grade)";

    private static String reporting(String text) {
        return KernelLaws.messageVerdict(CATALOGUE, "/Catalogue", "../Reference", READS, "en_US", text);
    }

    @Test
    void anEmptyCategoryNameIsAskedAfterEntityResolutionAndBeforeMembership() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a referenced enum, empty category", reporting("$Grade ->$"));
        actual.put("a field the condition never reads, empty category", reporting("$Unread ->$"));
        actual.put("a field the model does not declare, empty category", reporting("$NoSuchField ->$"));
        actual.put("a referenced enum, doubled arrow", reporting("$Grade -> -> Band$"));
        actual.put("an unread field, doubled arrow", reporting("$Unread -> -> Band$"));
        actual.put("an undeclared field, doubled arrow", reporting("$NoSuchField -> -> Band$"));
        actual.put("a referenced enum, named category — the control", reporting("$Grade -> Band$"));
        actual.put("an unread enum declaring the category — membership still asked", reporting("$Shelved -> Band$"));
        actual.put("the same unread enum, empty category", reporting("$Shelved ->$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a referenced enum, empty category", "NO_CATEGORY");
        // Kernel 31.1.1 asks for the empty name before membership, so an unread field is refused for it.
        expected.put("a field the condition never reads, empty category", "NO_CATEGORY");
        // …while entity resolution still comes first, so an undeclared name outranks the empty name.
        expected.put("a field the model does not declare, empty category", "INVALID_ENTITY");
        expected.put("a referenced enum, doubled arrow", "NO_CATEGORY");
        expected.put("an unread field, doubled arrow", "NO_CATEGORY");
        expected.put("an undeclared field, doubled arrow", "INVALID_ENTITY");
        expected.put("a referenced enum, named category — the control", KernelLaws.ADMITTED);
        // The within-model control for the order: `Shelved` differs from `Grade` only in being unread, so its
        // named category meets membership and its empty one is refused for the empty name first.
        expected.put("an unread enum declaring the category — membership still asked", "INVALID_FIELD");
        expected.put("the same unread enum, empty category", "NO_CATEGORY");
        assertEquals(expected, actual, "entity resolution, then the empty category name, then membership");
    }
}
