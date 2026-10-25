package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.RenderedDiff;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * The modify diff shows the scale-gate waiver on each side that carries it (KERNEL-FINDINGS KF75). It rendered the
 * bare conditions, so adding or dropping the waiver diffed as unchanged and the "before" of a waived rule was a
 * condition A12 refuses (2026-10-25 cold review, R2).
 */
class ScaleWaiverDiffTest {

    private static final BoundModel M = BoundModel.ofJson(DocumentModelDraft.named("policy-scale-diff").locale("en_US")
            .rootGroup("Policy", p -> p
                    .numberField("Units", n -> n.fractionDigits(0))
                    .numberField("Rate", n -> n.fractionDigits(2)))
            .build().toJson());
    private static final NumberRef UNITS = M.num("/Policy/Units");
    private static final NumberRef RATE = M.num("/Policy/Rate");
    private static final Condition EQUAL = RATE.filled().and(UNITS.eq(RATE.asNumber().allowingDifferingDecimals()));
    private static final String WAIVER = "@SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES) ";
    private static final String TEXT = "FieldFilled(/Policy/Rate) And [/Policy/Units] == [/Policy/Rate]";

    private static KernelAdapter waivedModel() {
        return KernelAdapter.of(M.add(M.rule(M.group("/Policy")).name("Waived").errorField(UNITS.field()).when(EQUAL)
                .allowDifferingDecimals().code("WAIVED").message(Dsl.en("Units equal the rate.")).build()));
    }

    @Test
    void eachSideShowsTheWaiverItCarries() {
        KernelAdapter adapter = waivedModel();
        RenderedDiff kept = adapter.diff("/Policy/Waived", new ReadResult.Converted(EQUAL, true));
        RenderedDiff dropped = adapter.diff("/Policy/Waived", new ReadResult.Converted(EQUAL, false));
        assertEquals(List.of(WAIVER + TEXT, WAIVER + TEXT, false), List.of(kept.before(), kept.after(), kept.changed()));
        assertEquals(List.of(WAIVER + TEXT, TEXT, true),
                List.of(dropped.before(), dropped.after(), dropped.changed()));
    }
}
