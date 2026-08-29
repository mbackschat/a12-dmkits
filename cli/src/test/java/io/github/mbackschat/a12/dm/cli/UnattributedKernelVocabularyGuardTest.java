package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The guard on {@link CliTestSupport#run} has to be proven to FIRE, or it is decoration: it runs inside every
 * in-process CLI invocation in the estate, so a version of it that silently never trips would look exactly
 * like a clean suite. Both directions are asserted here.
 *
 * <p>The invariant it enforces: an {@code MVK_*} code — the KERNEL's vocabulary — must never reach stderr
 * without an envelope beside it to say who decided. Carrying the code is fine; carrying it unattributed is
 * what makes a local refusal indistinguishable from a kernel verdict.
 */
class UnattributedKernelVocabularyGuardTest {

    private static final String[] ARGS = {"-m", "m.json", "patterns", "tiered-amount"};

    private static AssertionError fires(CliTestSupport.Result result) {
        return assertThrows(AssertionError.class,
                () -> CliTestSupport.refuseUnattributedKernelVocabulary(ARGS, result));
    }

    private static void silent(String[] args, CliTestSupport.Result result) {
        assertDoesNotThrow(() -> CliTestSupport.refuseUnattributedKernelVocabulary(args, result));
    }

    /** The defect itself: the shape `patterns` shipped until 2026-08-30 — a bare MVK line, empty stdout. */
    @Test
    void itFiresOnAnUnattributedKernelCodeOnStderr() {
        AssertionError refusal = fires(new CliTestSupport.Result(2, "",
                "cannot scaffold: the computed field must not be referenced in a precondition "
                        + "(MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD)"));
        assertTrue(refusal.getMessage().contains("MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD"),
                () -> "the message names the offending token: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("rejectPrecheck"),
                () -> "and names the remedy: " + refusal.getMessage());
    }

    /** An unknown future code is caught the same way — the guard reads the SHAPE, not a fixed inventory. */
    @Test
    void itFiresOnACodeThatIsNotInTodaysKernelInventory() {
        fires(new CliTestSupport.Result(2, "", "refused (MVK_SOME_FUTURE_CLASS_NOBODY_HAS_MINTED)"));
    }

    /** The fixed shape: the same code, published with a source. This is what the remedy produces. */
    @Test
    void itIsSilentWhenAnEnvelopeAttributesTheCode() {
        silent(ARGS, new CliTestSupport.Result(1,
                """
                { "outcome": "rejected",
                  "diagnostics": [ { "source": "PRECHECK",
                                     "code": "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD" } ] }""",
                ""));
    }

    /**
     * Help must stay free to document the reject it prevents — `--suggest-error-field`'s own help text names
     * {@code MVK_ERROR_FIELD_NOT_REFERENCED}, and picocli prints help to stderr.
     */
    @Test
    void itIsSilentOnUsageText() {
        silent(ARGS, new CliTestSupport.Result(2, "",
                "Missing required option: '--condition=<DSL>'\nUsage: dmtool rule check [-hV]\n"
                        + "      --suggest-error-field  … avoids the MVK_ERROR_FIELD_NOT_REFERENCED reject"));
    }

    /** The one verb whose contract IS explaining the vocabulary; naming codes is its job, not a verdict. */
    @Test
    void itIsSilentForTheDiagnosticsVerb() {
        silent(new String[] {"diagnostics", "RK_NOPE"}, new CliTestSupport.Result(2, "",
                "no diagnostic code 'RK_NOPE' — a structural one (e.g. MVK_ERROR_FIELD_NOT_IN_RULEGROUP) "
                        + "is explained here"));
    }

    /** Ordinary prose with no kernel code is none of this guard's business. */
    @Test
    void itIsSilentOnAPlainRefusalCarryingNoKernelCode() {
        silent(ARGS, new CliTestSupport.Result(2, "", "flags mode needs --group + --name + (--kind | --typedef)"));
    }
}
