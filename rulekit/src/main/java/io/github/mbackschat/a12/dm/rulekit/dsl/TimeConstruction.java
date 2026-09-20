package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code Time(hour?, minute?, second?)} — the kernel's time-of-day construction from a zero-to-three
 * component prefix (its
 * {@code ITimeConstructionOperation}). A {@link Calculation} of {@link ValueKind#TIME}; wrap in a
 * {@link TimeExpr} to compare it. Omitted trailing components default to zero. Each present component renders
 * in argument position — a field bare ({@code Time(H, M, S)}), a constant as a quoted literal
 * ({@code Time("10", "30", "50")}), or a matching time extractor as its call. The kernel rejects a bracketed
 * value ({@code [H]}) here; the renderer therefore preserves the constructor's bare-field syntax.
 */
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;

public record TimeConstruction(List<Calculation> components) implements Calculation {

    /**
     * An extractor component must be admissible in a Time construction AND sit at its own position — the
     * same two-code partition {@link DateConstruction} states, measured on this carrier rather than
     * transferred to it ({@code validate.laws.TemporalExtractorComponentLawsTest}): a TIME-family extractor
     * outside its own slot is {@code MVK_INVALID_DATE_OR_TIME_EXTRACT_OP}, while a DATE-family one — which no
     * Time position accepts — is {@code MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED}.
     *
     * <p>Intrinsic: which extractor occupies which slot is visible from the operands, so it belongs here
     * rather than on the model-bound facade (API-FINDINGS AF153).
     */
    public TimeConstruction {
        components = List.copyOf(components);
        if (components.size() > 3) {
            throw new IllegalArgumentException("Time accepts at most hour, minute, and second components");
        }
        for (int slot = 0; slot < components.size(); slot++) {
            checkComponent(components.get(slot), TIME_POSITIONS.get(slot));
        }
    }

    /** The units a Time position admits at all, in position order; the date family is outside it. */
    private static final List<DateExtraction.Unit> TIME_POSITIONS = List.of(
            DateExtraction.Unit.HOURS, DateExtraction.Unit.MINUTES, DateExtraction.Unit.SECONDS);

    private static void checkComponent(Calculation component, DateExtraction.Unit expected) {
        if (!(component instanceof DateExtraction extraction)) {
            return;
        }
        if (!TIME_POSITIONS.contains(extraction.unit())) {
            throw Refusals.of(KernelCode.MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED,
                    "a Time construction does not admit a " + extraction.unit()
                            + " extractor in any position");
        }
        if (extraction.unit() != expected) {
            throw Refusals.of(KernelCode.MVK_INVALID_DATE_OR_TIME_EXTRACT_OP,
                    "a " + extraction.unit() + " extractor does not belong in the "
                            + expected + " component of a Time construction");
        }
    }

    /** Backward-compatible full three-component construction. */
    public TimeConstruction(Calculation hour, Calculation minute, Calculation second) {
        this(List.of(hour, minute, second));
    }

    @Override
    public ValueKind kind() {
        return ValueKind.TIME;
    }

    public String operatorId() {
        return "Time";
    }
}
