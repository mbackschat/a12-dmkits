package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * What EQUALITY demands of two numbers' declared scales, and what a number may stand against at all.
 *
 * <p>{@code CheckVergleichsBedingungImpl.checkNumberComparison} is the last arm of the comparison walk and
 * carries two classes. The scale gate is the surprising one: {@code ==} and {@code !=} require the two sides
 * to declare the SAME number of fractional digits, so a scale-2 declaration is refused against the written-out
 * {@code 1} and admitted against {@code 1.00} — a literal's scale is what it spells, not what it means.
 *
 * <p>The existing locks measure the gate through EXPRESSIONS, where the multiplicative-constant waiver
 * relaxes it ({@code NumberLawsTest}, {@code PowerDerivedScaleWaiverLawsTest}). This grid measures the BARE
 * pair, where no waiver applies and the law is plain equality of declared scale — which is the population a
 * checker over stored DM-JSON can type.
 */
class NumberComparisonScaleGateLawsTest {

    private static final String GROUP = "/Order";

    private static final String ORDER_JSON = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order", g -> {
                g.stringField("Reference", f -> { });
                g.numberField("Quantity", n -> n.fractionDigits(0));
                g.numberField("Backordered", n -> n.fractionDigits(0));
                g.numberField("UnitPrice", n -> n.fractionDigits(2));
                g.numberField("Discount", n -> n.fractionDigits(2));
                g.field("Expedited", FieldKind.BOOLEAN);
            }).build().toJson();

    private static final String SCALE = "MVK_INVALID_COMPARE_DEC_PLACES";

    private static final String WRONG_TYPE = "MVK_INCONSISTENT_TYPES_COMPARED";

    private static String verdict(String comparison) {
        return KernelLaws.verdictModel(ORDER_JSON, GROUP, "../Reference",
                "FieldFilled(Reference) And " + comparison);
    }

    /** Equality between two bare numbers, and the scales that make it legal. */
    @Test
    void whichScalesEqualityAdmits() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two declarations of the same scale", verdict("[Quantity] == [Backordered]"));
        actual.put("two declarations of scale 2", verdict("[UnitPrice] == [Discount]"));
        actual.put("scale 0 against scale 2", verdict("[Quantity] == [UnitPrice]"));
        actual.put("scale 2 against scale 0", verdict("[UnitPrice] == [Quantity]"));
        actual.put("the same two under inequality", verdict("[Quantity] != [UnitPrice]"));
        actual.put("the same two under ORDERING", verdict("[Quantity] < [UnitPrice]"));
        actual.put("scale 0 against a whole written-out number", verdict("[Quantity] == 1"));
        actual.put("scale 0 against a two-place written-out number", verdict("[Quantity] == 1.00"));
        actual.put("scale 2 against a whole written-out number", verdict("[UnitPrice] == 1"));
        actual.put("scale 2 against a ONE-place written-out number", verdict("[UnitPrice] == 1.0"));
        actual.put("scale 0 against a one-place written-out number", verdict("[Quantity] == 1.0"));
        actual.put("scale 2 against a two-place written-out number", verdict("[UnitPrice] == 1.00"));
        actual.put("a two-place written-out number on the LEFT of scale 0", verdict("1.00 == [Quantity]"));
        actual.put("scale 2 against a NEGATIVE two-place number", verdict("[UnitPrice] == -1.00"));
        actual.put("differing scales within a TOLERANCE",
                verdict("[Quantity] DiffersWithToleranceRange1 [UnitPrice]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two declarations of the same scale", KernelLaws.ADMITTED);
        expected.put("two declarations of scale 2", KernelLaws.ADMITTED);
        expected.put("scale 0 against scale 2", SCALE);
        expected.put("scale 2 against scale 0", SCALE);
        expected.put("the same two under inequality", SCALE);
        // The gate is asked only of `==` and `!=`; ordering compares magnitudes and needs no common scale.
        expected.put("the same two under ORDERING", KernelLaws.ADMITTED);
        expected.put("scale 0 against a whole written-out number", KernelLaws.ADMITTED);
        expected.put("scale 0 against a two-place written-out number", SCALE);
        // A written-out number is the one operand A12 will RESCALE, so a differing pair passes exactly when
        // the smaller-scale side is the literal. That is why this row and the one above it disagree: both
        // pair a declaration with a literal of a different scale, and only here is the literal the smaller.
        expected.put("scale 2 against a whole written-out number", KernelLaws.ADMITTED);
        expected.put("scale 2 against a ONE-place written-out number", KernelLaws.ADMITTED);
        expected.put("scale 0 against a one-place written-out number", SCALE);
        expected.put("scale 2 against a two-place written-out number", KernelLaws.ADMITTED);
        expected.put("a two-place written-out number on the LEFT of scale 0", SCALE);
        expected.put("scale 2 against a NEGATIVE two-place number", KernelLaws.ADMITTED);
        // The tolerance arm RETURNS once both sides are numbers, so it never reaches the scale gate.
        expected.put("differing scales within a TOLERANCE", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "which scales equality admits");
    }

    /** The other class of the same method: a number against something that is not number-like at all. */
    @Test
    void whatANumberMayStandAgainst() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("against True", verdict("[Quantity] == True"));
        actual.put("against False", verdict("[Quantity] == False"));
        actual.put("against True on the LEFT", verdict("True == [Quantity]"));
        actual.put("against True under inequality", verdict("[Quantity] != True"));
        actual.put("against a BOOLEAN declaration", verdict("[Quantity] == [Expedited]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("against True", WRONG_TYPE);
        expected.put("against False", WRONG_TYPE);
        expected.put("against True on the LEFT", WRONG_TYPE);
        expected.put("against True under inequality", WRONG_TYPE);
        // A boolean DECLARATION is the yes/no arm's business and answers well before this one, which is what
        // makes the written-out constants the whole reachable population of this class on a comparison.
        expected.put("against a BOOLEAN declaration", "MVK_INVALID_COMPARE_TO_YESNO");
        assertEquals(expected, actual, "what a number may stand against");
    }

    /** The scale gate is the one comparison class a COMPUTATION may switch off for itself. */
    @Test
    void aComputationMaySuppressTheScaleGate() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a mismatched-scale precondition", computationVerdict(List.of()));
        actual.put("the same precondition, suppressed", computationVerdict(List.of(SCALE)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a mismatched-scale precondition", SCALE);
        expected.put("the same precondition, suppressed", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "a computation may suppress the scale gate");
    }

    /**
     * What scale a declaration that OMITS `maxFractionalDigits` carries.
     *
     * <p>Every row above is built through modelkit, which always writes the member; stored DM-JSON need not,
     * and a checker has to know whether absence means zero or means unreadable.
     */
    @Test
    void whatScaleAnOmittedFractionalDigitsDeclares() {
        String withoutTheMember = ORDER_JSON.replace(
                "\"NumberType\":{\"maxFractionalDigits\":0}", "\"NumberType\":{}");
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("against a scale-2 declaration", KernelLaws.verdictModel(withoutTheMember, GROUP,
                "../Reference", "FieldFilled(Reference) And [Quantity] == [UnitPrice]"));
        actual.put("against a whole written-out number", KernelLaws.verdictModel(withoutTheMember, GROUP,
                "../Reference", "FieldFilled(Reference) And [Quantity] == 1"));
        actual.put("against a two-place written-out number", KernelLaws.verdictModel(withoutTheMember, GROUP,
                "../Reference", "FieldFilled(Reference) And [Quantity] == 1.00"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("against a scale-2 declaration", SCALE);
        expected.put("against a whole written-out number", KernelLaws.ADMITTED);
        expected.put("against a two-place written-out number", SCALE);
        assertEquals(expected, actual, "what scale an omitted fractional-digits member declares");
    }

    private static String computationVerdict(List<String> suppressed) {
        DraftComputation draft = DraftComputation.builder()
                .groupPath(GROUP).name("ScaleProbe")
                .computedFieldRelPath("../Discount")
                .commonPrecondition("[Quantity] == [UnitPrice]")
                .alternatives(List.of(new DraftComputation.Alternative(null, "1.00")))
                .errorCodesToSuppress(suppressed)
                .messages(List.of(en("x"), de("y")))
                .build();
        return KernelLaws.verdictOf(
                ModelWriter.addComputation(ORDER_JSON, draft, List.of(en("x"), de("y"))));
    }
}
