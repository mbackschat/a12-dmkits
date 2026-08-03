package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;

/** Locks the indexed, conversion-independent computation-target read seam. */
class ComputationTargetFieldsTest {

    private static final String AMOUNT_COMPUTATION = "/Calibration/DerivedAmountComputation";
    private static final String TIME_COMPUTATION = "/Calibration/DerivedAtComputation";
    private static final String SUPPRESSION = "MVK_INVALID_COMPARE_DEC_PLACES";

    @Test
    void returnsEveryTargetInModelOrderWithoutParsingConditions() {
        KernelAdapter adapter = KernelAdapter.ofJson(modelWithConvertedAndOpaqueComputations());
        assertInstanceOf(ReadComputation.Converted.class, adapter.readComputation(AMOUNT_COMPUTATION));
        assertInstanceOf(ReadComputation.Opaque.class, adapter.readComputation(TIME_COMPUTATION));
        assertEquals(List.of(SUPPRESSION), adapter.computationErrorCodesToSuppress(TIME_COMPUTATION));
        long parsesBefore = adapter.referenceParseCount();

        Map<String, String> targets = adapter.computationTargetFields();

        assertEquals(List.of(AMOUNT_COMPUTATION, TIME_COMPUTATION), List.copyOf(targets.keySet()));
        assertEquals("/Calibration/DerivedAmount", targets.get(AMOUNT_COMPUTATION));
        assertEquals("/Calibration/DerivedAt", targets.get(TIME_COMPUTATION));
        assertEquals(parsesBefore, adapter.referenceParseCount(),
                "reading calculated fields must not parse computation conditions");
    }

    private static String modelWithConvertedAndOpaqueComputations() {
        String base = DocumentModelDraft.named("calibration").locale("en_US")
                .rootGroup("Calibration", group -> group
                        .field("SourceAmount", FieldKind.NUMBER)
                        .field("DerivedAmount", FieldKind.NUMBER)
                        .dateTimeField("SourceAt", date -> date.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .dateTimeField("DerivedAt", date -> date.format("yyyy-MM-dd'T'HH:mm:ss")))
                .build().toJson();
        EditSession session = EditSession.read(base);
        session.addComputation(model -> model.computation(model.num("/Calibration/DerivedAmount"))
                .name("DerivedAmountComputation")
                .calc(model.num("/Calibration/SourceAmount").value())
                .message(en("Derived amount follows the source amount.")).build());
        session.addComputation(model -> model.computation(model.dateTime("/Calibration/DerivedAt").field())
                .name("DerivedAtComputation")
                .calc(Dsl.addHours(model.dateTime("/Calibration/SourceAt"), 1).expression())
                .errorCodesToSuppress(SUPPRESSION)
                .message(en("Derived time is one hour after the source time.")).build());
        return session.toJson();
    }
}
