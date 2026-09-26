package io.github.mbackschat.a12.dm.rulekit.validate;

import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * A draft varies in one component at a time, which is what a sweep of near-miss candidates does to every
 * seed; the withers keep every other component, messages included.
 */
class DraftRuleWitherTest {

    private static final DraftRule SEED = DraftRule.builder().groupPath("/Policy").name("Probe")
            .errorEntityRelPath("../Code").errorCondition("FieldFilled(Code)").errorCode("P1").severity("ERROR")
            .messages(List.of(new LocalizedText("en_US", "code is set"))).build();

    @Test
    void aNewConditionKeepsEveryOtherComponent() {
        DraftRule edited = SEED.withErrorCondition("FieldNotFilled(Code)");
        assertEquals(new DraftRule("/Policy", "Probe", "../Code", "FieldNotFilled(Code)", "P1", "ERROR",
                SEED.messages()), edited);
    }

    @Test
    void newMessagesReplaceTheOldOnesAndKeepEveryOtherComponent() {
        List<LocalizedText> replaced = List.of(new LocalizedText("de_DE", "Code gesetzt"));
        DraftRule edited = SEED.withMessages(replaced);
        assertEquals(new DraftRule("/Policy", "Probe", "../Code", "FieldFilled(Code)", "P1", "ERROR", replaced),
                edited);
    }
}
