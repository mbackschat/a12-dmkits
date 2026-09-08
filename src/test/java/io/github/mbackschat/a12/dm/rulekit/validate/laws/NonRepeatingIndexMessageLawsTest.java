package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

class NonRepeatingIndexMessageLawsTest {
    @Test
    void oneEnumValueWithSValueAdmitsBothSuffixesAndEachRemovedConjunctRefuses() {
        for (boolean secondValue : List.of(false, true)) {
            for (boolean sValue : List.of(true, false)) {
                String json = model(secondValue, sValue);
                var approval = EditSession.read(json).group("/Shipment/Approval");
                var status = approval.field("Status");
                var note = approval.field("Note");
                var condition = Dsl.fieldFilled(status).and(Dsl.fieldNotFilled(note));
                for (String message : List.of("Approval needs a note.",
                        "Approval $index(Status).value$ needs a note.",
                        "Approval $index(Status)->Category$ needs a note.")) {
                    var rule = approval.rule(r -> r.name("ApprovalNote").code("NOTE").severity(Severity.ERROR)
                            .errorField(note).when(condition).message(Dsl.en(message)).build());
                    var result = new RuleValidator().check(json, rule.draft());
                    assertEquals(Verification.KERNEL_CONFIRMED, result.consistencyGate().orElseThrow());
                    String row = "secondValue=" + secondValue + ", sValue=" + sValue + ": " + message;
                    if (!message.contains("$") || (!secondValue && sValue)) {
                        assertTrue(result.ok(), () -> row + ": " + result.diagnostics());
                    } else {
                        assertEquals(List.of("INVALID_INDEX_FIELD_NONREP_ENUM"), result.diagnostics().stream()
                                .map(d -> d.code().orElse("<codeless>")).toList(), row);
                    }
                }
            }
        }
    }

    private static String model(boolean secondValue, boolean sValue) {
        return DocumentModelDraft.named("shipment-approval").locale("en_US")
                .rootGroup("Shipment", shipment -> shipment.group("Approval", approval -> approval
                        .indexField("Status")
                        .enumField("Status", status -> {
                            status.value("APPROVED");
                            if (secondValue) status.value("PENDING");
                            status.category("Category", secondValue
                                    ? new String[] {"FINAL", "OPEN"} : new String[] {"FINAL"});
                            if (sValue) status.sValue("APPROVED");
                        })
                        .stringField("Note", note -> { })))
                .build().toJson();
    }
}
