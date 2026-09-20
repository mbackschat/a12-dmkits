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
 * KERNEL-SEMANTICS §14 — {@code MVK_ERROR_INVALID_LANGUAGE_FEATURE}: which constructs a computation
 * precondition may not use.
 *
 * <p>The code had <b>no test anywhere in the estate</b> while {@code Computation.Builder} refused on it, and
 * the ledger bucketed it {@code kernel-delegated} — "representable, F4 arbitrates" — which the refusal
 * contradicts. A client-side refusal may be no broader than its measured basis, so the basis is measured
 * here and the bucket corrected.
 *
 * <p>It stayed invisible because the refusal cites its code in a string handed to a local {@code fail(...)}
 * helper rather than in a {@code throw new …Exception(} of its own, which is the shape
 * {@code ClientRefusalKernelBasisCensusTest} scans for.
 *
 * <p>The illegal shape is unauthorable through the typed builder — that IS the refusal under test — so the
 * draft is written directly, the layer at which a computation is already rendered text.
 */
class ComputationLanguageFeatureLawsTest {

    private static final String INVALID_FEATURE = "MVK_ERROR_INVALID_LANGUAGE_FEATURE";

    private static final String MODEL = DocumentModelDraft.named("computation-language-feature").locale("en_US")
            .rootGroup("Ledger", l -> l
                    .field("Reference", FieldKind.STRING)
                    .numberField("Amount", n -> n.min("0").max("1000000"))
                    .numberField("Total", n -> n.min("0").max("1000000"))
                    .group("Entries", e -> e.repeatable(20).indexField("EntryKey")
                            .field("EntryKey", FieldKind.STRING)
                            .field("Code", FieldKind.STRING)))
            .build().toJson();

    /** One computation into {@code /Ledger/Total}, varying only the precondition text. */
    private static String verdict(String precondition) {
        return KernelLaws.computationVerdict(MODEL, new DraftComputation("/Ledger", "TotalProbe", "../Total",
                null, List.of(new DraftComputation.Alternative(precondition, "[Amount]"))));
    }

    /**
     * The measurement, with its liveness control first: an ordinary presence precondition on the same
     * computation is admitted, so a refusal below is about the construct and not about the computation, the
     * operand, or the declaring group.
     *
     * <p><b>The code's measured denominator is {@code CustomCondition} alone.</b> The ledger listed
     * {@code RepetitionNotUnique} under the same sentence, and RNU IS refused here — but by the wildcard
     * gates, in both spellings, so it has no admitted form in a computation precondition on this topology
     * and never reaches the language-feature gate. Outcome right, code wrong: a client-side refusal written
     * from that sentence would have quoted a code the kernel does not report for it.
     */
    @Test
    void aPreconditionMayNotUseACustomCondition() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("liveness: an ordinary presence precondition", verdict("FieldFilled(Reference)"));
        actual.put("CustomCondition", verdict("CustomCondition SomeCheck"));
        actual.put("CustomCondition conjoined with a legal one",
                verdict("FieldFilled(Reference) And CustomCondition SomeCheck"));
        actual.put("RepetitionNotUnique, starred", verdict("RepetitionNotUnique(/Ledger/Entries*/Code)"));
        actual.put("RepetitionNotUnique, unstarred", verdict("RepetitionNotUnique(/Ledger/Entries/Code)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("liveness: an ordinary presence precondition", KernelLaws.ADMITTED);
        expected.put("CustomCondition", INVALID_FEATURE);
        expected.put("CustomCondition conjoined with a legal one", INVALID_FEATURE);
        // Refused, but one gate earlier: RNU has no admitted form in this position at all.
        expected.put("RepetitionNotUnique, starred", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("RepetitionNotUnique, unstarred", "MVK_NO_WILDCARD");
        Assertions.assertEquals(expected, actual,
                "which constructs the kernel bars from a computation precondition, and under which code");
    }
}
