package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

class RootOnlyErrorTargetIterationLawsTest {
    @Test
    void directReferenceAdmitsTheSamePlainAndParallelIterations() {
        String json = DocumentModelDraft.named("shipment-reconciliation").locale("en_US")
                .rootGroup("Shipment", root -> root.repeatable(5)
                        .group("Received", rows -> rows.repeatable(10).indexField("LineNo")
                                .stringField("LineNo", f -> { }).numberField("Quantity", f -> { })
                                .stringField("Note", f -> { }))
                        .group("Expected", rows -> rows.repeatable(10).indexField("LineNo")
                                .stringField("LineNo", f -> { }).numberField("Quantity", f -> { })))
                .build().toJson();
        var session = EditSession.read(json);
        var root = session.group("/Shipment");
        var received = session.group("/Shipment/Received");
        var note = received.field("Note");
        Condition rootOnly = Dsl.groupFilled(root.ref());
        List<Condition> conditions = List.of(rootOnly,
                rootOnly.and(received.num("Quantity").gt(0)),
                rootOnly.and(root.num("Expected/Quantity").gt(0)));
        List<List<String>> expected = List.of(List.of(),
                List.of("MVK_ERROR_FIELDGROUP_ITERATION4_INVALID"),
                List.of("MVK_ERROR_FIELDGROUP_ITERATION5_INVALID"));
        for (int i = 0; i < conditions.size(); i++) {
            for (boolean direct : List.of(false, true)) {
                Condition condition = direct ? conditions.get(i).and(Dsl.fieldFilled(note)) : conditions.get(i);
                var rule = received.rule(r -> r.name("Reconcile").code("NOTE").severity(Severity.ERROR).errorField(note)
                        .when(condition).message(Dsl.en("Check the received quantity.")).build());
                var result = new RuleValidator().check(json, rule.draft());
                assertEquals(Verification.KERNEL_CONFIRMED, result.consistencyGate().orElseThrow());
                assertEquals(direct ? List.of() : expected.get(i), result.diagnostics().stream()
                        .map(d -> d.code().orElse("<codeless>")).toList(), rule.render());
                assertEquals(direct || i == 0, result.ok(), rule.render());
            }
        }
    }
}
