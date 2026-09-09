package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

class AncestorGroupRenderingTest {
    @Test
    void immediateAndDeeperAncestorOperandsUseKernelAdmittedParentNavigation() {
        String json = DocumentModelDraft.named("shipping-labels").locale("en_US")
                .rootGroup("Shipment", root -> root.group("Parcels", parcels -> parcels.repeatable(3)
                        .group("Labels", labels -> labels.repeatable(3).stringField("Text", f -> { }))))
                .build().toJson();
        var session = EditSession.read(json);
        var labels = session.group("/Shipment/Parcels/Labels");
        var text = labels.field("Text");
        var ancestors = List.of(session.group("/Shipment/Parcels").ref(), session.group("/Shipment").ref());
        var expected = List.of("GroupFilled(..) And FieldFilled(Text)",
                "GroupFilled(../..) And FieldFilled(Text)");
        for (int i = 0; i < ancestors.size(); i++) {
            Condition condition = Dsl.groupFilled(ancestors.get(i)).and(Dsl.fieldFilled(text));
            var rule = labels.rule(r -> r.name("LabelContext").code("LABEL").severity(Severity.ERROR)
                    .errorField(text).when(condition).message(Dsl.en("Check this label.")).build());
            assertEquals(expected.get(i), rule.render());
            var result = new RuleValidator().check(json, rule.draft());
            assertEquals(Verification.KERNEL_CONFIRMED, result.consistencyGate().orElseThrow());
            assertTrue(result.ok(), () -> rule.render() + ": " + result.diagnostics());
        }
    }
}
