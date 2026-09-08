package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.HashSet;
import java.util.List;

import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;

/**
 * The kernel's two parameter-duplicate arms, once, for every carrier that holds a list of operands.
 *
 * <p>Both arms run in the order the kernel runs them — exact duplicates first, then containment — because the
 * kernel's shared caller invokes them as two sequential statements and the first throws. So an exact duplicate
 * pre-empts an overlap that is present in the same list, and a carrier calling {@link #rejectDuplicates}
 * reproduces that precedence without having to know it.
 *
 * @see DuplicateOperand for the identity's four components and why a wildcarded operand is exempt from the
 *      exact arm but not from the containment arm
 */
final class DuplicateGate {

    private DuplicateGate() {
    }

    /** Both arms, in the kernel's order. */
    static void rejectDuplicates(String opName, List<? extends DuplicateOperand> operands) {
        rejectExactDuplicates(opName, operands);
        rejectOverlap(opName, operands);
    }

    /** The direct arm — an exact reference may not repeat; a wildcarded operand is exempt. */
    static void rejectExactDuplicates(String opName, List<? extends DuplicateOperand> operands) {
        var seen = new HashSet<String>();
        for (DuplicateOperand operand : operands) {
            var identity = operand.exactIdentity();
            if (identity.isEmpty()) {
                continue;
            }
            if (!seen.add(identity.get())) {
                throw Refusals.of(KernelCode.MVK_DUPLICATE_PARAM1,
                        opName + ": '" + operand.renderedPath() + "' is listed more than once");
            }
        }
    }

    /**
     * The indirect arm — no operand may already be included through another. Wildcards are NOT exempt here,
     * and the modifiers participate: an index-selected ancestor does not contain its plainly-addressed
     * descendant.
     */
    static void rejectOverlap(String opName, List<? extends DuplicateOperand> operands) {
        for (DuplicateOperand child : operands) {
            for (DuplicateOperand candidateParent : operands) {
                if (candidateParent == child) {
                    continue;
                }
                if (child.isStrictlyInside(candidateParent)
                        && child.overlapModifier().equals(candidateParent.overlapModifier())) {
                    throw Refusals.of(KernelCode.MVK_DUPLICATE_PARAM2, opName + ": '" + child.renderedPath()
                            + "' is already included through '" + candidateParent.renderedPath() + "'");
                }
            }
        }
    }
}
