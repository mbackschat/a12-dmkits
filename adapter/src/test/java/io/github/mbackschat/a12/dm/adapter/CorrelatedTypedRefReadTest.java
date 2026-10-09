package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * A {@code $}-marked typed reference reads back marked in every slot its own methods build: presence, value lists,
 * pattern, length, concatenation, text comparison, the equal-or-both-empty pair, the number and enumeration
 * forms, a value-list entry, arithmetic, a group-list quantifier and a date component. Each is authored, admitted by the kernel's consistency gate and converted back; the read must equal the
 * authored object, which needs the marker on both sides and one spelling of a marked value read.
 *
 * <p>Own domain (an insurance claims register).
 */
class CorrelatedTypedRefReadTest {

    private static final String G = "/Register/Claims";
    private static final String REGISTER = DocumentModelDraft.named("claims-register-marked-read").locale("en_US")
            .rootGroup("Register", r -> r
                    .group("Claims", c -> c.repeatable(5)
                            .stringField("Code", f -> { })
                            .stringField("Note", f -> { })
                            .numberField("Units", n -> n.fractionDigits(0))
                            .numberField("Day", n -> n.fractionDigits(0).min("1").max("31"))
                            .enumField("Plan", e -> e.value("BASIC").value("GOLD"))
                            .dateField("FiledOn", d -> d.format("yyyy-MM-dd"))))
            .build().toJson();

    @Test
    void aMarkedTypedReferenceReadsBackMarkedInEverySlot() {
        BoundModel m = BoundModel.ofJson(REGISTER);
        DateRef filedOn = m.date(G + "/FiledOn");
        StringRef code = m.str(G + "/Code");
        StringRef note = m.str(G + "/Note");
        NumberRef units = m.num(G + "/Units");
        EnumRef plan = m.enumOf(G + "/Plan");
        Map<String, Condition> matches = new LinkedHashMap<>();
        matches.put("Presence", note.outer().notFilled());
        matches.put("ValueList", code.outer().in("AB12", "CD34"));
        matches.put("FieldValueList", code.outer().in(code));
        matches.put("Pattern", code.outer().matches("AB.*"));
        matches.put("Length", code.outer().length().eq(code.length()));
        matches.put("Concatenation", code.outer().asString().plus("-").eq(code.asString().plus("-")));
        matches.put("Text", code.outer().eq(code.asString()));
        matches.put("Agrees", code.outer().agreesWith(code));
        matches.put("NumberList", units.outer().in(1, 2));
        matches.put("EnumList", plan.outer().in("GOLD"));
        matches.put("EnumConcatenation", plan.outer().plus("-").eq(plan.plus("-")));
        matches.put("MarkedListEntry", code.in(code.outer()));
        matches.put("Arithmetic", units.outer().plus(1).lt(units.plus(2)));
        matches.put("GroupList", Dsl.allGroupsFilled(note.field().outer(), code.field()));
        matches.put("DateComponent", filedOn.eq(Dsl.date(m.field(G + "/Day").outer(),
                Dsl.temporalComponent("01"), Dsl.temporalComponent("2024"))));
        Map<String, Condition> authored = new LinkedHashMap<>();
        BoundModel withRules = m;
        for (Map.Entry<String, Condition> match : matches.entrySet()) {
            Condition condition = filedOn.filled().and(Dsl.atLeastOneFieldFilled(
                    m.flatten("/Register/Claims*/FiledOn").having(filedOn.filled().and(match.getValue()))));
            authored.put(match.getKey(), condition);
            withRules = withRules.add(m.rule(m.group(G)).name(match.getKey())
                    .errorField(filedOn.field()).when(condition)
                    .code("C").severity(Severity.ERROR).message(en("Check the claim.")).build());
        }
        // the consistency gate is the admission oracle: it throws on a rule the kernel refuses
        KernelAdapter adapter = KernelAdapter.checkAndOpen(withRules);
        Map<String, Object> read = new LinkedHashMap<>();
        authored.keySet().forEach(name -> read.put(name, adapter.read(G + "/" + name) instanceof
                ReadResult.Converted converted ? converted.condition() : adapter.read(G + "/" + name)));
        assertEquals(authored, read);
    }
}
