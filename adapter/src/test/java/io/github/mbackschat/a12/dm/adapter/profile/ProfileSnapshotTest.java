package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

class ProfileSnapshotTest {

    @Test
    void readsProgramsAndReferenceInverseExactlyOnce() {
        ProfileSnapshot snapshot = ProfileSnapshot.load(calibrationModel());

        assertEquals(1, snapshot.read().rules().size());
        assertEquals(1, snapshot.read().computations().size());
        assertEquals(2, snapshot.referenceParseCount());
        assertEquals(
                java.util.Set.of(
                        "/Calibration/PositiveAmount",
                        "/Calibration/DerivedAmountComputation"),
                snapshot.referrersByEntity().get("/Calibration/Amount"));
        assertEquals(
                java.util.Map.of(
                        "/Calibration/DerivedAmountComputation",
                        "/Calibration/DerivedAmount"),
                snapshot.computationTargets());
        assertEquals(3, snapshot.card().fields().size());
    }

    private static String calibrationModel() {
        String base = DocumentModelDraft.named("profile-snapshot").locale("en_US")
                .rootGroup("Calibration", group -> group
                        .field("Amount", FieldKind.NUMBER)
                        .field("DerivedAmount", FieldKind.NUMBER)
                        .field("Accepted", FieldKind.BOOLEAN))
                .build()
                .toJson();
        EditSession session = EditSession.read(base);
        session.addRule(model -> model.rule(model.group("/Calibration"))
                .name("PositiveAmount")
                .errorField(model.num("/Calibration/Amount").field())
                .when(model.num("/Calibration/Amount").le(0))
                .code("POSITIVE")
                .severity(Severity.ERROR)
                .message(en("Amount must be positive."))
                .build());
        session.addComputation(model -> model.computation(
                        model.num("/Calibration/DerivedAmount"))
                .name("DerivedAmountComputation")
                .calc(model.num("/Calibration/Amount").value())
                .message(en("Derived amount follows amount."))
                .build());
        return session.toJson();
    }
}
