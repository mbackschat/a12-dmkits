package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * An operand of kind {@code UNKNOWN} — a dangling type reference — is refused by every entity-list carrier
 * WITHOUT a code. No legal model carries one, so no class has ever been measured for it, and the kernel-oracled
 * matrix ({@code validate.laws.EntityListLeaderRouteLawsTest}) cannot pose the question; this is the only
 * control the refusal has.
 */
class EntityListUnknownKindWithheldTest {

    private static final FieldRef UNRESOLVED = new FieldRef("/Probe/Unresolved", ValueKind.UNKNOWN);
    private static final FieldRef WEIGHT = new FieldRef("/Probe/Weight", ValueKind.NUMBER);

    @Test
    void everyCarrierRefusesADanglingTypeUncoded() {
        Map<String, Supplier<?>> carriers = new LinkedHashMap<>();
        carriers.put("Sum", () -> Dsl.sum(WEIGHT, UNRESOLVED));
        carriers.put("MaxValue", () -> Dsl.maxValue(WEIGHT, UNRESOLVED));
        carriers.put("MinValue", () -> Dsl.minValue(WEIGHT, UNRESOLVED));
        carriers.put("MaxValue over dates", () -> Dsl.latestOf(WEIGHT, UNRESOLVED));
        carriers.put("NumberOfDifferentValues", () -> Dsl.numberOfDifferentValues(WEIGHT, UNRESOLVED));

        Map<String, String> actual = new LinkedHashMap<>();
        carriers.forEach((name, build) -> {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, build::get, name);
            actual.put(name, refusal instanceof KernelCodeRefusal coded ? coded.code().name() : "uncoded");
        });
        Map<String, String> expected = new LinkedHashMap<>();
        carriers.keySet().forEach(name -> expected.put(name, "uncoded"));
        assertEquals(expected, actual);
    }
}
