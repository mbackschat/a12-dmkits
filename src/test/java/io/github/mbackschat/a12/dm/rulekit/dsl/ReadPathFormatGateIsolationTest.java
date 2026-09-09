package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Checkout;
import io.github.mbackschat.a12.dm.SourceText;

/**
 * The format gate must stay off the INBOUND READ path. {@code :adapter}'s {@code ConditionConverter} rebuilds
 * a stored model's conditions through rulekit, so a refusal it reaches makes a kernel-legal model
 * <b>unreadable</b> rather than merely unauthorable — the sharper half of the standing rule, and the shape
 * that already shipped once as a kind-keyed gate (API-FINDINGS 127/130).
 *
 * <p>Today it holds because the converter routes through the TYPED factories, whose operands were issued by
 * {@link BoundModel} and so already fit, while the format gate lives only on the family factories a bare
 * {@code Calculation} reaches. That is a property of who calls what, not of the types, so nothing else stops
 * it changing: this guard is the enforcement locus for it.
 *
 * <p>It scans for the call rather than asserting a runtime outcome because the failure it prevents is a legal
 * model becoming unreadable, which has no cheap runtime witness — you would need the very model nobody
 * thought to write down.
 */
class ReadPathFormatGateIsolationTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /**
     * The scanned estate is {@code :adapter} ALONE, and the boundary is deliberate rather than an omission.
     *
     * <p>{@code :interpreter-bridge} is the second {@code ConditionReadBack} provider and it DOES reach these
     * factories on purpose: its own contract is that "the mapping is the validation" and names a rejection as
     * one of three legitimate outcomes, so a rulekit refusal surfacing there is the design, not a leak.
     * {@code :adapter}'s converter is the opposite — it rebuilds from a kernel-accepted model through the
     * typed handles and must not refuse — which is why it, and only it, is scanned.
     *
     * <p>What protects the bridge is therefore not this guard but the standing rule the gates are held to:
     * never broader than the measured basis, established PER GATE by the denominator table each one lands with.
     * {@code ClientRefusalKernelBasisCensusTest} is not that protection and its own doc says so — it answers
     * only whether a code was ever observed at the kernel, not whether a gate's basis covers every carrier it
     * refuses. Widening this scan to the bridge would fail on a call the design requires.
     */
    private static final String READ_PATH = "adapter/src/main";

    /**
     * The factories that apply a declared-format gate. Reaching any of them from the read path is the defect.
     *
     * <p>The {@code Dsl.} pair resolves an opaque expression and so keeps its model there; every other
     * promoted contextual gate lives on {@code BoundModel} (the settled receiver — API-FINDINGS AF148), so the
     * list has to name those too, or it would guard a shrinking minority of the gated surface. They are matched
     * on the call suffix because the receiver at a call site is a local variable, not the type name; each name
     * is unique in the kits, and {@code dateRangeOf}/{@code dateOf} are deliberately NOT overloads of
     * {@code dateRange}/{@code date} so that this scan can tell the checked form from the plain accessor.
     *
     * <p><b>The base-year gates are deliberately absent, and cannot be added in this form.</b> A name-substring
     * scan for {@code .baseYear(} also matches {@code document.baseYear()} in the converter's configuration
     * projector, an unrelated read of a model property. What holds that family instead is the measurement: the
     * {@code Dsl} route the converter uses is ungated by construction, and
     * {@code dsl.BaseYearDeclarationGateTest} pins that it stays so. Said out loud, because an unexplained
     * omission from a list whose own doc demands completeness reads as an oversight.
     */
    private static final List<String> GATED_FACTORIES =
            List.of("Dsl.dateExtraction", "Dsl.dateTimePart",
                    ".rangesEqual(", ".rangesNotEqual(", ".dateRangeOf(", ".dateOf(");

    /**
     * The scan predicate, extracted so the controls below exercise the real thing. Inline, the positive control
     * could only restate its own literal and would not notice the loop changing.
     */
    private static boolean reaches(String text, String factory) {
        return text.contains(factory);
    }

    @Test
    void theInboundConverterDoesNotReachTheFormatGatedFactories() throws IOException {
        List<String> reached = new ArrayList<>();
        int scanned = 0;
        for (Path source : CHECKOUT.tracked(READ_PATH)) {
            String name = source.getFileName().toString();
            if (!name.endsWith(".java") && !name.endsWith(".kt")) {
                continue;
            }
            scanned++;
            String text = SourceText.uncommented(source);
            for (String factory : GATED_FACTORIES) {
                if (reaches(text, factory)) {
                    reached.add(CHECKOUT.relative(source) + " calls " + factory);
                }
            }
        }

        assertTrue(scanned > 0, "scanned no read-path source, so this guard proved nothing");
        assertEquals(List.of(), reached, () -> "the inbound read path now reaches the declared-format gate. A "
                + "refusal there does not merely block authoring — it makes a stored, kernel-LEGAL model "
                + "unreadable, because the converter rebuilds every condition through these same objects. If "
                + "this is deliberate, it needs a measurement that the kernel refuses each such model on LOAD, "
                + "not only on authoring:\n  " + String.join("\n  ", reached));
    }

    /**
     * The scan must be able to see the calls it hunts, or its silence proves nothing — and one planted call
     * per ENTRY, because an {@code anyMatch} over the list is satisfied by a single recognized name and would
     * leave every other entry unproven.
     */
    @Test
    void theScannerWouldSeeEveryGatedCall() {
        var planted = Map.of(
                "Dsl.dateExtraction", "var x = Dsl.dateExtraction(model, unit, source);",
                "Dsl.dateTimePart", "var x = Dsl.dateTimePart(model, unit, source);",
                ".rangesEqual(", "return model.rangesEqual(left, right);",
                ".rangesNotEqual(", "return model.rangesNotEqual(left, right);",
                ".dateRangeOf(", "return model.dateRangeOf(start, end);",
                ".dateOf(", "return model.dateOf(day, month, year);");

        assertEquals(Set.copyOf(GATED_FACTORIES), planted.keySet(),
                "every gated factory needs its own planted call, or the entries without one are unproven");
        planted.forEach((factory, call) -> assertTrue(reaches(call, factory),
                () -> "the scanner no longer recognizes the call it exists to find: " + factory));
    }

    /** The legitimate read-path routes, including the near-miss the checked names must not swallow. */
    @Test
    void theScannerIgnoresTheLegitimateRoutes() {
        for (String legitimate : List.of(
                "var x = Dsl.yearFromDate(date);",
                "var r = model.dateRange(path);",
                "var r = Dsl.dateRange(start, end);",
                "var d = model.date(path);",
                "return left.eq(right);")) {
            assertTrue(GATED_FACTORIES.stream().noneMatch(g -> reaches(legitimate, g)),
                    () -> "the read path's legitimate route is flagged: " + legitimate);
        }
    }
}
