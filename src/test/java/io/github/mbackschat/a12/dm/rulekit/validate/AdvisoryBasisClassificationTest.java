package io.github.mbackschat.a12.dm.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Every LINT advisory's classification must agree with what its own text claims.
 *
 * <p>An advisory that PREDICTS a kernel refusal and one that advises on A12 evaluation semantics mean opposite
 * things when they fire on kernel-legal input: the first is a conformance defect, the second is the advisory
 * working. That difference had no representation, so an external conformance pass counted 15 semantics
 * advisories as misses alongside the one prediction that genuinely was one.
 *
 * <p>This holds the two in step. A code whose text claims the kernel rejects something must be classified as a
 * prediction, and one that does not must not be — so the classification cannot drift from the words, which is
 * exactly how {@code NEG_CONDITION_IN_ITERATION} came to declare intent advice while emitting a kernel claim.
 */
class AdvisoryBasisClassificationTest {

    @Test
    void everyAdvisorysClassificationAgreesWithItsOwnText() {
        var mismatched = new LinkedHashMap<String, String>();
        for (RkCode code : Arrays.stream(RkCode.values())
                .filter(c -> c.source() == DiagnosticSource.LINT).toList()) {
            String text = (code.meaning() + " " + code.fix()).toLowerCase();
            boolean claimsKernel = text.contains("the kernel rejects") || text.contains("the kernel forbids")
                    || text.contains("the kernel will reject");
            if (claimsKernel != code.predictsKernelRefusal()) {
                mismatched.put(code.code(), claimsKernel
                        ? "its text claims a kernel refusal but it is not classified as a prediction"
                        : "it is classified as a prediction but its text claims no kernel refusal");
            }
        }
        assertEquals(Map.of(), mismatched,
                "a LINT advisory's basis classification must match what its text claims — a prediction firing "
                        + "on kernel-legal input is a defect, a semantics advisory doing so is its purpose");
    }

    /** Both halves are non-empty, so neither arm of the check is vacuous. */
    @Test
    void bothClassificationsArePopulated() {
        var lint = Arrays.stream(RkCode.values()).filter(c -> c.source() == DiagnosticSource.LINT).toList();
        long predictions = lint.stream().filter(RkCode::predictsKernelRefusal).count();
        assertEquals(2, predictions, "the predictions are NEG_CONDITION_IN_ITERATION and "
                + "COMPUTATION_FORBIDDEN_CONSTRUCT; a change here needs the classification revisited");
        org.junit.jupiter.api.Assertions.assertTrue(lint.size() - predictions >= 5,
                "the semantics advisories must stay the majority arm, else this check is measuring nothing");
    }
}
