package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimeExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.OpListMath;
import io.github.mbackschat.a12.dm.rulekit.dsl.TimeExpr;

/** Operand-list {@code Min}/{@code Max} over TIME and DATETIME operands (KERNEL-FINDINGS §6). */
class TemporalOperandMinMaxConditionTest {

    private static final BoundModel MODEL = BoundModel.ofJson(DocumentModelDraft.named("temporal-extrema")
            .locale("en_US")
            .rootGroup("Schedule", s -> s
                    .timeField("Start", t -> t.format("HH:mm:ss"))
                    .timeField("End", t -> t.format("HH:mm:ss"))
                    .timeField("Limit", t -> t.format("HH:mm:ss"))
                    .dateTimeField("CheckIn", dt -> dt.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateTimeField("CheckOut", dt -> dt.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateTimeField("Deadline", dt -> dt.format("yyyy-MM-dd'T'HH:mm:ss")))
            .build().toJson());

    @Test
    void timeMinMaxAreTimeOperands() {
        TimeExpr earliest = Dsl.min(MODEL.time("/Schedule/Start"), MODEL.time("/Schedule/End"));
        Condition latestAfterLimit = Dsl.max(MODEL.time("/Schedule/Start"), MODEL.time("/Schedule/End"))
                .after(MODEL.time("/Schedule/Limit"));

        assertEquals(ValueKind.TIME, earliest.expression().kind());
        assertEquals("Min([/Schedule/Start], [/Schedule/End])", new DslRenderer().render(earliest.expression()));
        assertEquals("Max([/Schedule/Start], [/Schedule/End]) > [/Schedule/Limit]",
                new DslRenderer().render(latestAfterLimit));
    }

    @Test
    void dateTimeMinMaxAreDateTimeOperands() {
        DateTimeExpr earliest = Dsl.min(MODEL.dateTime("/Schedule/CheckIn"),
                MODEL.dateTime("/Schedule/CheckOut"));
        Condition latestAfterDeadline = Dsl.max(MODEL.dateTime("/Schedule/CheckIn"),
                MODEL.dateTime("/Schedule/CheckOut")).after(MODEL.dateTime("/Schedule/Deadline"));

        assertEquals(ValueKind.DATE_TIME, earliest.expression().kind());
        assertEquals("Min([/Schedule/CheckIn], [/Schedule/CheckOut])",
                new DslRenderer().render(earliest.expression()));
        assertEquals("Max([/Schedule/CheckIn], [/Schedule/CheckOut]) > [/Schedule/Deadline]",
                new DslRenderer().render(latestAfterDeadline));
    }

    @Test
    void theAstBoundaryRejectsMixedOrNonNumericAbsOperands() {
        var start = MODEL.time("/Schedule/Start").expression();
        var checkIn = MODEL.dateTime("/Schedule/CheckIn").expression();

        assertThrows(IllegalArgumentException.class,
                () -> new OpListMath(OpListMath.Op.MIN, List.of(start, checkIn)));
        assertThrows(IllegalArgumentException.class,
                () -> new OpListMath(OpListMath.Op.ABS, List.of(start)));
    }
}
