package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

class ReferenceGraphTest {

    @Test
    void derivesFieldFanOutAndAnIterativeComputationCascadeFromTheSharedInverse() {
        var graph = ReferenceGraph.of(ProfileSnapshot.load(calibrationModel()));

        var wiring = graph.wiringProfile();
        assertEquals(java.util.Map.of("ANY", 4L), wiring.referencedFields().counts());
        assertEquals(4, wiring.fieldFanOut().count());
        assertEquals(1, wiring.fieldFanOut().min());
        assertEquals(2, wiring.fieldFanOut().max());

        var dependencies = graph.dependencyProfile();
        assertEquals(3, dependencies.maxCascadeDepth());
        assertEquals(2, dependencies.feedingComputations());
    }

    private static String calibrationModel() {
        String base = DocumentModelDraft.named("profile-graph").locale("en_US")
                .rootGroup("Calibration", group -> group
                        .field("Input", FieldKind.NUMBER)
                        .field("StageOne", FieldKind.NUMBER)
                        .field("StageTwo", FieldKind.NUMBER)
                        .field("Output", FieldKind.NUMBER))
                .build()
                .toJson();
        EditSession session = EditSession.read(base);
        session.addRule(model -> model.rule(model.group("/Calibration"))
                .name("InputPresent")
                .errorField(model.num("/Calibration/Input").field())
                .when(model.num("/Calibration/Input").notFilled())
                .code("INPUT")
                .severity(Severity.ERROR)
                .message(en("Input is required."))
                .build());
        session.addComputation(model -> model.computation(model.num("/Calibration/StageOne"))
                .name("ComputeStageOne")
                .calc(model.num("/Calibration/Input").value())
                .message(en("Stage one follows input."))
                .build());
        session.addComputation(model -> model.computation(model.num("/Calibration/StageTwo"))
                .name("ComputeStageTwo")
                .calc(model.num("/Calibration/StageOne").value())
                .message(en("Stage two follows stage one."))
                .build());
        session.addComputation(model -> model.computation(model.num("/Calibration/Output"))
                .name("ComputeOutput")
                .calc(model.num("/Calibration/StageTwo").value())
                .message(en("Output follows stage two."))
                .build());
        return session.toJson();
    }
}
