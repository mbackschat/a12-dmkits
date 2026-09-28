package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import io.github.mbackschat.a12.dm.core.EntityPath;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
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
    @Test
    void repetitionProvenanceSurvivesEveryOperandWrapper() {
        var model = BoundModel.ofJson(DocumentModelDraft.named("order-read-provenance").locale("en_US")
                .rootGroup("Order", r -> r.numberField("Total", n -> { })
                        .group("Packages", g -> g.repeatable(4)
                                .numberField("Amount", n -> { })
                                .group("Items", h -> h.repeatable(5).numberField("Price", n -> { }))))
                .build().toJson());
        var order = model.groupAt("/Order").orElseThrow();
        var fixed = order.field("Total");
        var repeating = order.field("Packages/Amount");
        var partial = order.flatten("Packages/Items*/Price");
        var full = order.flatten("Packages*/Items*/Price");
        for (FieldRef field : List.of(repeating, repeating.absolute(), repeating.outer(), WEIGHT)) {
            assertEquals(true, OperandRead.mayRefuse(field), field.toString());
            assertEquals(true, OperandRead.mayRefuse(field.value()), field.toString());
            assertEquals(true, OperandRead.mayRefuse(field.firstFilledOperand()), field.toString());
        }
        for (DuplicateOperand operand : List.of(fixed, fixed.absolute(), fixed.value(), fixed.firstFilledOperand(),
                full, full.absolute(), full.firstFilledOperand())) {
            assertEquals(false, OperandRead.mayRefuse(operand), operand.toString());
        }
        for (DuplicateOperand operand : List.of(partial, partial.absolute(), partial.firstFilledOperand(),
                full.outer(), fixed.outer())) {
            assertEquals(true, OperandRead.mayRefuse(operand), operand.toString());
        }
        assertEquals(fixed.repeatingAncestors(), fixed.outer().repeatingAncestors());
        assertEquals(repeating.repeatingAncestors(), repeating.outer().repeatingAncestors());
        var refusal = assertThrows(KernelCodeRefusal.class, () -> Dsl.sum(fixed, fixed));
        assertEquals("MVK_DUPLICATE_PARAM1", refusal.code().name());
    }

    @Test
    void suppliedRepetitionProvenanceIsImmutableAndOldConstructorsRemainUnknown() {
        var path = EntityPath.of("/Order/Items");
        var ancestors = new HashSet<>(Set.of(path));
        var field = new FieldRef(EntityPath.of("/Order/Items/Price"), ValueKind.NUMBER,
                PathStyle.CANONICAL, false, Optional.of(ancestors));
        ancestors.clear();
        assertEquals(Set.of(path), field.repeatingAncestors().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> field.repeatingAncestors().orElseThrow().clear());
        for (var legacy : List.of(new FieldRef(field.path(), field.kind(), PathStyle.ABSOLUTE, false),
                new FieldRef(field.path().value(), field.kind()),
                new FieldRef(field.path().value(), field.kind(), PathStyle.CANONICAL))) {
            assertEquals(Optional.empty(), legacy.repeatingAncestors());
            assertEquals(true, OperandRead.mayRefuse(legacy));
        }
    }

}
