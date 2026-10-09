package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * A stored rule whose {@code Date}/{@code Time} component A12 refuses reads back as the kernel's own answer, never
 * as a converter error: the kernel parses no such condition, so the typed construction's grammar check is never
 * reached on the read path (KERNEL-FINDINGS KF532). The condition is text because the typed surface refuses it.
 *
 * <p>Own domain (an insurance cover term).
 */
class RefusedTemporalComponentReadTest {

    private static final String TERM = DocumentModelDraft.named("cover-term-refused-read").locale("en_US")
            .rootGroup("Term", t -> t
                    .stringField("Holder", f -> { })
                    .dateField("StartOn", d -> d.format("yyyy-MM-dd"))
                    .timeField("RenewalClock", c -> c.format("HH:mm:ss"))
                    .dateTimeField("SignedAt", c -> c.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .group("Claims", g -> g.repeatable(5)
                            .numberField("HourNo", n -> n.fractionDigits(0).min("0").max("23"))))
            .build().toJson();

    @Test
    void everyRefusedComponentReadsBackAsTheKernelsUnparsedText() {
        Map<String, String> conditions = new LinkedHashMap<>();
        conditions.put("Aggregate", "Time(MaxValue(Claims*/HourNo)) > [RenewalClock]");
        conditions.put("StampSplit", "Date(DateFromDateTime(SignedAt), \"01\", \"2024\") > [StartOn]");
        conditions.put("SplitYearStampSplit", "Date(\"01\", \"01\", DateFromDateTime(SignedAt), \"24\") > [StartOn]");
        conditions.put("BareNumber", "Time(1) > [RenewalClock]");
        String json = TERM;
        for (Map.Entry<String, String> rule : conditions.entrySet()) {
            json = RuntimeLaws.withRule(json, "/Term", rule.getKey(), "../Holder", "C",
                    "FieldFilled(Holder) And " + rule.getValue());
        }
        // lenient, since the checked binding refuses the whole model before any rule is read
        KernelAdapter adapter = KernelAdapter.of(BoundModel.ofJsonLenient(json, ReferenceResolvers.selfContained()));
        Map<String, String> reasons = new LinkedHashMap<>();
        conditions.keySet().forEach(name -> reasons.put(name, adapter.read("/Term/" + name) instanceof
                ReadResult.Opaque opaque ? opaque.reason() : "converted"));
        Map<String, String> expected = new LinkedHashMap<>();
        conditions.keySet().forEach(name ->
                expected.put(name, "the kernel could not parse the stored condition against this model"));
        assertEquals(expected, reasons);
    }
}
