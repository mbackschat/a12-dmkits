package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.List;

import io.github.mbackschat.a12.dm.rulekit.dsl.ToleranceBand;

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

    public record Alternative(String precondition, String operation, ToleranceBand toleranceRangeOp) {

        public Alternative(String precondition, String operation) {
            this(precondition, operation, null);
        }

        public static Builder builder(String operation) {
            return new Builder(operation);
        }

        public static final class Builder {
            private final String operation;
            private String precondition;
            private ToleranceBand toleranceRangeOp;

            private Builder(String operation) {
                this.operation = operation;
            }

            public Builder precondition(String precondition) {
                this.precondition = precondition;
                return this;
            }

            public Builder toleranceRangeOp(ToleranceBand toleranceRangeOp) {
                this.toleranceRangeOp = toleranceRangeOp;
                return this;
            }

            public Alternative build() {
                return new Alternative(precondition, operation, toleranceRangeOp);
            }
        }
    }
}
