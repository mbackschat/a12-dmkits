package io.github.mbackschat.a12.dm.adapter.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;

/**
 * <b>Does the INDEXED-PARALLEL carrier clear a seeded target in an over-limit row?</b> a12-kernel-lean measured
 * the clear for the addressed and constant carriers and left this one unclaimed rather than inheriting it: the
 * parallel carrier's index column has no entry at an over-limit row, so that row is arguably outside its
 * execution domain altogether, which is a different reason from the other carriers' exclusion.
 *
 * <p>Own domain (dispatch picking). {@code Lines} is declared max 2 and joined to {@code Picks} by
 * {@code LineRef}, so {@code Fulfilled} in a third instantiated line is the row in question.
 */
class OverLimitIndexedParallelTargetLawsTest {

    private static final String TARGET = "/Dispatch/Lines/Fulfilled";

    private static final String DISPATCH = DocumentModelDraft.named("dispatch").locale("en_US")
            .rootGroup("Dispatch", d -> d
                    .field("Reference", FieldKind.STRING)
                    .group("Lines", g -> g
                            .repeatable(2).indexField("LineRef")
                            .field("LineRef", FieldKind.STRING)
                            .numberField("Fulfilled", n -> n.fractionDigits(0)))
                    .group("Picks", g -> g
                            .repeatable(9).indexField("LineRef")
                            .field("LineRef", FieldKind.STRING)
                            .numberField("PickedQty", n -> n.fractionDigits(0))))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(DISPATCH);

    /** {@code Fulfilled = [Picks/PickedQty For LineRef]} — the parallel join keyed by this row's own index. */
    private static final String MODEL = model();

    private static String model() {
        Computation comp = M.computation(M.field(TARGET)).name("FulfilledComp")
                .calc(M.num("/Dispatch/Picks/PickedQty").forIndex(M.field("/Dispatch/Lines/LineRef")).expression())
                .message(en("Fulfilled from picks."))
                .build();
        return M.add(comp).toJson();
    }

    /** {@code lines} instantiated line rows, each with a matching pick; the LAST line's target is seeded wrong. */
    private static RuntimeLaws.Doc doc(int lines) {
        RuntimeLaws.Doc d = RuntimeLaws.doc().group("/Dispatch", 1)
                .field("/Dispatch/Reference", "DSP-1", 1, 1);
        for (int i = 1; i <= lines; i++) {
            d = d.group("/Dispatch/Lines", 1, i)
                    .field("/Dispatch/Lines/LineRef", "L" + i, 1, i, 1)
                    .group("/Dispatch/Picks", 1, i)
                    .field("/Dispatch/Picks/LineRef", "L" + i, 1, i, 1)
                    .field("/Dispatch/Picks/PickedQty", String.valueOf(i * 10), 1, i, 1);
        }
        return d.field("/Dispatch/Lines/Fulfilled", "999", 1, lines, 1);
    }

    /** Every {@code Fulfilled} outcome, keyed by pointer so row order cannot affect the assertion. */
    private static Map<String, String> outcomes(RuntimeLaws.Doc doc) {
        return RuntimeLaws.compute(MODEL, doc).stream()
                .filter(c -> c.path().contains("Fulfilled"))
                .collect(Collectors.toMap(RuntimeLaws.Computed::pointer,
                        c -> c.value().orElse("-") + (c.cleared() ? " CLEARED" : "")
                                + (c.errored() ? " ERR" : ""), (a, b) -> a, LinkedHashMap::new));
    }

    /** Two lines, the SECOND having no matching pick — an in-capacity row whose join cannot resolve. */
    private static RuntimeLaws.Doc unmatched() {
        return RuntimeLaws.doc().group("/Dispatch", 1)
                .field("/Dispatch/Reference", "DSP-1", 1, 1)
                .group("/Dispatch/Lines", 1, 1)
                .field("/Dispatch/Lines/LineRef", "L1", 1, 1, 1)
                .group("/Dispatch/Picks", 1, 1)
                .field("/Dispatch/Picks/LineRef", "L1", 1, 1, 1)
                .field("/Dispatch/Picks/PickedQty", "10", 1, 1, 1)
                .group("/Dispatch/Lines", 1, 2)
                .field("/Dispatch/Lines/LineRef", "L9", 1, 2, 1)
                .field("/Dispatch/Lines/Fulfilled", "999", 1, 2, 1);
    }

    /**
     * <b>The indexed-parallel carrier clears the excluded row too — and NOT for the reason the open question
     * assumed.</b> a12-kernel-lean left this carrier unclaimed because a parallel computation's index column
     * has no entry at an over-limit row, so the row might be outside its execution domain rather than merely
     * excluded by capacity. This fixture gives the over-limit row a **resolvable** join — line 3 carries
     * {@code LineRef "L3"} and a matching pick of 30 — and it is still cleared, so the clearing follows the
     * capacity exclusion and not the index domain.
     *
     * <p>The third row is what makes {@code CLEARED} mean something. An in-capacity line whose join has no
     * partner does not clear: it computes the kind's empty value **0** and overwrites the seed. So the carrier
     * distinguishes "no value to compute" (a number, 0) from "this row is not mine to write" (cleared, no
     * value), and the over-limit row lands in the second. Without that control, {@code CLEARED} at line 3
     * would be equally consistent with an unresolved join.
     *
     * <p>Both in-capacity rows are asserted unchanged across the at-capacity and over-limit documents, so the
     * excess row does not disturb its siblings.
     */
    @Test
    void theIndexedParallelCarrierClearsAnOverLimitTargetEvenWithAResolvableJoin() {
        assertEquals(Map.of(
                        "/Dispatch[1]/Lines[1]/Fulfilled", "10",
                        "/Dispatch[1]/Lines[2]/Fulfilled", "20"),
                outcomes(doc(2)),
                "at capacity the parallel join computes both rows");

        assertEquals(Map.of(
                        "/Dispatch[1]/Lines[1]/Fulfilled", "10",
                        "/Dispatch[1]/Lines[2]/Fulfilled", "20",
                        "/Dispatch[1]/Lines[3]/Fulfilled", "- CLEARED"),
                outcomes(doc(3)),
                "the over-limit row is cleared though its join would have yielded 30 and its cell held 999");

        assertEquals(Map.of(
                        "/Dispatch[1]/Lines[1]/Fulfilled", "10",
                        "/Dispatch[1]/Lines[2]/Fulfilled", "0"),
                outcomes(unmatched()),
                "an unresolvable join computes 0 rather than clearing — so CLEARED is the capacity exclusion");
    }
}
