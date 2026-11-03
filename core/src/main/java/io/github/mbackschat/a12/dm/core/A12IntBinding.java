package io.github.mbackschat.a12.dm.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * How A12's reader binds one model member to a Java {@code Integer} (KERNEL-FINDINGS KF590, KF591, KF593). A primitive
 * {@code int} member cannot hold the "no value" outcome on kernel 31, whose Jackson 3 refuses a null for one, so for
 * such a member {@link A12IntBinding.NoValue} is a value A12 cannot bind ({@link A12Ints#repetitionCount(String)}).
 *
 * <p>Text is read after {@code String.trim}: the empty text and {@code null}, in that exact case, bind no value; any
 * other text binds through {@code Integer.parseInt}, so a decimal digit of any script counts and a fraction or an
 * exponent does not. A JSON number binds when its value lies within {@code int}'s range, and then truncated toward
 * zero, so {@code 2024.7} is 2024. A Boolean, an object and an array never bind. This reader never refuses: a value
 * A12 cannot bind leaves A12 with no model, and the caller decides what that reads as.
 *
 * <p>It calls the JDK's own {@code Integer.parseInt} and {@code BigDecimal}, which is what A12 runs, so it does not
 * take the Kotlin copy in {@code :consistency}: that one reimplements those calls for Node and would make this JVM
 * reader a reimplementation of its own oracle.
 */
sealed interface A12IntBinding {

    record Bound(int value) implements A12IntBinding {}

    record NoValue() implements A12IntBinding {}

    record Unbindable() implements A12IntBinding {}

    static A12IntBinding of(JsonNode member) {
        if (member == null || member.isMissingNode() || member.isNull()) {
            return new NoValue();
        }
        if (member.isTextual()) {
            String text = member.asText().trim();
            if (text.isEmpty() || text.equals("null")) {
                return new NoValue();
            }
            try {
                return new Bound(Integer.parseInt(text));
            } catch (NumberFormatException notAnInt) {
                return new Unbindable();
            }
        }
        if (member.isNumber()) {
            BigDecimal value = member.decimalValue();
            // Kernel 31.1.1 parses through Jackson 3's fast parser, which refuses a scale of Integer.MIN_VALUE that the
            // JDK builds (KERNEL-FINDINGS KF593).
            if (value.scale() == Integer.MIN_VALUE) {
                return new Unbindable();
            }
            boolean fits = value.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) >= 0
                    && value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0;
            return fits ? new Bound(value.intValue()) : new Unbindable();
        }
        return new Unbindable();
    }

    /** The bound value; none when the member binds no value or cannot be bound. */
    default Optional<Integer> bound() {
        return this instanceof Bound(int bound) ? Optional.of(bound) : Optional.empty();
    }
}
