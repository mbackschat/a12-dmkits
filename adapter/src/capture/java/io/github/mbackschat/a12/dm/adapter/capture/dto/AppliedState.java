package io.github.mbackschat.a12.dm.adapter.capture.dto;

/**
 * The exact post-application state of one probed cell after applying the public computation result to a
 * FRESH document copy (proposal §4, channel 6): {@code absent}, {@code present-empty}, and
 * {@code present-value(kind, value)} are kept DISTINCT — never collapsed. A runner whose public read
 * surface cannot separate absent from present-empty reports the honest {@link NoValueIndeterminate},
 * never a guessed state — since the interpreter's {@code cellStateAt} (IF126) every registered runner
 * exposes the distinction, but the state stays in the closed {@code compute-observation-v1} vocabulary.
 */
public sealed interface AppliedState {

    String wire();

    record Absent() implements AppliedState {
        @Override
        public String wire() {
            return "absent";
        }
    }

    record PresentEmpty() implements AppliedState {
        @Override
        public String wire() {
            return "presentEmpty";
        }
    }

    record PresentValue(ObservedValue value) implements AppliedState {
        @Override
        public String wire() {
            return "presentValue";
        }
    }

    /** No value observable at the probe, and the runner cannot distinguish absent from present-empty. */
    record NoValueIndeterminate() implements AppliedState {
        @Override
        public String wire() {
            return "noValueIndeterminate";
        }
    }
}
