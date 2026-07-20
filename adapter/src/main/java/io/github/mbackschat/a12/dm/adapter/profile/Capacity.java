package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.Objects;

sealed interface Capacity permits Capacity.Bounded, Capacity.Unbounded {

    enum Cause {
        DECLARED,
        OVERFLOW
    }

    record Bounded(long value) implements Capacity {

        public Bounded {
            if (value < 0) {
                throw new IllegalArgumentException("capacity must be non-negative: " + value);
            }
        }
    }

    record Unbounded(Cause cause) implements Capacity {

        public Unbounded {
            Objects.requireNonNull(cause, "cause");
        }
    }

    default Capacity multiply(Capacity other) {
        Objects.requireNonNull(other, "other");
        return switch (this) {
            case Bounded left -> switch (other) {
                case Bounded right -> multiply(left, right);
                case Unbounded right -> right;
            };
            case Unbounded left -> switch (other) {
                case Bounded ignored -> left;
                case Unbounded right -> combine(left, right);
            };
        };
    }

    default Capacity max(Capacity other) {
        Objects.requireNonNull(other, "other");
        return switch (this) {
            case Bounded left -> switch (other) {
                case Bounded right -> left.value() >= right.value() ? left : right;
                case Unbounded right -> right;
            };
            case Unbounded left -> switch (other) {
                case Bounded ignored -> left;
                case Unbounded right -> combine(left, right);
            };
        };
    }

    private static Capacity multiply(Bounded left, Bounded right) {
        if (left.value() != 0 && right.value() > Long.MAX_VALUE / left.value()) {
            return new Unbounded(Cause.OVERFLOW);
        }
        return new Bounded(left.value() * right.value());
    }

    private static Unbounded combine(Unbounded left, Unbounded right) {
        return switch (left.cause()) {
            case DECLARED -> left;
            case OVERFLOW -> switch (right.cause()) {
                case DECLARED -> right;
                case OVERFLOW -> left;
            };
        };
    }
}
