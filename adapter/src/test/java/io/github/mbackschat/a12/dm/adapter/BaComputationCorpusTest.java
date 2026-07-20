package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringLiteral;

/**
 * Own-domain transfer of the <b>kernel-team BA doc's worked Computation-Rule examples</b>
 * ({@code kernel-ba.md} "Computation Rules", license-safe: mechanism re-expressed, not the text) — the
 * BA-doc peer of {@code ComputationShapesTest} (which transferred the kernel-mm + workspaces shapes).
 * Each BA example is re-expressed and read through the full cycle (render → extract → convert
 * identity), confirming the converter handles the shapes the prose documents:
 *
 * <ul>
 *   <li>string <b>concatenation</b> (BA Example 2) with an {@code AllFieldsFilled} guard (Example 6);</li>
 *   <li><b>tiered</b> computation with a rounding op in the calc (Example 3: {@code RoundUp([x] * 0.05)});</li>
 *   <li>a grouped sub-expression with <b>division</b> + {@code RoundDown} (Example 6);</li>
 *   <li>a <b>common precondition</b> over tiered concat alternatives (Example 4);</li>
 *   <li><b>CurrentRepetition</b>-driven tiered computation of string constants (the current-repetition example);</li>
 *   <li><b>parallel iteration</b> — a computation summing across sibling repeatable groups joined by a shared index (Example 9).</li>
 * </ul>
 */
class BaComputationCorpusTest {

    private static void roundTrips(BoundModel m, Computation comp, String fullName) {
        ReadComputation result = KernelAdapter.of(m.add(comp)).readComputation(fullName);
        Computation got = assertInstanceOf(ReadComputation.Converted.class, result,
                () -> "expected a converted computation, got: " + result).computation();
        assertEquals(comp.draft(), got.draft());
    }

    /** A string field's value as a {@link StringExpr} part (for concatenation). */
    private static StringExpr str(BoundModel m, String path) {
        return new StringExpr(new FieldValue(m.field(path)));
    }

    /** BA Example 2 + 6 — string concatenation with an AllFieldsFilled guard (no trailing-space artifact). */
    @Test
    void concatenationWithGuard() {
        BoundModel m = BoundModel.ofJson(DocumentModelDraft.named("person").locale("en_US")
                .rootGroup("Person", p -> p
                        .field("FirstName", FieldKind.STRING)
                        .field("LastName", FieldKind.STRING)
                        .field("DisplayName", FieldKind.STRING))
                .build().toJson());
        // the AllFieldsFilled guard (BA Example 6) sits on the single alternative — for a one-row table the
        // kernel folds a "common precondition + unconditional calc" into one preconditioned alternative.
        Computation comp = m.computation(m.field("/Person/DisplayName")).name("DisplayNameComp")
                .when(m.allFieldsFilled("/Person/FirstName", "/Person/LastName"))
                .calc(str(m, "/Person/FirstName").plus(" ").plus(str(m, "/Person/LastName")).expression())
                .message(en("DisplayName is the full name.")).build();
        roundTrips(m, comp, "/Person/DisplayNameComp");
    }

    /** BA Example 3 — a tiered computation whose calc rounds (RoundUp), with mutually-exclusive preconditions. */
    @Test
    void tieredWithRounding() {
        BoundModel m = BoundModel.ofJson(DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .numberField("Subtotal", n -> { })
                        .numberField("Discount", n -> { }))
                .build().toJson());
        Computation comp = m.computation(m.num("/Order/Discount")).name("DiscountComp")
                .when(m.num("/Order/Subtotal").lt(100)).calc(NumberLiteral.of(0))
                .when(m.num("/Order/Subtotal").ge(100))
                .calc(Dsl.roundUp(m.num("/Order/Subtotal").asNumber().times(Dsl.number("0.05"))).expression())
                .message(en("Discount is 5% above 100, rounded up.")).build();
        roundTrips(m, comp, "/Order/DiscountComp");
    }

    /** BA Example 6 — a grouped sub-expression with division, wrapped in RoundDown. */
    @Test
    void divisionInAGroupedSubExpression() {
        BoundModel m = BoundModel.ofJson(DocumentModelDraft.named("project").locale("en_US")
                .rootGroup("Project", p -> p
                        .numberField("Labor", n -> { })
                        .numberField("TaxRate", n -> { })
                        .numberField("Total", n -> { }))
                .build().toJson());
        // Total = RoundDown(Labor * (1 + TaxRate / 100))
        NumberExpr factor = Dsl.number("1").plus(m.num("/Project/TaxRate").asNumber().dividedBy(Dsl.number("100")));
        Computation comp = m.computation(m.num("/Project/Total")).name("TotalComp")
                .calc(Dsl.roundDown(m.num("/Project/Labor").asNumber().times(factor)).expression())
                .message(en("Total is labor plus tax, rounded down.")).build();
        roundTrips(m, comp, "/Project/TotalComp");
    }

    /** BA Example 4 — a common precondition (enum) over tiered concat / field-value alternatives. */
    @Test
    void commonPreconditionOverTieredConcat() {
        BoundModel m = BoundModel.ofJson(DocumentModelDraft.named("address").locale("en_US")
                .rootGroup("Address", a -> a
                        .enumField("Kind", e -> e.value("ADDRESS").value("POB"))
                        .field("Street", FieldKind.STRING)
                        .field("HouseNumber", FieldKind.STRING)
                        .field("StreetLine", FieldKind.STRING))
                .build().toJson());
        Computation comp = m.computation(m.field("/Address/StreetLine")).name("StreetLineComp")
                .commonPrecondition(m.enumOf("/Address/Kind").is("ADDRESS"))
                .when(m.field("/Address/HouseNumber").filled())
                .calc(str(m, "/Address/Street").plus(" ").plus(str(m, "/Address/HouseNumber")).expression())
                .when(m.field("/Address/HouseNumber").notFilled())
                .calc(new FieldValue(m.field("/Address/Street")))
                .message(en("StreetLine joins street and house number when an address.")).build();
        roundTrips(m, comp, "/Address/StreetLineComp");
    }

    /** The current-repetition example — a fixed-size table: the title depends on the row's repetition index. */
    @Test
    void currentRepetitionTiered() {
        BoundModel m = BoundModel.ofJson(DocumentModelDraft.named("org").locale("en_US")
                .rootGroup("Org", o -> o
                        .group("Roles", g -> g.repeatable(10).field("Title", FieldKind.STRING)))
                .build().toJson());
        Computation comp = m.computation(m.field("/Org/Roles/Title")).name("TitleComp")
                .when(Dsl.groupFilled(m.group("/Org/Roles")).and(Dsl.currentRepetition(m.group("/Org/Roles")).eq(1)))
                .calc(new StringLiteral("LEAD"))
                .when(Dsl.groupFilled(m.group("/Org/Roles")).and(Dsl.currentRepetition(m.group("/Org/Roles")).eq(2)))
                .calc(new StringLiteral("DEPUTY"))
                .message(en("Title follows the role row.")).build();
        roundTrips(m, comp, "/Org/Roles/TitleComp");
    }

    /** BA Example 9 — Parallel Iteration: a computation summing across parallel groups joined by a shared index. */
    @Test
    void parallelIterationAcrossGroups() {
        BoundModel m = BoundModel.ofJson(DocumentModelDraft.named("staffing").locale("en_US")
                .rootGroup("Staffing", s -> s
                        .field("PlanName", FieldKind.STRING)
                        .group("Demand", g -> g.repeatable(50).indexField("Department")
                                .field("Department", FieldKind.STRING).numberField("Headcount", n -> { }))
                        .group("Supply", g -> g.repeatable(50).indexField("Department")
                                .field("Department", FieldKind.STRING).numberField("Headcount", n -> { })
                                .numberField("Combined", n -> { })))
                .build().toJson());
        // Combined[dept] = Demand.Headcount[dept] + Supply.Headcount[dept] — joined by the shared Department index
        Computation comp = m.computation(m.num("/Staffing/Supply/Combined")).name("CombinedComp")
                .when(m.field("/Staffing/Supply/Department").filled())
                .calc(m.num("/Staffing/Demand/Headcount").asNumber()
                        .plus(m.num("/Staffing/Supply/Headcount").asNumber()).expression())
                .message(en("Combined is demand plus supply headcount per department.")).build();
        roundTrips(m, comp, "/Staffing/Supply/CombinedComp");
    }
}
