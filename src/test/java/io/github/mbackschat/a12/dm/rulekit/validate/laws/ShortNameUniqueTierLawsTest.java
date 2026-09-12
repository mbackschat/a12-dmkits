package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import org.junit.jupiter.api.Test;

/**
 * The UNIQUE half of the model-wide short-name tier — the two rows its ambiguous sibling does not carry.
 *
 * <p>{@link ShortNameTierLawsTest} holds the same grid with {@code Code} declared in BOTH subgroups, where
 * the flag chooses between two rejections ({@code MVK_INVALID_ENTITY} and {@code MVK_FIELDNAME_NOT_UNIQUE}).
 * That pair cannot show the fallback SUCCEEDING, so on its own it leaves open whether the flag enables a
 * lookup or merely changes which refusal is reported. These rows move exactly one thing — the sibling group
 * declares {@code Other} instead of a second {@code Code}, making the model-wide name unique — and the flag
 * then separates a rejection from an acceptance.
 *
 * <p>The rows were executed in a 2026-09-08 session probe and recorded in PORTABLE-CONSISTENCY-PROPOSAL §3.4
 * as evidence that was explicitly <em>not</em> locked; this is that lock. It is the Kernel-side oracle for
 * the portable checker's three-way reference classification, whose {@code Resolved} arm has no other
 * accept-side witness.
 *
 * <p>The condition is a raw bare-name string because the claim is about a path FORM the typed renderer never
 * emits — it always writes canonical absolute paths — so a bare short name is unauthorable through the
 * builder rather than merely unusual.
 */
class ShortNameUniqueTierLawsTest {

    private static final String ADMITTED = KernelLaws.ADMITTED;

    /** One model-wide {@code Code}: the sibling group carries {@code Other}, not a second {@code Code}. */
    private static final String FLAG_ON = DocumentModelDraft.named("catalog-unique-tier").locale("en_US")
            .rootGroup("Catalog", c -> c
                    .field("Note", FieldKind.STRING)
                    .group("Warehouse", w -> w.field("Code", FieldKind.STRING))
                    .group("Store", s -> s.field("Other", FieldKind.STRING)))
            .build().toJson();
    private static final String FLAG_OFF =
            ModelDocument.read(FLAG_ON).setFieldRefByShortNameAllowed(false).toJson();

    @Test
    void aUniqueModelWideNameResolvesOnlyWhenTheFlagIsOn() {
        String condition = "FieldFilled(Note) And FieldFilled(Code)";
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("unique model-wide, flag OFF", verdict(FLAG_OFF, condition));
        actual.put("unique model-wide, flag ON", verdict(FLAG_ON, condition));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("unique model-wide, flag OFF", "MVK_INVALID_ENTITY");
        expected.put("unique model-wide, flag ON", ADMITTED);
        assertEquals(expected, actual, "the flag enables the model-wide lookup, it does not merely retitle it");
    }

    /** The host group declares neither name, so admission above can only have come from the model-wide tier. */
    @Test
    void aNameNoGroupDeclaresStaysRejectedWithTheFlagOn() {
        assertEquals("MVK_INVALID_ENTITY", verdict(FLAG_ON, "FieldFilled(Note) And FieldFilled(Nope)"));
    }

    private static String verdict(String json, String condition) {
        ValidationResult r = KernelLaws.checkModel(json, "/Catalog", "../Note", condition);
        return r.ok() ? ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("?"))
                .distinct()
                .reduce((a, b) -> a + "," + b)
                .orElse("?");
    }
}
