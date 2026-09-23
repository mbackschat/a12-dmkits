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
 * An index-selected FIELD ({@code [Weight For "P1"]}) reads back with its key in every operand position A12
 * admits it in. The converter kept the key only where a comparison's left side or a presence condition named
 * the field; everywhere else it rebuilt the field from its path, and the F10 index backstop kept the whole
 * rule {@code Opaque}. That was safe, but it made the rule unreadable, and two of those positions also tripped
 * rulekit's duplicate gate, because two cells of one field under different keys look like one field repeated.
 *
 * <p>Each row is kernel-admitted first, and the oracle is read-back identity against the stored text, which
 * uses absolute paths, the renderer's default. Rows are grouped by the converter path they reach, and one grid
 * reports every failing row at once.
 */
class SemanticIndexFieldOperandPositionReadTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();

    /** Every entity-list slot: the members go through one shared field-operand conversion. */
    @Test
    void anIndexedFieldReadsBackAsAnEntityListMember() {
        assertReadsBack(List.of(
                // aggregates, including two cells of one field and a field-valued key
                "Sum(/Order/Packages/Weight For \"P1\", /Order/Packages/Weight For \"P2\") > 0",
                "Sum(/Order/Packages/Weight For /Order/Title, /Order/Limit) > 0",
                "MaxValue(/Order/Packages/Weight For \"P1\", /Order/Limit) > 0",
                "NumberOfDifferentValues(/Order/Packages/Weight For \"P1\", /Order/Limit) > 0",
                "NumberOfFilledFields(/Order/Packages/Carrier For \"P1\", /Order/Title) > 0",
                "NumberOfValueInFields(\"x\" In /Order/Packages/Carrier For \"P1\", /Order/Title) > 0",
                // temporal extrema; the second is an indexed group beside an indexed field inside it
                "MaxValue(/Order/Packages/Schedule/ShippedOn For \"P1\", /Order/Packages/Schedule/ShippedOn For \"P2\")"
                        + " == [/Order/PlacedOn]",
                "MaxValue(/Order/Packages/Schedule For \"P1\", /Order/Packages/Schedule/ShippedOn For \"P3\")"
                        + " == [/Order/PlacedOn]",
                // fill and uniqueness quantifiers
                "AllFieldsFilled(/Order/Packages/Carrier For \"P1\", /Order/Title)",
                "MoreThanOneFieldFilled(/Order/Packages/Carrier For \"P1\", /Order/Title)",
                "FieldValuesNotUnique(/Order/Packages/Carrier For \"P1\", /Order/Title)"));
    }

    private static void assertReadsBack(List<String> conditions) {
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
        DraftRule rule = new DraftRule("/Order", "IndexedFieldRule", "../Title", stored, "C", "ERROR");
        assertTrue(VALIDATOR.check(model(), rule).ok(), () -> "the kernel must admit the row it reads: " + stored);
        ReadResult read = KernelAdapter.ofJson(VALIDATOR.inject(model(), rule)).read("/Order/IndexedFieldRule");
        return read instanceof ReadResult.Converted converted
                ? new DslRenderer(ConditionLanguage.EN).render(converted.condition())
                : read.toString();
    }

    /** Packages is the index group; it holds one field of each kind the rows compare, and a date group. */
    private static String model() {
        return DocumentModelDraft.named("dispatch").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Title", FieldKind.STRING)
                        .field("Limit", FieldKind.NUMBER)
                        .dateField("PlacedOn", f -> f.format("yyyy-MM-dd"))
                        .timeField("OpensAt", f -> f.format("HH:mm:ss"))
                        .dateTimeField("AuditedAt", f -> f.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .group("Packages", g -> g.repeatable(5).indexField("PackageCode")
                                .field("PackageCode", FieldKind.STRING)
                                .field("Carrier", FieldKind.STRING)
                                .field("Weight", FieldKind.NUMBER)
                                .group("Schedule", s -> s.dateField("ShippedOn", f -> f.format("yyyy-MM-dd"))
                                        .dateField("DeliveredOn", f -> f.format("yyyy-MM-dd")))
                                .timeField("CutOff", f -> f.format("HH:mm:ss"))
                                .dateTimeField("ScannedAt", f -> f.format("yyyy-MM-dd'T'HH:mm:ss"))))
                .build().toJson();
    }
}
