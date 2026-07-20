package io.github.mbackschat.a12.dm.adapter.profile;

/** Accumulates explicit capacity states into the profile's bounded distribution and cause histogram. */
final class CapacityProfileBuilder {

    private final LongDistribution.Builder bounded = LongDistribution.builder();
    private final LongHistogram.Builder unbounded = LongHistogram.builder();

    CapacityProfileBuilder add(Capacity capacity) {
        switch (capacity) {
            case Capacity.Bounded value -> bounded.add(value.value());
            case Capacity.Unbounded value -> unbounded.add(value.cause().name());
        }
        return this;
    }

    ModelProfile.CapacityProfile build() {
        return new ModelProfile.CapacityProfile(bounded.build(), unbounded.build());
    }
}
