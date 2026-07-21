package io.github.mbackschat.a12.dm.adapter.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.ReferenceRewriter;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementChange;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.NumberTrait;

/** MR9 Set 3 — every authored field envelope is visible to semantic diff. */
class ModelDiffFieldCompletenessTest {

    private static final String AMOUNT = "/Invoice/Amount";

    @Test
    void detectsRequirednessWithDirectionalReviewImpact() {
        String optional = number(false, NumberTrait.AMOUNT);
        String required = number(true, NumberTrait.AMOUNT);

        ElementChange tightened = change(ModelDiff.between(optional, required), AMOUNT);
        assertEquals("OPTIONAL", delta(tightened, "requiredness").before());
        assertEquals("REQUIRED", delta(tightened, "requiredness").after());
        assertEquals(ModelDiff.Risk.MEDIUM, tightened.risk());
        assertTrue(tightened.reasons().contains("CONSTRAINT_TIGHTENED"));

        ElementChange loosened = change(ModelDiff.between(required, optional), AMOUNT);
        assertEquals(ModelDiff.Risk.HIGH, loosened.risk());
        assertTrue(loosened.reasons().contains("CONSTRAINT_LOOSENED"));
    }

    @Test
    void detectsUnitFormatAndTypedefReference() {
        ElementChange unit = change(ModelDiff.between(
                number(false, NumberTrait.AMOUNT), number(false, NumberTrait.PERCENT)), AMOUNT);
        assertEquals("AMOUNT", delta(unit, "unit").before());
        assertEquals("PERCENT", delta(unit, "unit").after());

        ElementChange scaleRange = change(ModelDiff.between(numberScale(0, 2), numberScale(1, 2)), AMOUNT);
        assertEquals("0", delta(scaleRange, "minFractionalDigits").before());
        assertEquals("1", delta(scaleRange, "minFractionalDigits").after());
        assertTrue(scaleRange.reasons().contains("SCALE_CHANGED"));

        ElementChange format = change(ModelDiff.between(date("yyyy-MM-dd"), date("dd.MM.yyyy")), "/Invoice/DueOn");
        assertEquals("yyyy-MM-dd", delta(format, "format").before());
        assertEquals("dd.MM.yyyy", delta(format, "format").after());

        ElementChange typeRef = change(ModelDiff.between(typedefField("ShortCode"), typedefField("LongCode")),
                "/Invoice/Code");
        assertEquals("ShortCode", delta(typeRef, "typeDefinition").before());
        assertEquals("LongCode", delta(typeRef, "typeDefinition").after());
        assertTrue(typeRef.reasons().contains("TYPE_CHANGED"));
    }

    @Test
    void enumAdditionLoosensAndRemovalTightens() {
        String two = enumeration("OPEN", "CLOSED");
        String three = enumeration("OPEN", "CLOSED", "ON_HOLD");

        ElementChange added = change(ModelDiff.between(two, three), "/Invoice/Status");
        assertEquals("present", delta(added, "enum.value.ON_HOLD").after());
        assertEquals(ModelDiff.Risk.HIGH, added.risk());
        assertTrue(added.reasons().contains("CONSTRAINT_LOOSENED"));

        ElementChange removed = change(ModelDiff.between(three, two), "/Invoice/Status");
        assertEquals(ModelDiff.Risk.MEDIUM, removed.risk());
        assertTrue(removed.reasons().contains("CONSTRAINT_TIGHTENED"));
    }

    @Test
    void detectsFieldLabelsDescriptionsHelperTextAnnotationsAndFlags() {
        String base = DocumentModelDraft.named("invoice").locale("en_US")
                .rootGroup("Invoice", invoice -> invoice.stringField("Reference", text -> { }))
                .build().toJson();
        String head = DocumentModelDraft.named("invoice").locale("en_US")
                .rootGroup("Invoice", invoice -> invoice.stringField("Reference", text -> text
                        .label("en_US", "Reference").helperText("en_US", "Customer reference")
                        .internalDescription("en_US", "Authoring note")
                        .externalDescription("en_US", "Reference shown on the invoice")
                        .annotation("owner", "billing").global().notPersisted()))
                .build().toJson();

        ElementChange change = change(ModelDiff.between(base, head), "/Invoice/Reference");
        assertEquals(List.of("annotation.owner", "externalDescription.en_US", "global", "helperText.en_US",
                        "internalDescription.en_US", "label.en_US", "transient"),
                change.deltas().stream().map(ModelDiff.Delta::aspect).sorted().toList());
        assertEquals(ModelDiff.Risk.LOW, change.risk());
        assertEquals(List.of("METADATA_CHANGED"), change.reasons());
    }

    @Test
    void treatsOmittedAndExplicitKernelDefaultNumberScaleAsEquivalent() {
        String base = DocumentModelDraft.named("invoice").locale("en_US")
                .rootGroup("Invoice", invoice -> {
                    invoice.numberField("LineCount", number -> number.fractionDigits(0, 0));
                    invoice.stringField("Reference", text -> { });
                }).build().toJson();
        String rewritten = ReferenceRewriter.rewriteReferences(base,
                Map.of("/Invoice/Reference", "/Invoice/Code"));
        String renamed = ModelDocument.read(rewritten).renameField("/Invoice/Reference", "Code").toJson();

        ModelDiff diff = ModelDiff.between(base, renamed);

        assertEquals(List.of("/Invoice/Reference"), diff.changes().stream().map(ElementChange::id).toList());
    }

    @Test
    void treatsEquivalentDecimalConstraintRepresentationsAsEqual() {
        String base = boundedNumber("1.0", "100.00");
        String head = boundedNumber("1.00", "100.0");

        assertTrue(ModelDiff.between(base, head).changes().isEmpty());
    }

    private static String number(boolean required, NumberTrait unit) {
        return DocumentModelDraft.named("invoice").locale("en_US")
                .rootGroup("Invoice", invoice -> invoice.numberField("Amount", amount -> {
                    amount.fractionDigits(2).unit(unit);
                    if (required) {
                        amount.required();
                    }
                })).build().toJson();
    }

    private static String date(String format) {
        return DocumentModelDraft.named("invoice").locale("en_US")
                .rootGroup("Invoice", invoice -> invoice.dateField("DueOn", date -> date.format(format)))
                .build().toJson();
    }

    private static String numberScale(int min, int max) {
        return DocumentModelDraft.named("invoice").locale("en_US")
                .rootGroup("Invoice", invoice -> invoice.numberField("Amount",
                        amount -> amount.fractionDigits(min, max)))
                .build().toJson();
    }

    private static String boundedNumber(String min, String max) {
        return DocumentModelDraft.named("invoice").locale("en_US")
                .rootGroup("Invoice", invoice -> invoice.numberField("Amount",
                        amount -> amount.fractionDigits(2).min(min).max(max)))
                .build().toJson();
    }

    private static String typedefField(String type) {
        return DocumentModelDraft.named("invoice").locale("en_US")
                .typeDef("ShortCode", definition -> definition.string(text -> text.maxLength(8)))
                .typeDef("LongCode", definition -> definition.string(text -> text.maxLength(32)))
                .rootGroup("Invoice", invoice -> invoice.typedefField("Code", type))
                .build().toJson();
    }

    private static String enumeration(String... values) {
        return DocumentModelDraft.named("invoice").locale("en_US")
                .rootGroup("Invoice", invoice -> invoice.enumField("Status", status -> {
                    for (String value : values) {
                        status.value(value);
                    }
                })).build().toJson();
    }

    private static ElementChange change(ModelDiff diff, String id) {
        return diff.changes().stream().filter(candidate -> candidate.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no change for " + id + " in " + diff.changes()));
    }

    private static ModelDiff.Delta delta(ElementChange change, String aspect) {
        return change.deltas().stream().filter(candidate -> candidate.aspect().equals(aspect)).findFirst()
                .orElseThrow(() -> new AssertionError("no '" + aspect + "' delta in " + change.deltas()));
    }
}
