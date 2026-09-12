package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * How the Kernel READS two `modelConfig` members that decide reference resolution — measured because a
 * reader stricter than A12's makes a legal model behave differently here than there, and the difference is
 * silent (PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN, B0).
 *
 * <p>Both members are read on the way IN, so an over-strict reading is the sharper half of the
 * client-refusal rule: it does not merely refuse to author something, it changes the verdict on a model A12
 * accepts unchanged.
 */
class ModelConfigCoercionLawsTest {

    /** Two groups, each with a uniquely-named field, so a bare cross-group reference needs the model-wide tier. */
    private static final String ORDERS = DocumentModelDraft.named("orders-config").locale("en_US")
            .rootGroup("Order", o -> o
                    .group("Head", h -> h.field("Code", FieldKind.STRING))
                    .group("Body", b -> b.field("Note", FieldKind.STRING)))
            .build().toJson();

    private static String withShortNameFlag(String literal) {
        String enabled = ModelDocument.read(ORDERS).setFieldRefByShortNameAllowed(true).toJson();
        String replaced = enabled.replace("\"fieldRefByShortNameAllowed\" : true", literal)
                .replace("\"fieldRefByShortNameAllowed\": true", literal);
        assertTrue(replaced.contains(literal), "the flag literal was not substituted: " + literal);
        return replaced;
    }

    /**
     * The flag QUOTED enables the tier exactly as the boolean does. Jackson's `asText()`/`asBoolean()`
     * coercion reaches it, so a reader that demanded a JSON boolean would resolve every cross-group short
     * name differently from A12 on the same file.
     */
    @Test
    void theShortNameFlagIsReadFromAStringToo() {
        String reference = "FieldFilled(Code) And FieldFilled(Note)";
        KernelLaws.acceptsModel(withShortNameFlag("\"fieldRefByShortNameAllowed\" : true"),
                "/Order/Head", "../Code", reference);
        KernelLaws.acceptsModel(withShortNameFlag("\"fieldRefByShortNameAllowed\" : \"true\""),
                "/Order/Head", "../Code", reference);
        // The control that keeps the two rows above from passing for an unrelated reason.
        KernelLaws.rejectsModel(withShortNameFlag("\"fieldRefByShortNameAllowed\" : false"),
                "/Order/Head", "../Code", reference, "MVK_INVALID_ENTITY");
    }

    /**
     * An UNRECOGNIZED `conditionLanguage` code makes the model UNUSABLE — it is not a fallback at all.
     *
     * <p>Reading `AbstractMultiLanguageHandler`'s constructor predicts a silent substitution of
     * `Locale.GERMANY` for any locale outside `SUPPORTED_CONDITION_LANGUAGES`, and that prediction is wrong
     * at this layer: a gate ahead of it refuses the model with "Die im ValidierungsModus übergebene Sprache
     * fr_FR wird nicht unterstützt", uncoded, so neither language's keywords are ever tried. Both spellings
     * are measured, because a single row could not tell a refusal from a German reading.
     */
    @Test
    void anUnrecognizedConditionLanguageMakesTheModelUnusable() {
        String exotic = ModelDocument.read(ORDERS).setConditionLanguage("fr_FR").toJson();

        for (String condition : List.of("FeldAngegeben(Code)", "FieldFilled(Code)")) {
            ValidationResult result = KernelLaws.checkModel(exotic, "/Order/Head", "../Code", condition);
            assertFalse(result.ok(), () -> "an unsupported condition language must not be accepted: " + condition);
            assertTrue(
                    result.diagnostics().stream().anyMatch(d -> d.code().isEmpty()
                            && d.summary().contains("fr_FR")),
                    () -> "the refusal must name the unsupported language, got: " + result.diagnostics());
        }
        // The control: the same model with a supported language accepts that language's spelling.
        KernelLaws.acceptsModel(ModelDocument.read(ORDERS).setConditionLanguage("de_DE").toJson(),
                "/Order/Head", "../Code", "FeldAngegeben(Code)");
    }
}
