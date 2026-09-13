package io.github.mbackschat.a12.dm.core;

import static io.github.mbackschat.a12.dm.core.KernelPatternPolicy.Rejection.JAVA_SYNTAX;
import static io.github.mbackschat.a12.dm.core.KernelPatternPolicy.Rejection.KERNEL_ADMISSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KernelPatternPolicyTest {

    @Test
    void distinguishesJavaSyntaxFromTheAdditionalKernelBlacklist() {
        assertEquals(JAVA_SYNTAX, KernelPatternPolicy.rejection("(").orElseThrow());
        // `a\*+` is the row that says the gate reads SOURCE TEXT: the pattern it spells is an ordinary
        // escaped star with a quantifier, and the blacklist still sees `*` followed by `+`.
        for (String pattern : new String[] {"(?<=a)b", "a++", "\\p{L}", "a\\*+", "[a-z&&[^bc]]"}) {
            assertEquals(KERNEL_ADMISSION, KernelPatternPolicy.rejection(pattern).orElseThrow(), pattern);
        }
    }

    /**
     * Uppercase {@code \P} is admitted; only lowercase {@code \p} is blacklisted.
     *
     * <p>This policy rejected it until 2026-09-13, on a 2026-07-20 observation that {@code \P{L}+} is
     * refused — which it is, by the possessive alternative catching {@code }} followed by {@code +}, with
     * the escape playing no part. The kernel grid that settles it is
     * {@code rulekit.validate.laws.DeclaredPatternGateLawsTest}, which also holds this policy to it.
     */
    @Test
    void admissionIsNotMisrepresentedAsJavaScriptPortability() {
        for (String admitted : new String[] {"(?i)abc", "\\R", "\\h", "\\P{L}", "[a-z&&c-e]"}) {
            assertTrue(KernelPatternPolicy.rejection(admitted).isEmpty(), admitted);
        }
    }
}
