package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * A {@code MaxValue}/{@code MinValue} whose scope holds only GROUP operands is a temporal extremum when the
 * group expands to date-like fields, and reads back as one when compared to a field. The converter typed the
 * extremum from its field operands alone, so a group-only scope was taken for a numeric aggregate and a
 * comparison against a date field read {@code Opaque}.
 *
 * <p>A12 judges a group operand by its recursive expansion, as a flat operand list (KERNEL-FINDINGS
 * {@code kf193}), so the rows cover a date, a time and a date-time group, starred and index-selected. Every
 * row is kernel-admitted first, and the oracle is read-back identity: the stored text uses absolute paths,
 * the renderer's default.
 */
class TemporalGroupScopeExtremumReadTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();

    @Test
    void aGroupOnlyTemporalExtremumReadsBackAgainstAField() {
        List<String> conditions = List.of(
                "MaxValue(/Order/Packages*/Schedule) == [/Order/PlacedOn]",
                "MinValue(/Order/Packages*/Schedule) < [/Order/PlacedOn]",
                "MaxValue(/Order/Packages*/Slots) > [/Order/CutOff]",
                "MinValue(/Order/Packages*/Stamps) < [/Order/AuditedAt]",
                "MaxValue(/Order/Packages/Schedule For \"P1\") == [/Order/PlacedOn]");

        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        for (String condition : conditions) {
            String stored = "FieldFilled(/Order/Title) And " + condition;
            expected.put(condition, stored);
            actual.put(condition, readBack(stored));
        }
        assertEquals(expected, actual);
    }

    /** The renderer's spelling of what the adapter read, or the {@code Opaque} result that stopped it. */
    private static String readBack(String stored) {
        DraftRule rule = new DraftRule("/Order", "ScheduleRule", "../Title", stored, "C", "ERROR");
        assertTrue(VALIDATOR.check(model(), rule).ok(), () -> "the kernel must admit the row it reads: " + stored);
        ReadResult read = KernelAdapter.ofJson(VALIDATOR.inject(model(), rule)).read("/Order/ScheduleRule");
        return read instanceof ReadResult.Converted converted
                ? new DslRenderer(ConditionLanguage.EN).render(converted.condition())
                : read.toString();
    }

    /** Packages is the index group; below it one group per temporal kind, each holding only that kind. */
    private static String model() {
        return DocumentModelDraft.named("dispatch").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Title", FieldKind.STRING)
                        .dateField("PlacedOn", f -> f.format("yyyy-MM-dd"))
                        .timeField("CutOff", f -> f.format("HH:mm:ss"))
                        .dateTimeField("AuditedAt", f -> f.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .group("Packages", g -> g.repeatable(5).indexField("PackageCode")
                                .field("PackageCode", FieldKind.STRING)
                                .group("Schedule", s -> s.dateField("ShippedOn", f -> f.format("yyyy-MM-dd"))
                                        .dateField("DeliveredOn", f -> f.format("yyyy-MM-dd")))
                                .group("Slots", s -> s.timeField("OpensAt", f -> f.format("HH:mm:ss"))
                                        .timeField("ClosesAt", f -> f.format("HH:mm:ss")))
                                .group("Stamps", s -> s
                                        .dateTimeField("ScannedAt", f -> f.format("yyyy-MM-dd'T'HH:mm:ss"))
                                        .dateTimeField("SignedAt", f -> f.format("yyyy-MM-dd'T'HH:mm:ss")))))
                .build().toJson();
    }
}
