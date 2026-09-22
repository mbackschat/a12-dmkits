package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * The READ half of KERNEL-FINDINGS KF71: a {@code FirstFilledValue} over two date-bearing KINDS at one declared
 * format is kernel-legal, so reading it back must convert it. The record's kind-family gate refused such lists,
 * and the converter builds that record, so a legal model came back {@code Opaque} — unreadable, not merely
 * unauthorable. The rule is injected as text because its point is the model a foreign author wrote.
 */
class FirstFilledValueCrossKindReadTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();

    @Test
    void readsATimeBesideATimeOnlyDateTime() {
        assertConverts("FirstFilledValue(OpensAt, ClockedAt) == [OpensAt]", "OpensAt",
                "FirstFilledValue(/Shift/OpensAt, /Shift/ClockedAt) == [/Shift/OpensAt]");
    }

    @Test
    void readsADateBesideADateOnlyTime() {
        assertConverts("FirstFilledValue(ListedOn, DueOn) == [ListedOn]", "ListedOn",
                "FirstFilledValue(/Shift/ListedOn, /Shift/DueOn) == [/Shift/ListedOn]");
    }

    /** {@code rendered} is the renderer's default absolute spelling of the same condition. */
    private static void assertConverts(String condition, String errorField, String rendered) {
        DraftRule rule = new DraftRule("/Shift", "CrossKindRule", "../" + errorField, condition, "C", "ERROR");
        assertTrue(VALIDATOR.check(model(), rule).ok(), "the kernel must admit the list this row reads back");

        ReadResult read = KernelAdapter.ofJson(VALIDATOR.inject(model(), rule)).read("/Shift/CrossKindRule");
        ReadResult.Converted converted = assertInstanceOf(ReadResult.Converted.class, read, read::toString);
        assertEquals(rendered, new DslRenderer(ConditionLanguage.EN).render(converted.condition()));
    }

    private static String model() {
        return DocumentModelDraft.named("timesheet").locale("en_US")
                .rootGroup("Shift", s -> s
                        .dateField("ListedOn", d -> d.format("yyyy-MM-dd"))
                        .timeField("OpensAt", t -> t.format("HH:mm:ss"))
                        .timeField("DueOn", t -> t.format("yyyy-MM-dd"))
                        .dateTimeField("ClockedAt", d -> d.format("HH:mm:ss")))
                .build().toJson();
    }
}
