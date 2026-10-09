package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;

/**
 * Which slots A12 admits a {@code $}-marked typed reference in, inside a filter that also reads the candidate row.
 * Every row is the same rule on a repeatable {@code Claims} with the rule in {@code Claims}; only the slot holding
 * the marked reference varies, so a refusal is the slot's own. A keyed cell has no row here: a filter refuses any
 * semantic index ({@code MVK_SEMANTIC_INDEX_NOT_ALLOWED}), marked or not, which the typed {@code having} already
 * enforces.
 *
 * <p>Own domain (an insurance claims register).
 */
class CorrelatedTypedRefSlotLawsTest {

    private static final String G = "/Register/Claims";
    private static final String REGISTER = DocumentModelDraft.named("claims-marked-slots").locale("en_US")
            .rootGroup("Register", r -> r
                    .group("Claims", c -> c.repeatable(5).indexField("Code")
                            .stringField("Code", f -> { })
                            .stringField("Note", f -> { })
                            .numberField("Units", n -> n.fractionDigits(0))
                            .enumField("Plan", e -> e.value("BASIC").value("GOLD"))
                            .dateField("FiledOn", d -> d.format("yyyy-MM-dd"))))
            .build().toJson();
    private static final BoundModel M = BoundModel.ofJson(REGISTER);
    private static final StringRef CODE = M.str(G + "/Code");
    private static final StringRef NOTE = M.str(G + "/Note");
    private static final NumberRef UNITS = M.num(G + "/Units");
    private static final EnumRef PLAN = M.enumOf(G + "/Plan");

    /** The rule: does ANOTHER claim match this one through [match]? */
    private static String verdict(Condition match) {
        Condition another = Dsl.currentRepetition(M.group(G)).ne(Dsl.currentRepetition(M.group(G), true));
        Condition rule = M.date(G + "/FiledOn").filled().and(Dsl.atLeastOneFieldFilled(
                M.flatten("/Register/Claims*/FiledOn").having(M.date(G + "/FiledOn").filled().and(another).and(match))));
        return KernelLaws.verdictModel(REGISTER, G, "../FiledOn", rule);
    }

    @Test
    void aMarkedTypedReferenceIsAdmittedInEverySlot() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("presence", verdict(NOTE.outer().notFilled().and(NOTE.filled())));
        actual.put("value list of literals", verdict(CODE.outer().in("AB12", "CD34")));
        actual.put("value list of fields", verdict(CODE.outer().in(CODE)));
        actual.put("pattern", verdict(CODE.outer().matches("AB.*")));
        actual.put("length", verdict(CODE.outer().length().eq(CODE.length())));
        actual.put("concatenation", verdict(CODE.outer().asString().plus("-").eq(CODE.asString().plus("-"))));
        actual.put("text against an expression", verdict(CODE.outer().eq(CODE.asString())));
        actual.put("equal or both empty", verdict(CODE.outer().agreesWith(CODE)));
        actual.put("number value list", verdict(UNITS.outer().in(1, 2)));
        actual.put("enumeration value list", verdict(PLAN.outer().in("GOLD")));
        actual.put("enumeration concatenation", verdict(PLAN.outer().plus("-").eq(PLAN.plus("-"))));

        Map<String, String> expected = new LinkedHashMap<>();
        actual.keySet().forEach(slot -> expected.put(slot, KernelLaws.ADMITTED));
        assertEquals(expected, actual);
    }

    /** The marker reaches the kernel: outside a filter the same marked reference is refused, so the rows above cannot
     *  be passing on a marker that vanished before rendering. */
    @Test
    void theMarkerReachesTheKernel() {
        assertEquals("MVK_INVALID_OUTER_ITERATION",
                KernelLaws.verdictModel(REGISTER, G, "../FiledOn", NOTE.outer().filled()));
    }
}
