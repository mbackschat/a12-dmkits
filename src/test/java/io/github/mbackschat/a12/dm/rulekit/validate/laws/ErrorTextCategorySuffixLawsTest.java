package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §13 — the category suffix an error-text parameter may carry.
 *
 * <p>A message parameter may project a field through an enum CATEGORY, written {@code $Field -> Band$}. A12
 * checks it in two steps, both in {@code FullChecks_ErrorTextParameterParser}: {@code checkAndGetCategoryName}
 * answers {@code NO_CATEGORY} when the arrow carries no name, and {@code checkAccessToCategoryValue} then
 * answers {@code FIELD_NO_ENUM} for a field that is not an enumeration and {@code INVALID_CATEGORY} for one
 * whose type declares no such category.
 *
 * <p><b>Membership runs first</b>, because {@code checkFieldRef} resolves the field against the condition to
 * get the id it then hands to the category check — so a parameter naming a field the condition never reads
 * draws {@code INVALID_FIELD} whatever its suffix says.
 *
 * <p>Own domain (an e-commerce catalogue desk).
 */
class ErrorTextCategorySuffixLawsTest {

    private static final String CATALOGUE = DocumentModelDraft.named("catalogue-error-text-category")
            .locale("en_US")
            .rootGroup("Catalogue", c -> c
                    .stringField("Reference", x -> { })
                    .stringField("Note", x -> { })
                    .numberField("Amount", x -> { })
                    .enumField("Grade", e -> e.value("NEW").value("USED")
                            .category("Band", "NEW", "USED"))
                    .enumField("Plain", e -> e.value("NEW").value("USED"))
                    .stringField("Unread", x -> { }))
            .build().toJson();

    /** Every field but `Unread` is read, so membership is satisfied and the suffix is what each row measures. */
    private static final String READS_THEM_ALL = "FieldFilled(Reference) And FieldFilled(Note) "
            + "And FieldFilled(Amount) And FieldFilled(Grade) And FieldFilled(Plain)";

    @Test
    void whichCategorySuffixAnErrorTextParameterMayCarry() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a plain parameter — the control", reporting("$Reference$"));
        actual.put("an enum projected through a category it declares", reporting("$Grade -> Band$"));
        actual.put("an enum projected through a category it does not", reporting("$Grade -> NoSuch$"));
        actual.put("an enum that declares NO categories at all", reporting("$Plain -> Band$"));
        actual.put("a NUMBER field with a category suffix", reporting("$Amount -> Band$"));
        actual.put("a TEXT field with a category suffix", reporting("$Note -> Band$"));
        actual.put("the arrow with no category name after it", reporting("$Grade ->$"));
        actual.put("a field the condition never reads, with a valid suffix", reporting("$Unread -> Band$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a plain parameter — the control", KernelLaws.ADMITTED);
        expected.put("an enum projected through a category it declares", KernelLaws.ADMITTED);
        expected.put("an enum projected through a category it does not", "INVALID_CATEGORY");
        expected.put("an enum that declares NO categories at all", "INVALID_CATEGORY");
        expected.put("a NUMBER field with a category suffix", "FIELD_NO_ENUM");
        expected.put("a TEXT field with a category suffix", "FIELD_NO_ENUM");
        expected.put("the arrow with no category name after it", "NO_CATEGORY");
        // Membership is asked first, because the category check needs the id that resolution produces.
        expected.put("a field the condition never reads, with a valid suffix", "INVALID_FIELD");
        assertEquals(expected, actual, "which category suffix an error-text parameter may carry");
    }

    private static String reporting(String text) {
        return KernelLaws.messageVerdict(CATALOGUE, "/Catalogue", "../Reference",
                READS_THEM_ALL, "en_US", text);
    }
}
