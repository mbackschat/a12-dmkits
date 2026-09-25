package io.github.mbackschat.a12.dm.adapter.review;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ChangeKind;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementChange;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementKind;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.RoundingMode;
import io.github.mbackschat.a12.dm.rulekit.dsl.ToleranceBand;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;

/** Computations are first-class structural-diff elements. */
class ModelDiffComputationTest {

    private static final String PRICE = "/Quote/Price";
    private static final String QUANTITY = "/Quote/Quantity";
    private static final String TOTAL = "/Quote/Total";
    private static final String BACKUP_TOTAL = "/Quote/BackupTotal";
    private static final String COMPUTATION = "/Quote/PriceTotalComp";

    private static String emptyModel() {
        return DocumentModelDraft.named("quote-pricing").locale("en_US")
                .rootGroup("Quote", quote -> quote
                        .numberField("Price", number -> number.fractionDigits(2))
                        .numberField("Quantity", number -> number.fractionDigits(0))
                        .numberField("Total", number -> number.fractionDigits(2))
                        .numberField("BackupTotal", number -> number.fractionDigits(2)))
                .build().toJson();
    }

    private static String withComputation(Function<BoundModel, Computation> factory) {
        EditSession session = EditSession.read(emptyModel());
        session.addComputation(factory);
        return session.toJson();
    }

    private static Computation product(BoundModel model) {
        return computation(model, TOTAL, "Price times quantity.", builder -> builder
                .calc(model.num(PRICE).asNumber().times(model.num(QUANTITY).asNumber()).expression()));
    }

    private static Computation sum(BoundModel model) {
        return computation(model, TOTAL, "Price plus quantity.", builder -> builder
                .calc(model.num(PRICE).asNumber().plus(model.num(QUANTITY).asNumber()).expression()));
    }

    private static Computation computation(BoundModel model, String target, String message,
            Function<Computation.Builder, Computation.Builder> configure) {
        var builder = model.computation(model.num(target)).name("PriceTotalComp");
        return configure.apply(builder).message(en(message)).build();
    }

    @Test
    void detectsAddedAndRemovedComputation() {
        String computed = withComputation(ModelDiffComputationTest::product);

        ElementChange added = change(ModelDiff.between(emptyModel(), computed));
        assertEquals(ElementKind.COMPUTATION, added.element());
        assertEquals(ChangeKind.ADDED, added.change());
        assertEquals(ModelDiff.Risk.MEDIUM, added.risk());
        assertTrue(added.reasons().contains("COMPUTATION_CHANGED"), () -> "reasons: " + added.reasons());

        ElementChange removed = change(ModelDiff.between(computed, emptyModel()));
        assertEquals(ChangeKind.REMOVED, removed.change());
        assertEquals(ModelDiff.Risk.MEDIUM, removed.risk());
        assertTrue(removed.reasons().contains("COMPUTATION_CHANGED"), () -> "reasons: " + removed.reasons());
    }

    @Test
    void detectsOperationChange() {
        ElementChange changed = change(ModelDiff.between(
                withComputation(ModelDiffComputationTest::product),
                withComputation(ModelDiffComputationTest::sum)));

        var operation = delta(changed, "alternative[0].operation");
        assertTrue(operation.before().contains("*") && operation.after().contains("+"),
                () -> "operation delta: " + operation);
        assertEquals(ModelDiff.Risk.MEDIUM, changed.risk());
        assertTrue(changed.reasons().contains("COMPUTATION_CHANGED"), () -> "reasons: " + changed.reasons());
    }

    @Test
    void detectsComputedTargetChange() {
        String base = withComputation(ModelDiffComputationTest::product);
        String head = withComputation(model -> computation(model, BACKUP_TOTAL, "Backup total.", builder -> builder
                .calc(model.num(PRICE).asNumber().times(model.num(QUANTITY).asNumber()).expression())));

        assertEquals(TOTAL, delta(change(ModelDiff.between(base, head)), "target").before());
        assertEquals(BACKUP_TOTAL, delta(change(ModelDiff.between(base, head)), "target").after());
    }

    @Test
    void detectsCommonPreconditionChange() {
        String base = withComputation(model -> computation(model, TOTAL, "Total.", builder -> builder
                .commonPrecondition(model.num(QUANTITY).gt(0))
                .when(model.num(QUANTITY).gt(100)).calc(model.num(PRICE).value())
                .when(model.num(QUANTITY).le(100)).calc(model.num(BACKUP_TOTAL).value())));
        String head = withComputation(model -> computation(model, TOTAL, "Total.", builder -> builder
                .commonPrecondition(model.num(QUANTITY).gt(1))
                .when(model.num(QUANTITY).gt(100)).calc(model.num(PRICE).value())
                .when(model.num(QUANTITY).le(100)).calc(model.num(BACKUP_TOTAL).value())));

        var precondition = delta(change(ModelDiff.between(base, head)), "commonPrecondition");
        assertTrue(precondition.before().contains("0") && precondition.after().contains("1"),
                () -> "precondition delta: " + precondition);
    }

    @Test
    void detectsOrderedAlternativeChange() {
        String base = withComputation(model -> tiered(model, 10));
        String head = withComputation(model -> tiered(model, 20));

        var precondition = delta(change(ModelDiff.between(base, head)), "alternative[0].precondition");
        assertTrue(precondition.before().contains("10") && precondition.after().contains("20"),
                () -> "alternative delta: " + precondition);
    }

    private static Computation tiered(BoundModel model, int threshold) {
        return model.computation(model.num(TOTAL)).name("PriceTotalComp")
                .when(model.num(QUANTITY).gt(threshold)).calc(model.num(PRICE).value())
                .when(model.num(QUANTITY).le(threshold)).calc(model.num(BACKUP_TOTAL).value())
                .message(en("Tiered total.")).build();
    }

    @Test
    void detectsMessageChangeAsLowRisk() {
        String base = withComputation(model -> computation(model, TOTAL, "Old explanation.", builder -> builder
                .calc(model.num(PRICE).value())));
        String head = withComputation(model -> computation(model, TOTAL, "New explanation.", builder -> builder
                .calc(model.num(PRICE).value())));

        ElementChange changed = change(ModelDiff.between(base, head));
        assertEquals("Old explanation.", delta(changed, "message.en_US").before());
        assertEquals("New explanation.", delta(changed, "message.en_US").after());
        assertEquals(ModelDiff.Risk.LOW, changed.risk());
        assertEquals(List.of("MESSAGE_CHANGED"), changed.reasons());
    }

    @Test
    void repeatedLocalizedMessageChangesCarryOneReasonCode() {
        ElementChange changed = change(ModelDiff.between(
                bilingualComputation("Old English.", "Altes Deutsch."),
                bilingualComputation("New English.", "Neues Deutsch.")));

        assertEquals(List.of("MESSAGE_CHANGED"), changed.reasons());
    }

    private static String bilingualComputation(String english, String german) {
        String base = DocumentModelDraft.named("quote-pricing").locale("en_US").locale("de_DE")
                .rootGroup("Quote", quote -> quote
                        .numberField("Price", number -> number.fractionDigits(2))
                        .numberField("Quantity", number -> number.fractionDigits(0))
                        .numberField("Total", number -> number.fractionDigits(2))
                        .numberField("BackupTotal", number -> number.fractionDigits(2)))
                .build().toJson();
        EditSession session = EditSession.read(base);
        session.addComputation(model -> model.computation(model.num(TOTAL)).name("PriceTotalComp")
                .calc(model.num(PRICE).value()).message(en(english), de(german)).build());
        return session.toJson();
    }

    @Test
    void detectsToleranceAndSuppressionChanges() {
        String base = withComputation(model -> computation(model, TOTAL, "Total.", builder -> builder
                .calc(model.num(PRICE).value(), ToleranceBand.TR1)));
        String head = withComputation(model -> computation(model, TOTAL, "Total.", builder -> builder
                .calc(model.num(PRICE).value(), ToleranceBand.TR5)
                .errorCodesToSuppress("MVK_INVALID_COMPARE_DEC_PLACES")));

        ElementChange changed = change(ModelDiff.between(base, head));
        assertEquals(ToleranceBand.TR1.wire(), delta(changed, "alternative[0].tolerance").before());
        assertEquals(ToleranceBand.TR5.wire(), delta(changed, "alternative[0].tolerance").after());
        assertEquals("MVK_INVALID_COMPARE_DEC_PLACES", delta(changed, "errorCodesToSuppress").after());
    }

    /** A mode-only change is a semantic change, and an Exact side carries no aspect, as A12 stores none. */
    @Test
    void detectsARoundingModeChange() {
        String up = withComputation(model -> computation(model, TOTAL, "Total.", builder -> builder
                .calc(model.num(PRICE).value(), ToleranceBand.TR1).roundingMode(RoundingMode.ROUND_UP)));
        String down = withComputation(model -> computation(model, TOTAL, "Total.", builder -> builder
                .calc(model.num(PRICE).value(), ToleranceBand.TR1).roundingMode(RoundingMode.ROUND_DOWN)));
        String exact = withComputation(model -> computation(model, TOTAL, "Total.", builder -> builder
                .calc(model.num(PRICE).value(), ToleranceBand.TR1)));

        ModelDiff.Delta changed = delta(change(ModelDiff.between(up, down)), "roundingMode");
        assertEquals("RoundUp", changed.before());
        assertEquals("RoundDown", changed.after());
        ModelDiff.Delta removed = delta(change(ModelDiff.between(up, exact)), "roundingMode");
        assertEquals("RoundUp", removed.before());
        assertEquals(null, removed.after());
    }

    @Test
    void opaqueComputationsUseTheirVerbatimTextAsTheFingerprint() {
        var first = ComputationDiffProjector.aspects(
                new ReadComputation.Opaque("[Price] * [Quantity]", "x"),
                List.of("MVK_INVALID_COMPARE_DEC_PLACES"), RoundingMode.ROUND_UP);
        var second = ComputationDiffProjector.aspects(new ReadComputation.Opaque("[Price] + [Quantity]", "y"));

        assertEquals("[Price] * [Quantity]", first.get("opaque"));
        assertEquals("MVK_INVALID_COMPARE_DEC_PLACES", first.get("errorCodesToSuppress"));
        // the element's own members ride beside the verbatim formula
        assertEquals("RoundUp", first.get("roundingMode"));
        assertEquals("[Price] + [Quantity]", second.get("opaque"));
        assertEquals(null, second.get("roundingMode"), "Exact is never an aspect");
    }

    @Test
    void computationReadersJoinReferenceRewritingRefactors() {
        String base = withComputation(ModelDiffComputationTest::product);
        String renamedBase = DocumentModelDraft.named("quote-pricing").locale("en_US")
                .rootGroup("Quote", quote -> quote
                        .numberField("UnitPrice", number -> number.fractionDigits(2))
                        .numberField("Quantity", number -> number.fractionDigits(0))
                        .numberField("Total", number -> number.fractionDigits(2))
                        .numberField("BackupTotal", number -> number.fractionDigits(2)))
                .build().toJson();
        EditSession renamed = EditSession.read(renamedBase);
        renamed.addComputation(model -> computation(model, TOTAL, "Price times quantity.", builder -> builder
                .calc(model.num("/Quote/UnitPrice").asNumber()
                        .times(model.num(QUANTITY).asNumber()).expression())));

        ModelDiff diff = ModelDiff.between(base, renamed.toJson());
        assertEquals(1, diff.refactors().size(), () -> "refactors: " + diff.refactors());
        assertEquals(List.of(COMPUTATION), diff.refactors().get(0).referrers());
    }

    @Test
    void identicalAndReformattedComputationsRemainEmpty() {
        String model = withComputation(ModelDiffComputationTest::product);
        assertTrue(ModelDiff.between(model, model).changes().isEmpty());
        String reformatted = io.github.mbackschat.a12.dm.modelkit.ModelDocument.read(model).toJson();
        assertTrue(ModelDiff.between(model, reformatted).changes().isEmpty());
    }

    @Test
    void headlessLibraryReturnsTypedChanges() {
        String beforeJson = withComputation(ModelDiffComputationTest::product);
        String afterJson = withComputation(ModelDiffComputationTest::sum);

        // tag::model-diff[]
        ModelDiff diff = ModelDiff.between(beforeJson, afterJson);

        for (ModelDiff.ElementChange change : diff.changes()) {
            System.out.println(change.element() + " " + change.id() + " " + change.risk());
            for (ModelDiff.Delta delta : change.deltas()) {
                String typedAfter = delta.afterValue().map(value -> switch (value) {
                    case ModelDiff.TextValue text -> text.value();
                    case ModelDiff.BooleanValue bool -> Boolean.toString(bool.value());
                    case ModelDiff.IntegerValue integer -> Integer.toString(integer.value());
                    case ModelDiff.DecimalValue decimal -> decimal.value().toPlainString();
                    case ModelDiff.StringListValue strings -> String.join(", ", strings.value());
                }).orElse("<removed>");
                System.out.println(delta.aspect() + ": " + delta.before() + " -> " + typedAfter);
            }
        }
        // end::model-diff[]

        assertEquals(ElementKind.COMPUTATION, change(diff).element());
    }

    private static ElementChange change(ModelDiff diff) {
        return diff.changes().stream()
                .filter(candidate -> candidate.id().equals(COMPUTATION))
                .findFirst().orElseThrow(() -> new AssertionError("no computation change in " + diff.changes()));
    }

    private static ModelDiff.Delta delta(ElementChange change, String aspect) {
        return change.deltas().stream().filter(candidate -> candidate.aspect().equals(aspect)).findFirst()
                .orElseThrow(() -> new AssertionError("no '" + aspect + "' delta in " + change.deltas()));
    }
}
