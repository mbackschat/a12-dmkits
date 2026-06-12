package io.github.mbackschat.a12.dm.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The {@code RK_*} registry is well-formed (CLI-CONFORMANCE-SPEC): every code carries a meaning + a fix,
 * its wire string round-trips through {@link RkCode#byCode}, and the lookup rejects non-RK / unknown codes.
 * This is the single-source guarantee's floor; the bidirectional source-scan parity guard rides on it (#23).
 */
class RkCodeTest {

    @Test
    void everyCodeIsCataloguedWithAMeaningAndFix() {
        for (RkCode c : RkCode.values()) {
            assertTrue(c.code().startsWith("RK_"), () -> c + " code must be RK_-prefixed: " + c.code());
            assertFalse(c.meaning().isBlank(), () -> c + " must carry a meaning");
            assertFalse(c.fix().isBlank(), () -> c + " must carry a fix");
            assertEquals(c, RkCode.byCode(c.code()).orElseThrow(),
                    () -> c.code() + " must round-trip through byCode");
        }
    }

    @Test
    void byCodeRejectsNonRkAndUnknownCodes() {
        assertTrue(RkCode.byCode("MVK_NO_SUCH_FIELD").isEmpty(), "MVK_* is a kernel code, not in the RK_ registry");
        assertTrue(RkCode.byCode("RK_TOTALLY_MADE_UP").isEmpty(), "an unknown RK_ code does not resolve");
        assertTrue(RkCode.byCode("NO_SUCH_FIELD").isEmpty(), "lookup expects the RK_ prefix");
        assertTrue(RkCode.byCode(null).isEmpty());
    }
}
