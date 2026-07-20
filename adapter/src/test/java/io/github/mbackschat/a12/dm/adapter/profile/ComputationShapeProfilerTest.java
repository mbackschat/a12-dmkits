package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoolLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.ToleranceBand;

class ComputationShapeProfilerTest {

    @Test
    void profilesConvertedTablesAndEveryComputedFieldKindIncludingOpaqueTargets() {
        var snapshot = ProfileSnapshot.load(calibrationModel());
        var shape = ComputationShapeProfiler.profile(snapshot);

        assertEquals(9, snapshot.read().computations().size());
        assertEquals(8, snapshot.read().convertedComputations());
        assertEquals(
                java.util.Map.of(
                        "BOOLEAN", 1L,
                        "DATE", 1L,
                        "DATE_TIME", 1L,
                        "NUMBER", 5L,
                        "STRING", 1L),
                shape.computedFieldKinds().counts());

        assertEquals(8, shape.tables().alternatives().count());
        assertEquals(1, shape.tables().alternatives().min());
        assertEquals(2, shape.tables().alternatives().max());
        assertEquals(8, shape.tables().preconditions().count());
        assertEquals(0, shape.tables().preconditions().min());
        assertEquals(3, shape.tables().preconditions().max());
        assertEquals(
                java.util.Map.of("TR1", 1L, "TR5", 1L),
                shape.tables().toleranceBands().counts());

        assertEquals(
                java.util.Map.of(
                        "AGGREGATE", 2L,
                        "ARITHMETIC", 1L,
                        "CONSTANT", 1L,
                        "COPY", 2L,
                        "DATE", 1L,
                        "STRING", 1L),
                shape.archetypes().counts());
    }

    @Test
    void comparesTheComputedScaleOnlyWithActualAggregateOperandCells() {
        var shape = ComputationShapeProfiler.profile(ProfileSnapshot.load(calibrationModel()));

        assertEquals(
                java.util.Map.of("MATCHED", 1L, "MISMATCHED", 1L),
                shape.aggregateScaleMatch().counts());
    }

    private static String calibrationModel() {
        String base = DocumentModelDraft.named("profile-computations").locale("en_US")
                .rootGroup("Calibration", root -> root
                        .numberField("BaseMeasure", field -> field.fractionDigits(2))
                        .numberField("CopyResult", field -> field.fractionDigits(2))
                        .numberField("ArithmeticResult", field -> field.fractionDigits(2))
                        .numberField("MatchedTotal", field -> field.fractionDigits(2))
                        .numberField("MismatchedTotal", field -> field.fractionDigits(0))
                        .numberField("TieredResult", field -> field.fractionDigits(2))
                        .dateField("CalculatedDate", field -> field.format("yyyy-MM-dd"))
                        .field("Label", FieldKind.STRING)
                        .field("Enabled", FieldKind.BOOLEAN)
                        .dateTimeField(
                                "SourceInstant",
                                field -> field.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .dateTimeField(
                                "OpaqueInstant",
                                field -> field.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .group("Rows", rows -> rows
                                .repeatable(100)
                                .numberField("Amount", field -> field.fractionDigits(2))
                                .numberField("Rank", field -> field.fractionDigits(0))))
                .build()
                .toJson();
        EditSession session = EditSession.read(base);

        session.addComputation(model -> model.computation(model.num("/Calibration/CopyResult"))
                .name("ComputeCopy")
                .calc(model.num("/Calibration/BaseMeasure").value())
                .message(en("The copy follows the base measure."))
                .build());
        session.addComputation(model -> model.computation(model.num("/Calibration/ArithmeticResult"))
                .name("ComputeArithmetic")
                .calc(model.num("/Calibration/BaseMeasure")
                        .asNumber()
                        .plus(Dsl.number("1.00"))
                        .expression())
                .message(en("The arithmetic result adds an adjustment."))
                .build());
        session.addComputation(model -> model.computation(model.num("/Calibration/MatchedTotal"))
                .name("ComputeMatchedTotal")
                .calc(Dsl.sum(model.flatten("/Calibration/Rows*/Amount")).expression())
                .message(en("The matched total sums row amounts."))
                .build());
        session.addComputation(model -> model.computation(model.num("/Calibration/MismatchedTotal"))
                .name("ComputeMismatchedTotal")
                .calc(Dsl.sum(model.flatten("/Calibration/Rows*/Amount")
                        .having(model.num("/Calibration/Rows/Rank").gt(0))).expression())
                .errorCodesToSuppress("MVK_INVALID_COMPARE_DEC_PLACES")
                .message(en("The mismatched total sums selected row amounts."))
                .build());
        session.addComputation(model -> model.computation(model.num("/Calibration/TieredResult"))
                .name("ComputeTiered")
                .commonPrecondition(model.num("/Calibration/BaseMeasure").filled())
                .when(model.num("/Calibration/BaseMeasure").gt(0))
                .calc(model.num("/Calibration/BaseMeasure").value(), ToleranceBand.TR1)
                .when(model.num("/Calibration/BaseMeasure").le(0))
                .calc(Dsl.number("0.00").expression(), ToleranceBand.TR5)
                .message(en("The tiered result follows the selected branch."))
                .build());
        session.addComputation(model -> model.computation(model.date("/Calibration/CalculatedDate").field())
                .name("ComputeDate")
                .calc(Dsl.today().expression())
                .message(en("The calculated date is today."))
                .build());
        session.addComputation(model -> model.computation(model.field("/Calibration/Label"))
                .name("ComputeLabel")
                .calc(new StringLiteral("calibrated"))
                .message(en("The label marks calibrated output."))
                .build());
        session.addComputation(model -> model.computation(model.bool("/Calibration/Enabled").field())
                .name("ComputeEnabled")
                .calc(new BoolLiteral(true))
                .message(en("The output is enabled."))
                .build());
        session.addComputation(model -> model.computation(model.dateTime("/Calibration/OpaqueInstant").field())
                .name("ComputeOpaqueInstant")
                .calc(Dsl.addHours(model.dateTime("/Calibration/SourceInstant"), 1).expression())
                .message(en("The instant follows the source by one hour."))
                .build());
        return session.toJson();
    }
}
