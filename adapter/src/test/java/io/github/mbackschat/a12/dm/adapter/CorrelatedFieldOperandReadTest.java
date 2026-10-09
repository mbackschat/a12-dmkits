package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DatePrecision;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * A {@code $}-marked field operand inside a filter reads back marked, in every slot that takes a plain field: it
 * reads the rule's own row rather than the candidate's, so a marker lost on either side of the round trip changes
 * what the condition compares while still rendering a valid reference. Each filter also reads the candidate row,
 * which A12 requires of every filter. Each shape is authored, injected into the kernel model (which admits it) and
 * converted back; the read must equal the authored object.
 *
 * <p>Own domain (an insurance claims register).
 */
class CorrelatedFieldOperandReadTest {

    private static final String REGISTER = DocumentModelDraft.named("claims-register-outer").locale("en_US")
            .rootGroup("Register", r -> r
                    .group("Claims", g -> g.repeatable(5)
                            .stringField("Code", f -> f.maxLength(4))
                            .numberField("Amount", n -> n.fractionDigits(2))
                            .dateField("FiledOn", f -> f.format("yyyy-MM-dd"))
                            .dateField("ReportedAbout",
                                    f -> f.format("yyyy-MM-dd").datePrecision(DatePrecision.MONTH_OPTIONAL))))
            .build().toJson();

    @Test
    void aMarkedOperandReadsBackMarkedInEverySlot() {
        BoundModel m = BoundModel.ofJson(REGISTER);
        DateRef filedOn = m.date("/Register/Claims/FiledOn");
        FieldRef code = m.field("/Register/Claims/Code").outer();
        Map<String, Condition> filters = new LinkedHashMap<>();
        filters.put("ValueAsDate", filedOn.after(m.valueAsDateLastDay(m.field("/Register/Claims/ReportedAbout").outer())));
        filters.put("Valid", Dsl.validType(code, "Postcode"));
        filters.put("FieldValueAsString", Dsl.fieldValueAsString(m.field("/Register/Claims/Amount").outer()).eq("1"));
        filters.put("RangeAsString", Dsl.rangeAsString(code, 1, 2).eq("AB"));
        filters.put("RangeAsNumber", Dsl.rangeAsNumber(code, 3, 4).gt(0));
        filters.put("RoundUp", Dsl.roundUpValue(m.num("/Register/Claims/Amount").outer()).gt(0));
        Map<String, Condition> authored = new LinkedHashMap<>();
        BoundModel withRules = m;
        for (Map.Entry<String, Condition> filter : filters.entrySet()) {
            Condition condition = filedOn.filled().and(Dsl.atLeastOneFieldFilled(
                    m.flatten("/Register/Claims*/FiledOn").having(filedOn.filled().and(filter.getValue()))));
            authored.put(filter.getKey(), condition);
            withRules = withRules.add(m.rule(m.group("/Register/Claims")).name(filter.getKey())
                    .errorField(filedOn.field()).when(condition)
                    .code("C").severity(Severity.ERROR).message(en("Check the claim.")).build());
        }
        KernelAdapter adapter = KernelAdapter.of(withRules);
        Map<String, Object> read = new LinkedHashMap<>();
        authored.keySet().forEach(name -> read.put(name, adapter.read("/Register/Claims/" + name) instanceof
                ReadResult.Converted converted ? converted.condition() : adapter.read("/Register/Claims/" + name)));
        assertEquals(authored, read);
    }
}
