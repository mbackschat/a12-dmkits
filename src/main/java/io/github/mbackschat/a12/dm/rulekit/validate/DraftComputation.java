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
        List<Alternative> alternatives,
        List<String> errorCodesToSuppress) {

    public DraftComputation {
        alternatives = List.copyOf(alternatives);
        errorCodesToSuppress = List.copyOf(errorCodesToSuppress);
    }

    /** No suppressions — the common case; keeps the pre-{@code errorCodesToSuppress} call sites unchanged. */
    public DraftComputation(String groupPath, String name, String computedFieldRelPath,
            String commonPrecondition, List<Alternative> alternatives) {
        this(groupPath, name, computedFieldRelPath, commonPrecondition, alternatives, List.of());
    }

    public record Alternative(String precondition, String operation) {
    }
}
