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
        for (String pattern : new String[] {"(?<=a)b", "a++", "\\P{L}", "[a-z&&[^bc]]"}) {
            assertEquals(KERNEL_ADMISSION, KernelPatternPolicy.rejection(pattern).orElseThrow(), pattern);
        }
    }

    @Test
    void admissionIsNotMisrepresentedAsJavaScriptPortability() {
        for (String admitted : new String[] {"(?i)abc", "\\R", "\\h", "[a-z&&c-e]"}) {
            assertTrue(KernelPatternPolicy.rejection(admitted).isEmpty(), admitted);
        }
    }
}
