package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.policy.Projections;

/**
 * The portable projection's FAIL-CLOSED backstop (proposal §5): a required channel a runner declares
 * unavailable surfaces as {@code unavailableRequired} and the comparison can NEVER read as equality —
 * even when both sides would compare empty. Real packets cannot carry a forged availability (the
 * relational validator refuses the contradiction with the registry), so this unit level is where the
 * backstop stays guarded against registry evolution — and where the M07 mutation
 * (unavailable-treated-as-equal) fires.
 */
class ProjectionFailClosedTest {

    private static Json.Obj channel(String availability) {
        return Json.Obj.of()
                .put("availability", availability)
                .put("granularity", "available".equals(availability) ? "some-granularity" : "notExposed")
                .put("order", "available".equals(availability) ? "observed" : "notApplicable")
                .put("entries", Json.Arr.of());
    }

    private static Json.Obj observation(String withoutErrorsAvailability) {
        Json.Obj channels = Json.Obj.of()
                .put("withoutErrors", channel(withoutErrorsAvailability))
                .put("changedSubset", channel("notExposedByRunner"))
                .put("withErrors", channel("available"))
                .put("cleared", channel("available"))
                .put("formalErrorsInOperands", channel("notExposedByRunner"))
                .put("appliedState", channel("available"));
        return Json.Obj.of().put("channels", channels);
    }

    @Test
    void anUnavailableRequiredChannelIsNeverEquality() {
        Projections.Comparison comparison = Projections.dmkitsPortable("backstop-case",
                observation("available"), observation("notExposedByRunner"), Set.of());
        assertEquals("unavailableRequired", comparison.result(),
                "an unavailable required channel fails CLOSED — never silently equal (both sides are"
                        + " empty here, so ONLY the availability check separates this from equality)");
        assertTrue(comparison.unavailableFields().contains("withoutErrors"),
                "the unavailable field is NAMED in the comparison");
        assertTrue(comparison.channels().stream().anyMatch(c ->
                        "withoutErrors".equals(c.channel()) && "unavailableRequired".equals(c.result())),
                "…and carried per-channel");
    }

    @Test
    void allChannelsAvailableAndEmptyCompareEqual() {
        Projections.Comparison comparison = Projections.dmkitsPortable("backstop-case",
                observation("available"), observation("available"), Set.of());
        assertEquals("equal", comparison.result(),
                "empty AVAILABLE channels are real data and compare equal — the backstop fires on"
                        + " availability, never on emptiness");
    }
}
