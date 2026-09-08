package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.List;

import io.github.mbackschat.a12.dm.rulekit.dsl.ToleranceBand;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;

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
        List<String> errorCodesToSuppress,
        List<LocalizedText> messages) {

    public DraftComputation {
        alternatives = List.copyOf(alternatives);
        errorCodesToSuppress = List.copyOf(errorCodesToSuppress);
        messages = List.copyOf(messages);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String groupPath;
        private String name;
        private String computedFieldRelPath;
        private String commonPrecondition;
        private List<Alternative> alternatives = List.of();
        private List<String> errorCodesToSuppress = List.of();
        private List<LocalizedText> messages = List.of();

        private Builder() { }

        public Builder groupPath(String groupPath) {
            this.groupPath = groupPath;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder computedFieldRelPath(String computedFieldRelPath) {
            this.computedFieldRelPath = computedFieldRelPath;
            return this;
        }

        public Builder commonPrecondition(String commonPrecondition) {
            this.commonPrecondition = commonPrecondition;
            return this;
        }

        public Builder alternatives(List<Alternative> alternatives) {
            this.alternatives = alternatives == null ? List.of() : alternatives;
            return this;
        }

        public Builder errorCodesToSuppress(List<String> errorCodesToSuppress) {
            this.errorCodesToSuppress = errorCodesToSuppress == null ? List.of() : errorCodesToSuppress;
            return this;
        }

        public Builder messages(List<LocalizedText> messages) {
            this.messages = messages == null ? List.of() : messages;
            return this;
        }

        public DraftComputation build() {
            return new DraftComputation(groupPath, name, computedFieldRelPath, commonPrecondition,
                    alternatives, errorCodesToSuppress, messages);
        }
    }

    /** Message-free compatibility form; the validator supplies a placeholder per declared locale. */
    public DraftComputation(String groupPath, String name, String computedFieldRelPath,
            String commonPrecondition, List<Alternative> alternatives, List<String> errorCodesToSuppress) {
        this(groupPath, name, computedFieldRelPath, commonPrecondition, alternatives, errorCodesToSuppress, List.of());
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
