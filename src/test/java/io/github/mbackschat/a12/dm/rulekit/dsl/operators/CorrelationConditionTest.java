package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;

/**
 * The {@code $} outer-iteration correlation marker (D13). Inside a {@code Having} filter, {@code $}
 * binds a reference to the <em>outer</em> rule iteration, so an aggregate can be correlated to the
 * current row — e.g. "sum the fees of all add-on rows sharing the current row's Name". The marker is
 * legal only inside a filter (kernel: {@code MVK_INVALID_OUTER_ITERATION} otherwise) and is not
 * localized ({@code $} is the same token in every render language).
 */
class CorrelationConditionTest {

    private static final BoundModel m = Fixtures.subscription();

    @Test
    void rendersOuterCorrelationInsideHavingFilter() {
        // [inner] == [$outer]: keep only the rows whose Name equals the current (outer) row's Name.
        FieldListRef fees = m.flatten("/Subscription/Addons*/MonthlyFee")
                .having(m.str("/Subscription/Addons/Name").eq(m.str("/Subscription/Addons/Name").outer()));

        assertEquals("Sum(/Subscription/Addons*/MonthlyFee Having [/Subscription/Addons/Name]"
                        + " == [$/Subscription/Addons/Name]) > 0",
                new DslRenderer().render(Dsl.sum(fees).gt(0)));
        assertEquals("Summe(/Subscription/Addons*/MonthlyFee Mit [/Subscription/Addons/Name]"
                        + " == [$/Subscription/Addons/Name]) > 0",
                new DslRenderer(ConditionLanguage.DE).render(Dsl.sum(fees).gt(0)));
    }

    @Test
    void fieldRefAndFieldListShareTheOuterVerbWithTheValueRefs() {
        // D2: the bare-operand correlation builder is outer() on FieldRef/FieldListRef too (was asOuter()),
        // so the whole ref family names the one $ concept the same way.
        assertEquals("NoFieldFilled($/Subscription/Addons/Name)",
                new DslRenderer().render(Dsl.noFieldFilled(m.field("/Subscription/Addons/Name").outer())));
        assertEquals("NoFieldFilled($/Subscription/Addons*/MonthlyFee)",
                new DslRenderer().render(Dsl.noFieldFilled(m.flatten("/Subscription/Addons*/MonthlyFee").outer())));
    }
}
