package io.github.mbackschat.a12.dm.adapter.capture.dto;

/**
 * The kernel-free shared value vocabulary (proposal §3): exact decimals, strings, booleans, instants, and
 * instant ranges — CLOSED (sealed; an unmappable runner value fails the run as {@code technicalFailure},
 * it never widens this vocabulary silently). Decimals and instants travel as exact strings, never binary
 * floating point.
 */
public sealed interface EvidenceValue {

    /** The closed wire kind discriminator. */
    String kind();

    record StringValue(String value) implements EvidenceValue {
        @Override
        public String kind() {
            return "STRING";
        }
    }

    /** An exact decimal, in plain (non-exponent) string form. */
    record DecimalValue(String plainValue) implements EvidenceValue {
        @Override
        public String kind() {
            return "DECIMAL";
        }
    }

    record BooleanValue(boolean value) implements EvidenceValue {
        @Override
        public String kind() {
            return "BOOLEAN";
        }
    }

    /** An instant, ISO-8601 UTC string form. */
    record InstantValue(String iso) implements EvidenceValue {
        @Override
        public String kind() {
            return "INSTANT";
        }
    }

    /** An instant range (the kernel's date-range value), both bounds ISO-8601 UTC. */
    record InstantRangeValue(String startIso, String endIso) implements EvidenceValue {
        @Override
        public String kind() {
            return "INSTANT_RANGE";
        }
    }
}
