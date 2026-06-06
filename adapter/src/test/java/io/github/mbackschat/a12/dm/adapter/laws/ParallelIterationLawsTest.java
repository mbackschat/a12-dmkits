package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Behavioral law — <b>parallel iteration</b> (KERNEL-SEMANTICS §9). When a rule references two
 * repeatable groups where neither is nested in the other, they are iterated <b>jointly, keyed by a
 * shared Index Field</b> (same name and type on both). It is an <b>outer join</b> over the index
 * values: for an index value present on one side but not the other, the missing side counts as
 * <b>"not specified"</b> for that iteration.
 *
 * <p>Own-domain fixture {@code staffing}: {@code Demand} and {@code Supply}, both repeatable and both
 * keyed by index field {@code Department}, each with a {@code Headcount}. The rule
 * {@code FieldsNotCollectivelyFilled(Demand/Headcount, Supply/Headcount)} fires per shared Department
 * where exactly one side has a headcount — including a Department that exists only in {@code Demand}
 * (its {@code Supply} side is "not specified" via the outer join).
 */
class ParallelIterationLawsTest {

    private static final String STAFFING = RuntimeLaws.model("models/staffing.dm.json");
    private static final String RULE = RuntimeLaws.withRule(STAFFING, "/Staffing", "Mismatch",
            "../Demand/Headcount", "MISMATCH",
            "FieldsNotCollectivelyFilled(Demand/Headcount, Supply/Headcount)");

    @Test
    void aDepartmentMissingFromOneSideFires_butAMatchedDepartmentDoesNot() {
        // Demand: Sales=5, Eng=3 ; Supply: Sales=5 (no Eng row at all)
        RuntimeLaws.Doc doc = RuntimeLaws.doc().group("/Staffing", 1).field("/Staffing/PlanName", "Q3", 1, 1)
                .group("/Staffing/Demand", 1, 1).field("/Staffing/Demand/Department", "Sales", 1, 1, 1)
                .field("/Staffing/Demand/Headcount", "5", 1, 1, 1)
                .group("/Staffing/Demand", 1, 2).field("/Staffing/Demand/Department", "Eng", 1, 2, 1)
                .field("/Staffing/Demand/Headcount", "3", 1, 2, 1)
                .group("/Staffing/Supply", 1, 1).field("/Staffing/Supply/Department", "Sales", 1, 1, 1)
                .field("/Staffing/Supply/Headcount", "5", 1, 1, 1);

        List<RuntimeLaws.Msg> msgs = RuntimeLaws.messages(RULE, doc).stream()
                .filter(m -> m.code().equals("MISMATCH")).toList();

        // fires once — on the Eng iteration (Demand row 2), whose Supply side is "not specified"
        assertEquals(1, msgs.size(), () -> "fires only on the unmatched index value, got: " + msgs);
        assertTrue(msgs.get(0).errorField().contains("[1, 2, 1]"),
                () -> "the message attaches to the Eng Demand row: " + msgs);
    }

    @Test
    void everyDepartmentMatchedOnBothSidesDoesNotFire() {
        // Sales and Eng both present on Demand AND Supply → no shared index has a one-sided headcount
        RuntimeLaws.Doc doc = RuntimeLaws.doc().group("/Staffing", 1).field("/Staffing/PlanName", "Q3", 1, 1)
                .group("/Staffing/Demand", 1, 1).field("/Staffing/Demand/Department", "Sales", 1, 1, 1)
                .field("/Staffing/Demand/Headcount", "5", 1, 1, 1)
                .group("/Staffing/Demand", 1, 2).field("/Staffing/Demand/Department", "Eng", 1, 2, 1)
                .field("/Staffing/Demand/Headcount", "3", 1, 2, 1)
                .group("/Staffing/Supply", 1, 1).field("/Staffing/Supply/Department", "Sales", 1, 1, 1)
                .field("/Staffing/Supply/Headcount", "5", 1, 1, 1)
                .group("/Staffing/Supply", 1, 2).field("/Staffing/Supply/Department", "Eng", 1, 2, 1)
                .field("/Staffing/Supply/Headcount", "3", 1, 2, 1);

        assertEquals(false, RuntimeLaws.fires(RULE, "MISMATCH", doc),
                "every shared Department has both headcounts, so the join finds no mismatch");
    }
}
