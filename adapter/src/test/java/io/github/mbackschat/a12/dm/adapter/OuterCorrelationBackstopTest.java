package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * The N8 safety net for {@code $} outer-iteration correlation read (D13): {@code verifyOuterPreserved}.
 * The supported comparison kinds (number/string/date/enum/category) and {@code CurrentRepetition($)}
 * are threaded, but the guarantee that an <em>un</em>-threaded kind can never silently drop a {@code $}
 * does not depend on which kinds happen to be threaded — it is this count-check: if the converted
 * condition renders fewer {@code $} markers than the source carried, the conversion is refused (the
 * rule degrades to verbatim {@link ReadResult.Opaque}). This white-box test drives the check directly, so
 * it stays valid no matter how many kinds get threaded later.
 */
class OuterCorrelationBackstopTest {

    private static String scheduleJson() {
        return DocumentModelDraft.named("schedule").locale("en_US")
                .rootGroup("Schedule", s -> s
                        .group("Slots", g -> g.repeatable(50)
                                .dateField("Start", d -> d.format("yyyy-MM-dd"))))
                .build().toJson();
    }

    @Test
    void refusesWhenAConvertedConditionDroppedAMarker() {
        BoundModel m = BoundModel.ofJson(scheduleJson());
        ConditionConverter conv = new ConditionConverter(m);
        // a converted condition with NO $ marker, but the source was said to carry one → silent drop
        Condition droppedTheDollar = m.field("/Schedule/Slots/Start").filled();

        assertThrows(UnsupportedConstructException.class,
                () -> conv.verifyOuterPreserved(droppedTheDollar, 1),
                "a converted condition rendering fewer $ than the source must be refused (N8)");
    }

    @Test
    void passesWhenEveryMarkerSurvives() {
        BoundModel m = BoundModel.ofJson(scheduleJson());
        ConditionConverter conv = new ConditionConverter(m);
        // CurrentRepetition(Slots) != CurrentRepetition($Slots) — renders one $ marker ("($")
        Condition keptTheDollar = Dsl.currentRepetition(m.group("/Schedule/Slots"))
                .ne(Dsl.currentRepetition(m.group("/Schedule/Slots"), true));

        assertDoesNotThrow(() -> conv.verifyOuterPreserved(keptTheDollar, 1),
                "a conversion that preserves the $ marker must pass the count-check");
    }
}
