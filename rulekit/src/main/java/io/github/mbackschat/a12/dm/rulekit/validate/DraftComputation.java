package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.List;

import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionDirectives;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.RoundingMode;
import io.github.mbackschat.a12.dm.rulekit.dsl.ToleranceBand;

/**
 * A computation rendered to its DM-JSON slot texts, ready for ground-truth checking — the
 * computation twin of {@link DraftRule}. An alternative's {@code precondition} is {@code null} for
 * the single-unconditional form (the kernel rejects mixing — {@code MVK_ERROR_EMPTY_PRECONDITION}).
 *
 * <p>{@code roundingMode} is never {@code null}: the draft is a whole re-expression, so an unset mode is
 * {@code EXACT}, and a writer stores the member only when it is not (A12's serializer omits {@code Exact}).
 *
 * <p>{@code additionalParserInformation} is A12's stored preamble text, such as {@code @SuppressWarning(a,b)}, or
 * {@code null} for none; a blank text is none too, as A12's serializer omits it.
 *
 * <p>{@code errorCode} is the code of the validation A12 generates from the computation, or {@code null} for none,
 * in which case A12 codes it by the computation's name; an empty or blank code is kept and refused by A12 as
 * {@code MVK_ERROR_CODE_MISSING} ({@code validate.laws.ComputationErrorCodeLawsTest}).
 */
public record DraftComputation(
        String groupPath,
        String name,
        String computedFieldRelPath,
        String commonPrecondition,
        String errorCode,
        List<Alternative> alternatives,
        String additionalParserInformation,
        RoundingMode roundingMode,
        List<LocalizedText> messages) {

    public DraftComputation {
        alternatives = List.copyOf(alternatives);
        roundingMode = roundingMode == null ? RoundingMode.EXACT : roundingMode;
        messages = List.copyOf(messages);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A builder holding every member of this draft, so a copy that changes some keeps all the others. */
    public Builder toBuilder() {
        return builder().groupPath(groupPath).name(name).computedFieldRelPath(computedFieldRelPath)
                .commonPrecondition(commonPrecondition).errorCode(errorCode).alternatives(alternatives)
                .additionalParserInformation(additionalParserInformation).roundingMode(roundingMode).messages(messages);
    }

    public static final class Builder {
        private String groupPath;
        private String name;
        private String computedFieldRelPath;
        private String commonPrecondition;
        private String errorCode;
        private List<Alternative> alternatives = List.of();
        private String additionalParserInformation;
        private RoundingMode roundingMode = RoundingMode.EXACT;
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

        public Builder errorCode(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public Builder alternatives(List<Alternative> alternatives) {
            this.alternatives = alternatives == null ? List.of() : alternatives;
            return this;
        }

        public Builder additionalParserInformation(String additionalParserInformation) {
            this.additionalParserInformation = additionalParserInformation;
            return this;
        }

        /**
         * Stores the preamble suppressing {@code codes}, spelled in the model's condition {@code language}; an empty
         * list stores none, since A12 refuses an empty preamble. The codes are passed through unjudged.
         */
        public Builder suppressWarnings(ConditionLanguage language, List<String> codes) {
            this.additionalParserInformation = codes.isEmpty() ? null
                    : ConditionDirectives.suppressionPreamble(language, codes);
            return this;
        }

        public Builder roundingMode(RoundingMode roundingMode) {
            this.roundingMode = roundingMode;
            return this;
        }

        public Builder messages(List<LocalizedText> messages) {
            this.messages = messages == null ? List.of() : messages;
            return this;
        }

        public DraftComputation build() {
            return new DraftComputation(groupPath, name, computedFieldRelPath, commonPrecondition, errorCode,
                    alternatives, additionalParserInformation, roundingMode, messages);
        }
    }

    /** Message- and preamble-free form; the validator supplies a placeholder per declared locale. */
    public DraftComputation(String groupPath, String name, String computedFieldRelPath,
            String commonPrecondition, List<Alternative> alternatives) {
        this(groupPath, name, computedFieldRelPath, commonPrecondition, null, alternatives, null, RoundingMode.EXACT,
                List.of());
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
