package io.github.mbackschat.a12.dm.adapter.review;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.TestResources;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ChangeKind;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementChange;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * MODEL-REVIEW MR2 — a pure element <b>reorder</b> is a display-order (hence semantic, SPEC §2b) change.
 * The engine must report it as {@code REORDERED} ({@code POSITION_CHANGED}, LOW) with a before→after
 * position delta, AND must never invent a reorder when an add/remove merely shifts absolute indices
 * (the both-directions discipline: detect the real move, do not fabricate one).
 */
class ModelDiffReorderTest {

    private static final String ADDONS = "/Subscription/Addons";
    private static final String FEE = ADDONS + "/MonthlyFee";
    private static final String FEE_CAP = ADDONS + "/FeeCapRule";
    private static final String FEE_FLOOR = ADDONS + "/FeeFloorRule";

    private static String base() {
        return TestResources.text("models/subscription.dm.json");
    }

    /** {@code base} + the named rules on {@code MonthlyFee}, added in the given order (document order = add order). */
    private static String withRules(String json, String... names) {
        BoundModel m = BoundModel.ofJson(json);
        for (String name : names) {
            m = m.add(ruleNamed(m, name));
        }
        return m.toJson();
    }

    /** A fee sanity-band rule catalog — three distinct, valid rules all guarding {@code MonthlyFee}. */
    private static Rule ruleNamed(BoundModel m, String name) {
        NumberRef fee = m.num(FEE);
        return switch (name) {
            case "cap" -> m.rule(m.group(ADDONS)).name("FeeCapRule").errorField(fee).when(fee.gt(2000))
                    .code("FEE_CAP").severity(Severity.ERROR)
                    .message(en("Monthly fee exceeds the maximum."), de("Monatsgebühr überschreitet das Maximum.")).build();
            case "floor" -> m.rule(m.group(ADDONS)).name("FeeFloorRule").errorField(fee).when(fee.lt(0))
                    .code("FEE_FLOOR").severity(Severity.ERROR)
                    .message(en("Monthly fee must not be negative."), de("Monatsgebühr darf nicht negativ sein.")).build();
            case "review" -> m.rule(m.group(ADDONS)).name("FeeReviewRule").errorField(fee).when(fee.gt(1000))
                    .code("FEE_REVIEW").severity(Severity.ERROR)
                    .message(en("Monthly fee above 1000 needs review."), de("Monatsgebühr über 1000 muss geprüft werden.")).build();
            default -> throw new IllegalArgumentException(name);
        };
    }

    @Test
    void aPureReorderIsReportedAsReordered() {
        String base = withRules(base(), "cap", "floor");   // [FeeCap, FeeFloor]
        String head = withRules(base(), "floor", "cap");   // [FeeFloor, FeeCap] — same rules, swapped order

        List<ElementChange> reorders = ModelDiff.between(base, head).changes().stream()
                .filter(c -> c.change() == ChangeKind.REORDERED).toList();
        assertEquals(1, reorders.size(), () -> "one minimal reorder (the moved element), got: " + reorders);
        ElementChange r = reorders.get(0);
        assertEquals(ElementKind.RULE, r.element());
        assertEquals(ModelDiff.Risk.LOW, r.risk(), () -> "reorder is a display-order change → LOW: " + r);
        assertTrue(r.reasons().contains("POSITION_CHANGED"), () -> "reasons: " + r.reasons());
        ModelDiff.Delta pos = r.deltas().stream().filter(d -> d.aspect().equals("position")).findFirst()
                .orElseThrow(() -> new AssertionError("no position delta in " + r.deltas()));
        assertNotEquals(pos.before(), pos.after(), () -> "position must move: " + pos);
    }

    @Test
    void identicalOrderHasNoReorder() {
        String m = withRules(base(), "cap", "floor");
        assertEquals(0, ModelDiff.between(m, m).changes().stream()
                .filter(c -> c.change() == ChangeKind.REORDERED).count(), "same model → no reorder");
    }

    @Test
    void removingAFrontSiblingDoesNotFalselyReorderTheRest() {
        // base [cap, floor, review]; head removes the FRONT rule → [floor, review].
        // floor/review keep their RELATIVE order (their absolute indices shift by 1) — that is NOT a reorder.
        String base = withRules(base(), "cap", "floor", "review");
        String head = withRules(base(), "floor", "review");

        ModelDiff diff = ModelDiff.between(base, head);
        assertTrue(diff.changes().stream().anyMatch(
                c -> c.change() == ChangeKind.REMOVED && c.id().equals(FEE_CAP)), "cap must be REMOVED");
        assertEquals(0, diff.changes().stream().filter(c -> c.change() == ChangeKind.REORDERED).count(),
                () -> "an index shift from a removal must not invent a reorder: " + diff.changes());
    }

    @Test
    void reorderFoldsIntoAContentChangeInsteadOfDoubleListing() {
        // FeeCap changes threshold (2000→3000) AND is moved after FeeFloor: ONE change, MODIFIED + POSITION_CHANGED.
        String base = withRules(base(), "cap", "floor");
        BoundModel m = BoundModel.ofJson(base());
        NumberRef fee = m.num(FEE);
        Rule capBumped = m.rule(m.group(ADDONS)).name("FeeCapRule").errorField(fee).when(fee.gt(3000))
                .code("FEE_CAP").severity(Severity.ERROR)
                .message(en("Monthly fee exceeds the maximum."), de("Monatsgebühr überschreitet das Maximum.")).build();
        String head = m.add(ruleNamed(m, "floor")).add(capBumped).toJson();   // [FeeFloor, FeeCap-bumped]

        List<ElementChange> forCap = ModelDiff.between(base, head).changes().stream()
                .filter(c -> c.id().equals(FEE_CAP)).toList();
        assertEquals(1, forCap.size(), () -> "the moved-and-edited rule must not be double-listed: " + forCap);
        ElementChange c = forCap.get(0);
        assertEquals(ChangeKind.MODIFIED, c.change(), () -> "content change dominates the kind: " + c);
        assertTrue(c.reasons().contains("POSITION_CHANGED"), () -> "position folded in: " + c.reasons());
        assertTrue(c.deltas().stream().anyMatch(d -> d.aspect().equals("position")), () -> "position delta: " + c.deltas());
        assertTrue(c.deltas().stream().anyMatch(d -> d.aspect().equals("condition")), () -> "condition delta: " + c.deltas());
    }
}
