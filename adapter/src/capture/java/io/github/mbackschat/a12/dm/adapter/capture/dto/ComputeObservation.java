package io.github.mbackschat.a12.dm.adapter.capture.dto;

import java.util.List;

/**
 * One runner's per-case {@code compute-observation-v1} payload (proposal §4): the six public result
 * channels as SEPARATE, multiplicity-preserving, enumeration-order-recording channels. Entries carry the
 * full target cell-instance pointer and — where the model makes it unique — the authored computation
 * declaration; values keep typed and rendered forms separate; per-channel and per-field availability is
 * explicit. Capture never reconstructs one channel from another, labels silence as clean no-value, or
 * manufactures internal no-value/poison state.
 *
 * <p>Built through the {@link Builder} (the standing long-parameter-list rule): a channel left unset
 * defaults to the honest empty {@code available} channel only via an explicit builder call — the builder
 * requires every channel to be set exactly once for a {@code success} observation, so a forgotten channel
 * is a construction error, never a silently-empty observation.
 */
public record ComputeObservation(
        String caseId,
        RunnerId runner,
        RunStatus status,
        String statusDetail,
        Channel<ValueEntry> withoutErrors,
        Channel<ValueEntry> changedSubset,
        Channel<ErrorEntry> withErrors,
        Channel<ClearedEntry> cleared,
        Channel<DiagnosticEntry> formalErrorsInOperands,
        Channel<AppliedEntry> appliedState) {

    /** One channel's observation: its availability, the runner's declared granularity, and the entries in
     *  OBSERVED enumeration order (multiplicity preserved; sorting is a projection concern, never done here). */
    public record Channel<E>(Availability availability, String granularity, List<E> entries) {
        public Channel {
            entries = List.copyOf(entries);
        }

        public static <E> Channel<E> available(String granularity, List<E> entries) {
            return new Channel<>(Availability.AVAILABLE, granularity, entries);
        }

        public static <E> Channel<E> notExposed() {
            return new Channel<>(Availability.NOT_EXPOSED_BY_RUNNER, RunnerFidelity.NOT_EXPOSED, List.of());
        }
    }

    /** A clean computed instance: full target pointer, the declaring computation (when the model makes the
     *  target→declaration mapping unique), and the computed value. */
    public record ValueEntry(String target, String declaredComputation, ObservedValue value) {
    }

    /** An errored computed instance: the ATTEMPTED value is retained exactly as exposed, plus the
     *  structured cause where the runner transports one. */
    public record ErrorEntry(String target, String declaredComputation, ObservedValue attempted,
            CauseObservation cause) {
    }

    /** A cleared field instance (pointer only — the public collection carries no value). */
    public record ClearedEntry(String target, String declaredComputation) {
    }

    /** One operand formal error: code, polarity type, pointer (message text stays out — license + locale). */
    public record DiagnosticEntry(String code, String messageType, String pointer) {
    }

    /** One probed cell's post-application state. */
    public record AppliedEntry(String pointer, AppliedState state) {
    }

    /** The structured cause of an errored instance, where exposed. */
    public record CauseObservation(Availability availability, String code, String messageType,
            String errorPointer) {

        public static CauseObservation of(String code, String messageType, String errorPointer) {
            return new CauseObservation(Availability.AVAILABLE, code, messageType, errorPointer);
        }

        public static CauseObservation notExposed() {
            return new CauseObservation(Availability.NOT_EXPOSED_BY_RUNNER, null, null, null);
        }
    }

    /** A non-success observation: every channel {@link Availability#NOT_APPLICABLE} (the run produced no
     *  semantic output — never serialized as successful-empty). */
    public static ComputeObservation failed(String caseId, RunnerId runner, RunStatus status, String detail) {
        if (status == RunStatus.SUCCESS) {
            throw new IllegalArgumentException("a success observation carries channels — use the Builder");
        }
        Channel<ValueEntry> noValues = new Channel<>(Availability.NOT_APPLICABLE, RunnerFidelity.NOT_EXPOSED, List.of());
        return new ComputeObservation(caseId, runner, status, detail,
                noValues, noValues,
                new Channel<>(Availability.NOT_APPLICABLE, RunnerFidelity.NOT_EXPOSED, List.of()),
                new Channel<>(Availability.NOT_APPLICABLE, RunnerFidelity.NOT_EXPOSED, List.of()),
                new Channel<>(Availability.NOT_APPLICABLE, RunnerFidelity.NOT_EXPOSED, List.of()),
                new Channel<>(Availability.NOT_APPLICABLE, RunnerFidelity.NOT_EXPOSED, List.of()));
    }

    public static Builder success(String caseId, RunnerId runner) {
        return new Builder(caseId, runner);
    }

    /** The success-observation builder — every channel set exactly once, enforced at {@link #build()}. */
    public static final class Builder {
        private final String caseId;
        private final RunnerId runner;
        private Channel<ValueEntry> withoutErrors;
        private Channel<ValueEntry> changedSubset;
        private Channel<ErrorEntry> withErrors;
        private Channel<ClearedEntry> cleared;
        private Channel<DiagnosticEntry> formalErrorsInOperands;
        private Channel<AppliedEntry> appliedState;

        private Builder(String caseId, RunnerId runner) {
            this.caseId = caseId;
            this.runner = runner;
        }

        public Builder withoutErrors(Channel<ValueEntry> channel) {
            this.withoutErrors = channel;
            return this;
        }

        public Builder changedSubset(Channel<ValueEntry> channel) {
            this.changedSubset = channel;
            return this;
        }

        public Builder withErrors(Channel<ErrorEntry> channel) {
            this.withErrors = channel;
            return this;
        }

        public Builder cleared(Channel<ClearedEntry> channel) {
            this.cleared = channel;
            return this;
        }

        public Builder formalErrorsInOperands(Channel<DiagnosticEntry> channel) {
            this.formalErrorsInOperands = channel;
            return this;
        }

        public Builder appliedState(Channel<AppliedEntry> channel) {
            this.appliedState = channel;
            return this;
        }

        public ComputeObservation build() {
            if (withoutErrors == null || changedSubset == null || withErrors == null || cleared == null
                    || formalErrorsInOperands == null || appliedState == null) {
                throw new IllegalStateException("every channel of a success observation must be set explicitly"
                        + " (an unexposed channel is Channel.notExposed(), never an omitted one)");
            }
            return new ComputeObservation(caseId, runner, RunStatus.SUCCESS, null,
                    withoutErrors, changedSubset, withErrors, cleared, formalErrorsInOperands, appliedState);
        }
    }
}
