package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;

/**
 * A computation A12 admits, assigning a textless enumeration or a category into a text field, reads back as the
 * computation it is (validate.laws.EnumIntoTextComputationLawsTest). The builder's kind gate refused authoring both
 * (2026-10-25 cold review, R14); the reader never consulted it, and this passed before that fix as well, so it locks
 * the read half the authoring fix must not break rather than reproducing a defect.
 */
class EnumIntoTextComputationReadTest {

    private static final String ORDER = DocumentModelDraft.named("order-enum-into-text-read").locale("en_US")
            .rootGroup("Order", o -> o
                    .stringField("Pick", f -> f.maxLength(20))
                    .stringField("Note", f -> f.maxLength(20))
                    .enumField("Status", e -> e.value("OPEN").value("SHIPPED").category("Tier", "High", "Low")))
            .build().toJson();

    @Test
    void aTextlessEnumerationAndACategoryIntoTextReadBack() {
        Map<String, String> computations = Map.of("Pick", "[/Order/Status]", "Note", "[/Order/Status -> Tier]");
        EditSession session = EditSession.read(ORDER);
        computations.forEach((target, operation) -> session.addComputation(new DraftComputation("/Order",
                target + "Comp", "../" + target, null, List.of(new DraftComputation.Alternative(null, operation)),
                List.of()), List.of(Dsl.en("The value."))));
        KernelAdapter adapter = KernelAdapter.of(BoundModel.ofJson(session.toJson()));
        DslRenderer render = new DslRenderer(ConditionLanguage.EN);

        computations.forEach((target, operation) -> {
            ReadComputation read = adapter.readComputation("/Order/" + target + "Comp");
            var converted = assertInstanceOf(ReadComputation.Converted.class, read, () -> target + ": " + read);
            assertEquals(operation, render.render(converted.computation().alternatives().get(0).operation()), target);
        });
    }
}
