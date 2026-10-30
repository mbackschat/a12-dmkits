package io.github.mbackschat.a12.dm.schemakit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.gate.PortableConsistencyGate;
import io.github.mbackschat.a12.dm.gate.PortableGateRun;

/**
 * The controls for F3-b's arm on {@link SchemaTestSupport#assertKernelAccepts}, which has none of its own.
 *
 * <p><b>Why a whole class for two rows.</b> That arm fires only when A12 and the Kernel-free checker disagree
 * about a model, and across this module's entire import corpus they do not — so the arm is green whether it
 * is armed or disarmed, and a mutant that deletes it survives every one of the other tests. These two rows
 * are what make it falsifiable: one proves the predicate REFUSES when the checker reports an error, the other
 * proves the checker actually examined a real imported model rather than finding nothing it could read.
 *
 * <p>Neither row asserts anything about A12 semantics. That is {@code :consistency}'s business, and restating
 * it here would create a second place that has to agree about the same constraint.
 */
class PortableAcceptanceGateTest {

    @Test
    void theGateRefusesAModelThePortableCheckerReportsAnErrorOn() {
        assertThrows(AssertionError.class,
                () -> SchemaTestSupport.assertPortableCheckerDoesNotRefuse(unresolvableErrorTarget()));
    }

    /**
     * The other way the arm could be green for nothing: a checker that read no declaration would report no
     * finding either. A completed family is the evidence that it looked.
     */
    @Test
    void theCheckerExaminesAnImportedModelRatherThanFindingNothingToRead() {
        PortableGateRun run = PortableConsistencyGate.check(unresolvableErrorTarget());

        assertFalse(run.report().getChecksCompleted().isEmpty(),
                "no family completed, so a silent report would mean nothing: " + run.report().getGaps());
    }

    /**
     * A rule reporting on a field that does not exist — refused by A12 and named by the checker alike. The frame
     * members are complete, so the refusal is the rule's and not the frame's (KERNEL-FINDINGS KF650).
     */
    private static String unresolvableErrorTarget() {
        return """
            {"header":{"id":"orders","modelVersion":"28.4.0","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"orders"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f-code","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Rule","id":"r-code","name":"CodeRequired","Rule":{
                  "errorCondition":"FieldFilled(Code)","errorEntityRelPath":"../NoSuchField",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"the order code"}]}}
              ]}}
             ]}}}
            """;
    }
}
