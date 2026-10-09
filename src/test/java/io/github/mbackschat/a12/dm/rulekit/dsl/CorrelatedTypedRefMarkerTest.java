package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * A typed reference marked with {@code outer()} keeps its {@code $} in every node it builds. The marker used to live
 * only on the typed ref, so a node built from its inner {@code FieldRef} — a presence, a value list, a pattern, a
 * length, a keyed cell, a concatenation — rendered the CANDIDATE's field inside a filter: a valid rule reading
 * something else.
 *
 * <p>Each row renders against the rule's own group and must name the marked field; whether A12 admits the marker in
 * that slot is the kernel's call, measured in {@code validate.laws.CorrelatedTypedRefSlotLawsTest}.
 *
 * <p>Own domain (an insurance claims register).
 */
class CorrelatedTypedRefMarkerTest {

    private static final BoundModel M = BoundModel.ofJson(DocumentModelDraft.named("claims-marked-refs").locale("en_US")
            .rootGroup("Register", r -> r
                    .stringField("Key", f -> { })
                    .group("Claims", c -> c.repeatable(5).indexField("Code")
                            .stringField("Code", f -> { })
                            .numberField("Amount", n -> n.fractionDigits(2))
                            .enumField("Plan", e -> e.value("BASIC").value("GOLD").category("Tier", "LOW", "HIGH"))
                            .dateField("FiledOn", d -> d.format("yyyy-MM-dd"))
                            .timeField("FiledAt", t -> t.format("HH:mm:ss"))
                            .dateTimeField("Stamp", t -> t.format("yyyy-MM-dd'T'HH:mm:ss"))
                            .dateRangeField("Cover", d -> d.format("yyyy-MM-dd").separator("/"))
                            .dateFragmentField("Season", f -> f.fragmentFormat("yyyy"))))
            .build().toJson());

    private static final String G = "/Register/Claims";
    private static final StringRef CODE = M.str(G + "/Code").outer();
    private static final NumberRef AMOUNT = M.num(G + "/Amount").outer();
    private static final EnumRef PLAN = M.enumOf(G + "/Plan").outer();
    private static final FieldRef KEY = M.field("/Register/Key");

    private static String render(Object node) {
        DslRenderer renderer = new DslRenderer(ConditionLanguage.EN, G);
        return switch (node) {
            case Condition c -> renderer.render(c);
            case Calculation c -> renderer.render(c);
            default -> throw new IllegalArgumentException(node.toString());
        };
    }

    @Test
    void everyNodeBuiltFromAMarkedRefCarriesTheMarker() {
        Map<String, Supplier<Object>> built = new LinkedHashMap<>();
        built.put("String filled", CODE::filled);
        built.put("String notFilled", CODE::notFilled);
        built.put("String in literals", () -> CODE.in("AB", "CD"));
        built.put("String in fields", () -> CODE.in(M.str(G + "/Code")));
        built.put("String matches", () -> CODE.matches("A.*"));
        built.put("String length", () -> CODE.length().expression());
        built.put("String asString", () -> CODE.asString().plus("-").expression());
        built.put("String eq expression", () -> CODE.eq(M.str(G + "/Code").asString()));
        built.put("String agreesWith", () -> CODE.agreesWith(M.str(G + "/Code")));
        built.put("String forIndex", () -> CODE.forIndex(KEY).expression());
        built.put("String semanticIndex", () -> CODE.semanticIndex(KEY).filled());
        built.put("Number filled", AMOUNT::filled);
        built.put("Number in literals", () -> AMOUNT.in(1, 2));
        built.put("Number agreesWith", () -> AMOUNT.agreesWith(M.num(G + "/Amount")));
        built.put("Number forIndex", () -> AMOUNT.forIndex(KEY).expression());
        built.put("Enum filled", PLAN::filled);
        built.put("Enum in literals", () -> PLAN.in("BASIC"));
        built.put("Enum plus", () -> PLAN.plus("-").expression());
        built.put("Enum category", () -> PLAN.category("Tier").is("LOW"));
        built.put("Enum forIndex", () -> PLAN.forIndex(KEY).expression());
        built.put("Date filled", () -> M.date(G + "/FiledOn").outer().filled());
        built.put("Date forIndex", () -> M.date(G + "/FiledOn").outer().forIndex(KEY).expression());
        built.put("Time filled", () -> M.time(G + "/FiledAt").outer().filled());
        built.put("DateTime filled", () -> M.dateTime(G + "/Stamp").outer().filled());
        built.put("DateRange filled", () -> M.dateRange(G + "/Cover").outer().filled());
        built.put("DateFragment filled", () -> M.dateFragment(G + "/Season").outer().filled());

        Map<String, String> unmarked = new LinkedHashMap<>();
        built.forEach((slot, node) -> {
            String text = render(node.get());
            if (!text.contains("$")) {
                unmarked.put(slot, text);
            }
        });
        assertEquals(Map.of(), unmarked);
    }

    /** One spelling of a marked value read, whichever handle built it, so an authored node equals the one read back. */
    @Test
    void aMarkedValueReadHasOneSpelling() {
        FieldRef code = M.field(G + "/Code");
        assertEquals(new FieldValue(code, true), code.outer().value());
        assertEquals(new FieldValue(code, true), M.str(G + "/Code").outer().asString().expression());
    }
}
