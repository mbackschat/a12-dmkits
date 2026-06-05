package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.List;

/**
 * A computation rendered to its DM-JSON slot texts, ready for ground-truth checking — the
 * computation twin of {@link DraftRule}. An alternative's {@code precondition} is {@code null} for
 * the single-unconditional form (the kernel rejects mixing — {@code MVK_ERROR_EMPTY_PRECONDITION}).
 */
public record DraftComputation(
        String groupPath,
        String name,
        String computedFieldRelPath,
        String commonPrecondition,
        List<Alternative> alternatives) {

    public DraftComputation {
        alternatives = List.copyOf(alternatives);
    }

    public record Alternative(String precondition, String operation) {
    }
}
