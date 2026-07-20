package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * The public temporal DSL exposes the kernel's exact operand families. The broad sealed
 * {@link TemporalOperand} union remains useful for internal exhaustive dispatch, but accepting it in a
 * factory would make illegal date/time combinations compile.
 */
class TemporalDslTypeSafetyTest {

    private static final Set<String> TEMPORAL_METHODS = Set.of(
            "differenceInDays", "differenceInMonths", "differenceInYears",
            "differenceInHours", "differenceInMinutes", "differenceInSeconds",
            "yearFromDate", "monthFromDate", "dayFromDate", "quarterFromDate",
            "hoursFromTime", "minutesFromTime", "secondsFromTime",
            "addDays", "addMonths", "addYears", "addHours", "addMinutes", "addSeconds");

    @Test
    void legalFamiliesComposeWithTheirPreciseResultTypes() {
        DateExpr date = new DateExpr(new Today());
        TimeExpr time = new TimeExpr(new TimeLiteral("12:34:56"));
        DateTimeExpr dateTime = Dsl.dateTime(date, time);

        assertInstanceOf(NumberExpr.class, Dsl.differenceInDays(date, dateTime));
        assertInstanceOf(NumberExpr.class, Dsl.differenceInMonths(date, date));
        assertInstanceOf(NumberExpr.class, Dsl.differenceInHours(dateTime, dateTime));
        assertInstanceOf(NumberExpr.class, Dsl.yearFromDate(dateTime));
        assertInstanceOf(NumberExpr.class, Dsl.hoursFromTime(time));
        assertInstanceOf(DateExpr.class, Dsl.addDays(date, 1));
        assertInstanceOf(DateTimeExpr.class, Dsl.addDays(dateTime, 1));
        assertInstanceOf(DateTimeExpr.class, Dsl.addHours(dateTime, 1));
    }

    @Test
    void noTemporalFactoryAcceptsTheBroadUnion() {
        for (Method method : Dsl.class.getDeclaredMethods()) {
            if (TEMPORAL_METHODS.contains(method.getName())) {
                assertNotEquals(TemporalOperand.class, Arrays.stream(method.getParameterTypes())
                        .filter(TemporalOperand.class::equals)
                        .findFirst()
                        .orElse(null), method::toGenericString);
            }
        }
    }

    @Test
    void exactOverloadInventoryRejectsSiblingTemporalFamiliesByConstruction() {
        Map<String, Set<String>> actual = Arrays.stream(Dsl.class.getDeclaredMethods())
                .filter(method -> TEMPORAL_METHODS.contains(method.getName()))
                .collect(Collectors.groupingBy(Method::getName, Collectors.mapping(
                        method -> Arrays.stream(method.getParameterTypes())
                                .map(Class::getSimpleName)
                                .collect(Collectors.joining(",")),
                        Collectors.toSet())));

        assertEquals(Set.of(
                "DateOperand,DateOperand", "DateOperand,DateTimeOperand",
                "DateTimeOperand,DateOperand", "DateTimeOperand,DateTimeOperand"),
                actual.get("differenceInDays"));
        for (String name : Set.of("differenceInMonths", "differenceInYears")) {
            assertEquals(Set.of("DateOperand,DateOperand"), actual.get(name), name);
        }
        for (String name : Set.of("differenceInHours", "differenceInMinutes", "differenceInSeconds")) {
            assertEquals(Set.of("DateTimeOperand,DateTimeOperand"), actual.get(name), name);
        }
        for (String name : Set.of("yearFromDate", "monthFromDate", "dayFromDate", "quarterFromDate")) {
            assertEquals(Set.of("DateOperand", "DateTimeOperand"), actual.get(name), name);
        }
        for (String name : Set.of("hoursFromTime", "minutesFromTime", "secondsFromTime")) {
            assertEquals(Set.of("TimeOperand", "DateTimeOperand"), actual.get(name), name);
        }
        assertEquals(Set.of(
                "DateOperand,long", "DateOperand,NumberExpr",
                "DateTimeOperand,long", "DateTimeOperand,NumberExpr"),
                actual.get("addDays"));
        for (String name : Set.of("addMonths", "addYears")) {
            assertEquals(Set.of("DateOperand,long", "DateOperand,NumberExpr"), actual.get(name), name);
        }
        for (String name : Set.of("addHours", "addMinutes", "addSeconds")) {
            assertEquals(Set.of("DateTimeOperand,long", "DateTimeOperand,NumberExpr"), actual.get(name), name);
        }
    }
}
