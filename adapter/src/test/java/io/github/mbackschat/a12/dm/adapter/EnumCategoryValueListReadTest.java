package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.dsl.ValueListOperand;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * F10 read tail — enum CATEGORY projections ({@code [field -> Cat]}) inside the field-list × value-list
 * quantifiers round-trip with their exact projection retained on BOTH sides (SPEC-2026-07-22-05). The old
 * converter erased {@code getCategoryName()} into the bare field. The order fixture's {@code Priority}
 * carries category {@code SpeedClass}; {@code RushLevel} is a second, disjoint enum.
 */
class EnumCategoryValueListReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);
    private static final String PRIORITY = "/Order/Priority";
    private static final String RUSH = "/Order/RushLevel";

    private final BoundModel m = BoundModel.ofJson(TestResources.text("models/order.dm.json"));

    @Test
    void literalFieldListRetainsADirectRefAndACategoryRefAsDistinctMembers() {
        Condition c = Dsl.noFieldValueIncludedInValueList(List.of("STANDARD", "FAST"),
                m.enumOf(PRIORITY), m.enumOf(PRIORITY).category("SpeedClass"));
        roundTrips("NoDirectAndCategory", c);
    }

    @Test
    void entityValuedRetainsACategoryProjectionOnTheValuesSide() {
        ValueListOperand speedClass = m.enumOf(PRIORITY).category("SpeedClass").listOperand();
        Condition c = Dsl.atLeastOneFieldValueIncludedInValueList(List.of(speedClass), m.enumOf(RUSH).field());
        roundTrips("EvValuesCategory", c);
    }

    @Test
    void entityValuedRetainsACategoryProjectionOnTheFieldsSide() {
        ValueListOperand rush = m.enumOf(RUSH).field();
        Condition c = Dsl.atLeastOneFieldValueIncludedInValueList(List.of(rush),
                m.enumOf(PRIORITY).category("SpeedClass").listOperand());
        roundTrips("EvFieldsCategory", c);
    }

    private void roundTrips(String name, Condition original) {
        Rule r = m.rule(m.group("/Order")).name(name).errorField(m.field(PRIORITY)).when(original)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
        KernelAdapter adapter = KernelAdapter.of(m.add(r));
        ReadResult result = adapter.read("/Order/" + name);
        Condition back = assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
        assertEquals(RENDER.render(original), RENDER.render(back),
                () -> "category projection lost on round-trip for " + name);
    }
}
