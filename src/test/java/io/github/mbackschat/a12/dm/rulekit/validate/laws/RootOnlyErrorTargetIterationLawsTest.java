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

    /**
     * The reconciliation shape both grids measure on — two parallel repeatable rows under a repeatable root.
     *
     * <p>Extracted from the method below 2026-09-14 so the grid added beside it varies the CONDITION against
     * the same carrier, rather than comparing two models that differ in every way nobody thought about.
     */
    private static final String SHIPMENT = DocumentModelDraft.named("shipment-reconciliation").locale("en_US")
            .rootGroup("Shipment", root -> root.repeatable(5)
                    .group("Received", rows -> rows.repeatable(10).indexField("LineNo")
                            .stringField("LineNo", f -> { }).numberField("Quantity", f -> { })
                            .stringField("Note", f -> { }))
                    .group("Expected", rows -> rows.repeatable(10).indexField("LineNo")
                            .stringField("LineNo", f -> { }).numberField("Quantity", f -> { })))
            .build().toJson();

    /**
     * Is the ROOT-group reference load-bearing, or does any non-pinning reference draw the class?
     *
     * <p>The grid below establishes that a rule reaching its error field only through a root-group reference
     * draws a class once the condition also iterates, and that a DIRECT reference to the error field
     * suppresses it. What it cannot separate is whether the ROOT is what matters: its only reaching
     * reference IS the root, so "reached via the root group" and "reached via a group that does not pin the
     * error field's repetition" are the same thing on every row it has. The rule's OWN group is the
     * discriminator — it reaches the error field and pins the repetition — and the two readings disagree
     * there.
     *
     * <p>Two further cells cost nothing beside it: a second field of the SAME group, which decides whether
     * the class keys on the group or on the field that was read, and both iterating reads at once, which is
     * the precedence between the two classes.
     */
    @Test
    void whetherTheRootGroupIsLoadBearingAndWhichClassWinsWhenBothIterationsAppear() {
        var actual = new java.util.LinkedHashMap<String, String>();
        actual.put("root reference, no iterating read", verdict("GroupFilled(/Shipment)"));
        actual.put("root reference, read in the error field's OWN group",
                verdict("GroupFilled(/Shipment) And [Quantity] > 0"));
        actual.put("root reference, read of the OTHER field in that group",
                verdict("GroupFilled(/Shipment) And FieldFilled(LineNo)"));
        actual.put("root reference, read in a PARALLEL repeatable group",
                verdict("GroupFilled(/Shipment) And [/Shipment/Expected/Quantity] > 0"));
        actual.put("root reference, BOTH iterating reads | own first",
                verdict("GroupFilled(/Shipment) And [Quantity] > 0 And [/Shipment/Expected/Quantity] > 0"));
        actual.put("root reference, BOTH iterating reads | PARALLEL first",
                verdict("GroupFilled(/Shipment) And [/Shipment/Expected/Quantity] > 0 And [Quantity] > 0"));
        actual.put("the RULE'S OWN group instead of the root, with an iterating read",
                verdict("GroupFilled(/Shipment/Received) And [Quantity] > 0"));
        actual.put("direct reference to the error field beside the root reference and a read",
                verdict("FieldFilled(Note) And GroupFilled(/Shipment) And [Quantity] > 0"));

        var expected = new java.util.LinkedHashMap<String, String>();
        expected.put("root reference, no iterating read", KernelLaws.ADMITTED);
        expected.put("root reference, read in the error field's OWN group",
                "MVK_ERROR_FIELDGROUP_ITERATION4_INVALID");
        expected.put("root reference, read of the OTHER field in that group",
                "MVK_ERROR_FIELDGROUP_ITERATION4_INVALID");
        expected.put("root reference, read in a PARALLEL repeatable group",
                "MVK_ERROR_FIELDGROUP_ITERATION5_INVALID");
        // Both orders, because one order cannot tell "the parallel class wins" from "the LATER read wins" —
        // the confound that shipped a wrong mechanism in [KF340] two days ago.
        expected.put("root reference, BOTH iterating reads | own first",
                "MVK_ERROR_FIELDGROUP_ITERATION5_INVALID");
        expected.put("root reference, BOTH iterating reads | PARALLEL first",
                "MVK_ERROR_FIELDGROUP_ITERATION5_INVALID");
        expected.put("the RULE'S OWN group instead of the root, with an iterating read", KernelLaws.ADMITTED);
        expected.put("direct reference to the error field beside the root reference and a read",
                KernelLaws.ADMITTED);
        assertEquals(expected, actual, "root-only error-target iteration");
    }

    /** The kernel's verdict for one condition on {@link #SHIPMENT}, rule in `Received`, reporting on `Note`. */
    private static String verdict(String condition) {
        return KernelLaws.verdictModel(SHIPMENT, "/Shipment/Received", "../Note", condition);
    }
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
