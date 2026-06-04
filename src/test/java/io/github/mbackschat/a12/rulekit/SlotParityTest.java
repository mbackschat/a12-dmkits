package io.github.mbackschat.a12.rulekit;

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
 * explicitly DEFERRED with a reason — a kernel upgrade adding a slot fails here, proactively.
 */
class SlotParityTest {

    private static final Map<String, String> RULE_SLOTS = Map.of(
            "getErrorEntity", "SUPPORTED: Rule.errorField → computed errorEntityRelPath",
            "getErrorCode", "SUPPORTED: Rule.code",
            "getErrorCondition", "SUPPORTED: Rule.when(Condition) → render",
            "getSeverity", "SUPPORTED: Rule.severity",
            "getErrorMessage", "SUPPORTED: Rule.messages (locale-law checked)",
            "getGuiPatterns", "DEFERRED: UI concern, no authoring demand yet",
            "getVariantFilter", "DEFERRED: variant machinery (proposal: variants untouched in Phase 1)",
            "getVariantErrorMessages", "DEFERRED: rides variant support");

    private static final Map<String, String> COMPUTATION_SLOTS = Map.of(
            "getComputedField", "SUPPORTED: Computation.of(field) → computed computedFieldRelPath",
            "getComputationAlternatives", "SUPPORTED: when/calc alternatives",
            "getCommonPrecondition", "SUPPORTED: Computation.commonPrecondition",
            "getErrorMessage", "SUPPORTED: Computation.messages (locale-law checked)",
            "getErrorCodesToSuppress", "DEFERRED: suppression surface not built (only DEC_PLACES is suppressible)",
            "getRoundingMode", "DEFERRED: gap found by this sweep 2026-06-04 — schedule with the rounding operators",
            "getVariantFilter", "DEFERRED: rides variant support");

    @Test
    void everyPublicRuleSlotIsClassified() {
        assertClassified(IRule.class, RULE_SLOTS);
    }

    @Test
    void everyPublicComputationSlotIsClassified() {
        assertClassified(IComputation.class, COMPUTATION_SLOTS);
    }

    private static void assertClassified(Class<?> api, Map<String, String> slots) {
        Set<String> unclassified = new LinkedHashSet<>();
        for (Method method : api.getDeclaredMethods()) {
            if (method.isSynthetic() || !method.getName().startsWith("get")) {
                continue;
            }
            if (!slots.containsKey(method.getName())) {
                unclassified.add(method.getName());
            }
        }
        assertTrue(unclassified.isEmpty(), () -> api.getSimpleName()
                + " declares unclassified slots (new in this kernel version? classify them): " + unclassified);
    }
}
