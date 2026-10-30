package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * Every typed computation target and every typed expression reaches a computation without {@code field()} or
 * {@code expression()}: {@link GroupEditor} takes each ref {@link BoundModel#computation} takes, and both computation
 * builders take each typed expression {@link Dsl} returns. The Date case was closed alone on 2026-10-13 ({@link
 * GroupEditorDateComputationTest}); a {@code DateTime(date, time)} differential then hit the same gap one kind over, so
 * both denominators are derived from the surfaces they must match rather than listed.
 *
 * <p>Own domain (an insurance claim's reported date, clock and logged stamp).
 */
class TypedComputationSurfaceTest {

    @Test
    void theEditorTakesEveryRefTheModelComputesInto() {
        Set<String> targets = parameterTypes(BoundModel.class, "computation");
        assertEquals(targets, parameterTypes(GroupEditor.class, "addComputation"), "addComputation");
        assertEquals(targets, parameterTypes(GroupEditor.class, "computation"), "computation");
    }

    /**
     * The typed expression kinds are whatever {@link Dsl}'s factories and the computation targets' own methods return
     * ({@code ConfirmRef.forIndex} is the only producer of a {@code ConfirmExpr}), so a new kind joins here.
     */
    @Test
    void bothBuildersTakeEveryTypedExpression() {
        Stream<Method> producers = Stream.concat(Arrays.stream(Dsl.class.getMethods()),
                Arrays.stream(BoundModel.class.getMethods()).filter(m -> m.getName().equals("computation"))
                        .flatMap(m -> Arrays.stream(m.getParameterTypes()[0].getMethods())));
        Set<String> expected = producers.map(m -> m.getReturnType().getSimpleName())
                .filter(type -> type.endsWith("Expr"))
                .collect(Collectors.toCollection(TreeSet::new));
        expected.add(Calculation.class.getSimpleName());
        assertEquals(expected, parameterTypes(Computation.Builder.class, "calc"), "Builder");
        assertEquals(expected, parameterTypes(Computation.AlternativeBuilder.class, "calc"), "AlternativeBuilder");
    }

    @Test
    void aDateTimeComputationIsAuthoredThroughTypedHandlesAndAdmitted() {
        EditSession session = EditSession.read(DocumentModelDraft.named("claim-stamp").locale("en_US")
                .rootGroup("Claim", c -> c
                        .dateField("Reported", f -> f.format("yyyy-MM-dd"))
                        .timeField("Clock", f -> f.format("HH:mm:ss"))
                        .dateTimeField("Logged", f -> f.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .timeField("LoggedClock", f -> f.format("HH:mm:ss")))
                .build().toJson());
        GroupEditor claim = session.group("/Claim");
        Function<Computation.Builder, Computation> logged = computation -> computation.name("LoggedComp")
                .when(claim.date("Reported").filled()).calc(Dsl.dateTime(claim.date("Reported"), claim.time("Clock")))
                .message(Dsl.en("Check the logged stamp.")).build();
        Function<Computation.Builder, Computation> loggedClock = computation -> computation.name("LoggedClockComp")
                .calc(Dsl.timeFromDateTime(claim.dateTime("Logged")))
                .message(Dsl.en("Check the logged clock.")).build();

        assertEquals("DateTime(/Claim/Reported, /Claim/Clock)", operationOf(claim.computation(claim.dateTime("Logged"), logged)));
        assertEquals("TimeFromDateTime(/Claim/Logged)", operationOf(claim.computation(claim.time("LoggedClock"), loggedClock)));
        claim.addComputation(claim.dateTime("Logged"), logged).addComputation(claim.time("LoggedClock"), loggedClock);
        session.validate();
        assertEquals("/Claim/LoggedClockComp", session.computation("/Claim/LoggedClockComp").name());
    }

    private static String operationOf(Computation built) {
        return new DslRenderer().render(built.alternatives().getFirst().operation());
    }

    private static Set<String> parameterTypes(Class<?> owner, String name) {
        return Arrays.stream(owner.getDeclaredMethods())
                .filter(m -> m.getName().equals(name) && Modifier.isPublic(m.getModifiers()))
                .filter(m -> m.getParameterCount() == 1 || m.getParameterCount() == 2
                        && m.getParameterTypes()[1] == Function.class)
                .map(Method::getParameterTypes).map(types -> types[0].getSimpleName())
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
