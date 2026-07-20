package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable internal result of comparing two current model-performance profiles. */
record ProfileComparison(List<AxisResult> axes) {

    ProfileComparison {
        axes = List.copyOf(Objects.requireNonNull(axes, "axes"));
        var paths = new HashSet<String>();
        for (AxisResult result : axes) {
            Objects.requireNonNull(result, "axis result");
            if (!paths.add(result.axis().path())) {
                throw new IllegalArgumentException(
                        "duplicate profile-comparison axis: " + result.axis().path());
            }
        }
    }

    boolean matches() {
        return axes.stream()
                .noneMatch(result -> result.outcome().verdict() == Verdict.FAIL);
    }

    AxisResult requireAxis(String path) {
        Objects.requireNonNull(path, "path");
        return axes.stream()
                .filter(result -> result.axis().path().equals(path))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown profile-comparison axis: " + path));
    }

    enum Policy {
        EXACT,
        AT_LEAST,
        BUCKET,
        INFORMATIVE
    }

    enum Verdict {
        PASS,
        FAIL,
        INFORMATIVE
    }

    sealed interface Value permits ScalarValue, HistogramValue, DistributionValue {}

    record ScalarValue(long value) implements Value {}

    record HistogramValue(LongHistogram value) implements Value {
        HistogramValue {
            Objects.requireNonNull(value, "value");
        }
    }

    record DistributionValue(LongDistribution value) implements Value {
        DistributionValue {
            Objects.requireNonNull(value, "value");
        }
    }

    record Axis(String path, Policy policy) {
        Axis {
            if (Objects.requireNonNull(path, "path").isBlank()) {
                throw new IllegalArgumentException("profile-comparison path must not be blank");
            }
            Objects.requireNonNull(policy, "policy");
        }
    }

    record Observation(Value target, Value actual) {
        Observation {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(actual, "actual");
            if (target.getClass() != actual.getClass()) {
                throw new IllegalArgumentException(
                        "profile-comparison values must have the same kind");
            }
        }
    }

    record Outcome(Verdict verdict, String rule) {
        Outcome {
            Objects.requireNonNull(verdict, "verdict");
            if (Objects.requireNonNull(rule, "rule").isBlank()) {
                throw new IllegalArgumentException("comparison rule must not be blank");
            }
        }
    }

    record AxisResult(Axis axis, Observation observation, Outcome outcome) {
        AxisResult {
            Objects.requireNonNull(axis, "axis");
            Objects.requireNonNull(observation, "observation");
            Objects.requireNonNull(outcome, "outcome");
            if (axis.policy() == Policy.INFORMATIVE
                    != (outcome.verdict() == Verdict.INFORMATIVE)) {
                throw new IllegalArgumentException(
                        "only informative axes may have an informative verdict");
            }
        }
    }
}
