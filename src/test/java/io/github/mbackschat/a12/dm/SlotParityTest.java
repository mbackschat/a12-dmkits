package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.model.api.IComputation;
import com.mgmtp.a12.kernel.md.model.api.IRule;

/**
 * Instrument 1 of the constraint-mining discipline: every accessor the kernel's public
 * {@code IRule}/{@code IComputation} declare is either SUPPORTED by rulekit's authoring objects or
 * explicitly DEFERRED with a reason, and every classified slot is one the kernel still declares — a kernel
 * upgrade adding or removing a slot fails here, proactively.
 */
class SlotParityTest {

    private static final Map<String, String> RULE_SLOTS = Map.of(
            "getErrorEntity", "SUPPORTED: Rule.errorEntity (a field or a group) → computed errorEntityRelPath",
            "getErrorCode", "SUPPORTED: Rule.code",
            "getErrorCondition", "SUPPORTED: Rule.when(Condition) → render",
            "getSeverity", "SUPPORTED: Rule.severity",
            "getErrorMessage", "SUPPORTED: Rule.messages (locale-law checked)",
            "getGuiPatterns", "DEFERRED: UI concern, no authoring demand yet");

    private static final Map<String, String> COMPUTATION_SLOTS = Map.of(
            "getComputedField", "SUPPORTED: Computation.of(field) → computed computedFieldRelPath",
            "getComputationAlternatives", "SUPPORTED: when/calc alternatives",
            "getCommonPrecondition", "SUPPORTED: Computation.commonPrecondition",
            "getErrorMessage", "SUPPORTED: Computation.messages (locale-law checked)",
            "getErrorCodesToSuppress", "SUPPORTED: Computation.Builder.errorCodesToSuppress (only DEC_PLACES is suppressible)",
            "getRoundingMode", "SUPPORTED: Computation.Builder.roundingMode, judged with each alternative's band (KF297)");

    @Test
    void everyPublicRuleSlotIsClassified() {
        assertClassified(IRule.class, RULE_SLOTS);
    }

    @Test
    void everyPublicComputationSlotIsClassified() {
        assertClassified(IComputation.class, COMPUTATION_SLOTS);
    }

    private static void assertClassified(Class<?> api, Map<String, String> slots) {
        Set<String> declared = new LinkedHashSet<>();
        for (Method method : api.getDeclaredMethods()) {
            if (!method.isSynthetic() && method.getName().startsWith("get")) {
                declared.add(method.getName());
            }
        }
        Set<String> unclassified = new LinkedHashSet<>(declared);
        unclassified.removeAll(slots.keySet());
        assertTrue(unclassified.isEmpty(), () -> api.getSimpleName()
                + " declares unclassified slots (new in this kernel version? classify them): " + unclassified);
        Set<String> retired = new LinkedHashSet<>(slots.keySet());
        retired.removeAll(declared);
        assertTrue(retired.isEmpty(), () -> api.getSimpleName()
                + " no longer declares these classified slots (retired in this kernel version? drop them): " + retired);
    }
}
