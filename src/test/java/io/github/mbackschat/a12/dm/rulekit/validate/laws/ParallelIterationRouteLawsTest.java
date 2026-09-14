package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.EntityPath;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §11 — the REST of the parallel-iteration route, past the three cells
 * {@link RuleOperandScopeLawsTest} pinned.
 *
 * <p>That class measured a sibling repeatable operand by its index-field declaration: same name admitted,
 * another name {@code MVK_ERROR_FIELD_INVALID_INDEX}, no index field {@code MVK_NO_WILDCARD}. Its fixture
 * keys the ERROR FIELD's own group, so the three rows vary the operand's declaration against a constant
 * target. This class varies the other end, and adds the two cells the source chain
 * ({@code CheckEntityUtils.checkParallelIteration} → {@code getIndexGroup}) reaches before either of them.
 *
 * <p>Own domain (e-commerce fulfilment), deliberately not the motor-claims fixture those rows run on — the
 * variables here are the TARGET's index chain and the operand's ANCESTOR chain, neither of which that model
 * can vary.
 */
class ParallelIterationRouteLawsTest {

    private static final String NO_WILDCARD = "MVK_NO_WILDCARD";

    /**
     * One root scope and four repeatable branches under it, each differing from the others in exactly one
     * thing.
     *
     * <ul>
     * <li>{@code Shipments} is keyed and carries an UNKEYED repeatable {@code Parcels} beneath it, so a
     * second level below an admitted one can be measured without changing the first.</li>
     * <li>{@code Invoices} repeats the key name, so a target in it iterates jointly with {@code Shipments}.
     * </li>
     * <li>{@code Returns} declares no index at all and is the target end of the no-index row — it carries
     * the same key FIELD as its siblings and merely does not DECLARE it, so that row varies one thing.</li>
     * <li>{@code Credits} is keyed with the SAME name and carries a keyed {@code Lines} beneath it, which is
     * the only shape that reaches the multiple-index gate from this route.</li>
     * </ul>
     */
    private static final String FULFILMENT = DocumentModelDraft.named("fulfilment").locale("en_US")
            .rootGroup("Fulfilment", f -> f
                    .stringField("OrderRef", s -> { })
                    .numberField("OrderTotal", n -> n.fractionDigits(0))
                    .group("Shipments", s -> s.repeatable(20).indexField("ShipmentKey")
                            .field("ShipmentKey", FieldKind.STRING)
                            .numberField("ShipmentCost", n -> n.fractionDigits(0))
                            .group("Parcels", p -> p.repeatable(20)
                                    .numberField("ParcelWeight", n -> n.fractionDigits(0))))
                    .group("Invoices", i -> i.repeatable(20).indexField("ShipmentKey")
                            .field("ShipmentKey", FieldKind.STRING)
                            .numberField("InvoiceAmount", n -> n.fractionDigits(0)))
                    .group("Returns", r -> r.repeatable(20)
                            .field("ShipmentKey", FieldKind.STRING)
                            .numberField("ReturnAmount", n -> n.fractionDigits(0)))
                    .group("Credits", c -> c.repeatable(20).indexField("ShipmentKey")
                            .field("ShipmentKey", FieldKind.STRING)
                            .numberField("CreditAmount", n -> n.fractionDigits(0))
                            .group("Lines", l -> l.repeatable(20).indexField("LineKey")
                                    .field("LineKey", FieldKind.STRING)
                                    .numberField("LineAmount", n -> n.fractionDigits(0))))
                    .group("Refunds", r -> r.repeatable(20).indexField("RefundKey")
                            .field("RefundKey", FieldKind.STRING)
                            .numberField("RefundAmount", n -> n.fractionDigits(0)))
                    .group("Adjustments", a -> a.repeatable(20).indexField("ShipmentKey")
                            .field("ShipmentKey", FieldKind.NUMBER)
                            .numberField("AdjustmentAmount", n -> n.fractionDigits(0))))
            .build().toJson();

    /**
     * The verdict on one rule declared in the error field's own group, whose condition reads that field and
     * the probe operand. The declaring group is never a second variable; that axis is
     * {@link ComputationDeclaringGroupLawsTest}'s.
     */
    private static String verdict(String errorField, String operandPath) {
        return verdictFrom(errorField.substring(0, errorField.lastIndexOf('/')), errorField, operandPath);
    }

    /** The same rule declared in an arbitrary {@code group}, for the one table that varies that axis. */
    private static String verdictFrom(String group, String errorField, String operandPath) {
        String errorRel = EntityPath.of(group + "/ProbeRule").relativize(EntityPath.of(errorField));
        String condition = "FieldFilled(" + errorField + ") And [" + operandPath + "] > 0";
        return KernelLaws.verdictModel(FULFILMENT, group, errorRel, condition);
    }

    /**
     * Which end of the pair the index declaration is demanded of. The sibling rows pinned the OPERAND's end;
     * this pins the TARGET's, and the two admitted controls are what keep the refusal from reading as "this
     * model shape is simply illegal".
     */
    @Test
    void theTargetPathNeedsAnIndexFieldOfItsOwn() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("target in an UNKEYED group, keyed operand",
                verdict("/Fulfilment/Returns/ReturnAmount", "/Fulfilment/Shipments/ShipmentCost"));
        actual.put("target in a keyed group, same-named keyed operand — control",
                verdict("/Fulfilment/Invoices/InvoiceAmount", "/Fulfilment/Shipments/ShipmentCost"));
        actual.put("target in an UNKEYED group, form-level operand — control",
                verdict("/Fulfilment/Returns/ReturnAmount", "/Fulfilment/OrderTotal"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("target in an UNKEYED group, keyed operand", "MVK_ERROR_FIELD_NO_INDEX");
        expected.put("target in a keyed group, same-named keyed operand — control", KernelLaws.ADMITTED);
        expected.put("target in an UNKEYED group, form-level operand — control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the index demand falls on both ends of the parallel pair");
    }

    /**
     * That the route is walked per LEVEL rather than once per reference: an operand crossing an admitted
     * parallel level and then an unkeyed one below it is refused at the lower level, with the class that
     * level's own declaration picks.
     */
    @Test
    void everyCrossedLevelIsAskedSeparately() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("keyed parallel level, UNKEYED level below it",
                verdict("/Fulfilment/Invoices/InvoiceAmount", "/Fulfilment/Shipments/Parcels/ParcelWeight"));
        actual.put("keyed parallel level alone — control",
                verdict("/Fulfilment/Invoices/InvoiceAmount", "/Fulfilment/Shipments/ShipmentCost"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("keyed parallel level, UNKEYED level below it", NO_WILDCARD);
        expected.put("keyed parallel level alone — control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "each crossed level is judged on its own declaration");
    }

    /**
     * Two keyed groups on the OPERAND's own ancestor chain, which A12 answers with a model-structure class
     * instead of an error-field one. It decides whether a checker may report the error-field classes on such
     * an operand at all, since the route never reaches them.
     *
     * <p>The second row is the one that pins the WALK rather than the gate, and it is where a prediction from
     * the gate chain alone went wrong: the same operand into an unkeyed target draws
     * {@code MVK_ERROR_FIELD_NO_INDEX}, because the OUTER parallel level is judged first and is already
     * refused there — the inner keyed level is never reached, so the multiple-index gate never runs. Per-level
     * order outranks the order of the gates inside one level.
     */
    @Test
    void twoKeyedGroupsOnTheOperandsChainAnswerWhereTheWalkReachesThem() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("keyed level under a keyed parallel level",
                verdict("/Fulfilment/Invoices/InvoiceAmount", "/Fulfilment/Credits/Lines/LineAmount"));
        actual.put("the same operand into an UNKEYED target",
                verdict("/Fulfilment/Returns/ReturnAmount", "/Fulfilment/Credits/Lines/LineAmount"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("keyed level under a keyed parallel level", "MVK_MULTIPLE_INDEX_FIELDS");
        expected.put("the same operand into an UNKEYED target", "MVK_ERROR_FIELD_NO_INDEX");
        assertEquals(expected, actual, "the outermost crossed level answers before any gate below it");
    }

    /**
     * The four cells {@link RuleOperandScopeLawsTest} and {@link ParallelIterationGateLawsTest} pinned on
     * their own fixtures, re-measured here.
     *
     * <p>Not a duplicate and not a transfer: a checker implementing this chain reads one model, so a table
     * mixing cells from three fixtures cannot say whether a verdict belongs to the chain or to a fixture. The
     * type row is the one that most needed a same-model home — its two index fields differ in declared type
     * and in nothing else, where the class it draws was first measured against a model whose key names also
     * happened to match.
     */
    @Test
    void theSiblingCellsOnThisFixture() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("sibling keyed under ANOTHER name",
                verdict("/Fulfilment/Invoices/InvoiceAmount", "/Fulfilment/Refunds/RefundAmount"));
        actual.put("sibling with NO index field",
                verdict("/Fulfilment/Invoices/InvoiceAmount", "/Fulfilment/Returns/ReturnAmount"));
        actual.put("sibling keyed under the same name, of another TYPE",
                verdict("/Fulfilment/Invoices/InvoiceAmount", "/Fulfilment/Adjustments/AdjustmentAmount"));
        actual.put("a level strictly BELOW the target's own",
                verdict("/Fulfilment/Shipments/ShipmentCost", "/Fulfilment/Shipments/Parcels/ParcelWeight"));
        actual.put("sibling keyed under ANOTHER name, rule declared at the ROOT",
                verdictFrom("/Fulfilment", "/Fulfilment/Invoices/ShipmentKey",
                        "/Fulfilment/Refunds/RefundAmount"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("sibling keyed under ANOTHER name", "MVK_ERROR_FIELD_INVALID_INDEX");
        expected.put("sibling with NO index field", NO_WILDCARD);
        expected.put("sibling keyed under the same name, of another TYPE", "MVK_ERROR_FIELD_INVALID_INDEX_TYPE");
        expected.put("a level strictly BELOW the target's own", NO_WILDCARD);
        expected.put("sibling keyed under ANOTHER name, rule declared at the ROOT",
                "MVK_ERROR_FIELD_INVALID_INDEX");
        assertEquals(expected, actual, "the sibling cells, on the fixture the rest of the chain runs on");
    }

    /**
     * An unstarred repeatable level BELOW a starred one. The star collapses its own level, so nothing above
     * iterates — the question is only what the level beneath it draws, and it is the one shape
     * {@code ErrorFieldReference}'s residue note left unmeasured.
     */
    @Test
    void anUnstarredLevelBeneathAStarredOne() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("aggregate over a starred level, unstarred level below it",
                KernelLaws.verdictModel(FULFILMENT, "/Fulfilment", "../OrderTotal",
                        "FieldFilled(OrderTotal) And Sum(Shipments*/Parcels/ParcelWeight) > 0"));
        actual.put("aggregate over both levels starred — control",
                KernelLaws.verdictModel(FULFILMENT, "/Fulfilment", "../OrderTotal",
                        "FieldFilled(OrderTotal) And Sum(Shipments*/Parcels*/ParcelWeight) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("aggregate over a starred level, unstarred level below it", NO_WILDCARD);
        expected.put("aggregate over both levels starred — control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "an iteration below a wildcard has no repetition to be about");
    }
}
