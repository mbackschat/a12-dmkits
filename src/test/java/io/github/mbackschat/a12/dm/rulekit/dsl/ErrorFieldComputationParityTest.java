package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * D10: the two "point at a field by typed handle" surfaces — {@code Rule.Builder.errorField(...)} and
 * {@code BoundModel.computation(...)} — must accept the <em>same</em> set of typed refs. errorField was
 * missing the temporal refs; computation carried only Number/String/Field. This locks the parity so a
 * ref added to one but not the other fails the build.
 */
class ErrorFieldComputationParityTest {

    private static Set<Class<?>> singleParamTypes(Class<?> owner, String method) {
        return Arrays.stream(owner.getDeclaredMethods())
                .filter(m -> m.getName().equals(method) && m.getParameterCount() == 1)
                .map(m -> m.getParameterTypes()[0])
                .collect(Collectors.toSet());
    }

    @Test
    void errorFieldAndComputationAcceptTheSameTypedRefs() {
        Set<Class<?>> errorFieldRefs = singleParamTypes(Rule.Builder.class, "errorField");
        Set<Class<?>> computationRefs = singleParamTypes(BoundModel.class, "computation");
        assertEquals(errorFieldRefs, computationRefs,
                "errorField and computation must offer the same typed-ref overloads (D10 parity)");
    }

    @Test
    void theParitySetCoversEveryTypedFieldRef() {
        Set<Class<?>> refs = singleParamTypes(Rule.Builder.class, "errorField");
        for (Class<?> expected : Set.of(FieldRef.class, NumberRef.class, StringRef.class, EnumRef.class,
                BoolRef.class, ConfirmRef.class, DateRef.class, TimeRef.class, DateTimeRef.class,
                DateFragmentRef.class)) {
            assertTrue(refs.contains(expected), () -> "missing errorField/computation overload for " + expected);
        }
    }
}
