package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;

/**
 * {@code MVK_ERROR_EMPTY_PRECONDITION} — a computation's alternatives are either ONE unconditional
 * {@code calc(...)} or all preconditioned; mixing the two is refused.
 *
 * <p>{@code Computation.Builder} has refused on this code with nothing measuring it, which the refusal
 * census could not see until kernel codes became typed: the citation sat in a string handed to a local
 * {@code fail} helper, outside the statement shape it scanned for. Adding the typed scan surfaced it
 * immediately, which is the point of the change.
 *
 * <p>The illegal shape is unauthorable through the builder — that IS the refusal under test — so the draft
 * is written directly, at the layer where a computation is already rendered text.
 */
class ComputationAlternativeShapeLawsTest {

    private static final String MODEL = DocumentModelDraft.named("computation-alternative-shape").locale("en_US")
            .rootGroup("Ledger", l -> l
                    .field("Reference", FieldKind.STRING)
                    .numberField("Amount", n -> n.min("0").max("1000000"))
                    .numberField("Total", n -> n.min("0").max("1000000")))
            .build().toJson();

    private static String verdict(List<DraftComputation.Alternative> alternatives) {
        return KernelLaws.computationVerdict(MODEL,
                new DraftComputation("/Ledger", "TotalProbe", "../Total", null, alternatives));
    }

    private static DraftComputation.Alternative when(String precondition) {
        return new DraftComputation.Alternative(precondition, "[Amount]");
    }

    /**
     * The measurement. Both single-shape forms are the liveness controls: one unconditional alternative is
     * admitted, and two preconditioned ones are admitted, so the refusal in between is about the MIXING and
     * not about either shape on its own.
     */
    @Test
    void alternativesAreEitherOneUnconditionalOrAllPreconditioned() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("one unconditional", verdict(List.of(when(null))));
        actual.put("all preconditioned",
                verdict(List.of(when("FieldFilled(Reference)"), when("FieldNotFilled(Reference)"))));
        actual.put("mixed: unconditional first",
                verdict(List.of(when(null), when("FieldFilled(Reference)"))));
        actual.put("mixed: preconditioned first",
                verdict(List.of(when("FieldFilled(Reference)"), when(null))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("one unconditional", KernelLaws.ADMITTED);
        expected.put("all preconditioned", KernelLaws.ADMITTED);
        expected.put("mixed: unconditional first", "MVK_ERROR_EMPTY_PRECONDITION");
        expected.put("mixed: preconditioned first", "MVK_ERROR_EMPTY_PRECONDITION");
        Assertions.assertEquals(expected, actual,
                "whether the kernel refuses MIXING the two alternative shapes, and under which code");
    }
}
