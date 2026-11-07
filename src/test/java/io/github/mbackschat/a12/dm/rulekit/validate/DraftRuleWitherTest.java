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
        assertEquals(DraftRule.builder().groupPath("/Policy").name("Probe").errorEntityRelPath("../Code")
                .errorCondition("FieldNotFilled(Code)").errorCode("P1").severity("ERROR").messages(SEED.messages()).build(), edited);
    }

    @Test
    void newMessagesReplaceTheOldOnesAndKeepEveryOtherComponent() {
        List<LocalizedText> replaced = List.of(new LocalizedText("de_DE", "Code gesetzt"));
        DraftRule edited = SEED.withMessages(replaced);
        assertEquals(DraftRule.builder().groupPath("/Policy").name("Probe").errorEntityRelPath("../Code")
                .errorCondition("FieldFilled(Code)").errorCode("P1").severity("ERROR").messages(replaced).build(),
                edited);
    }
}
