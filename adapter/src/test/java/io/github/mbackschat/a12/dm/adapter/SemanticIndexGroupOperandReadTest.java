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
 * A GROUP operand carrying a semantic index reads back as the operand the author wrote, on every carrier A12
 * admits it in ({@code validate.laws.SemanticIndexGroupOperandCarrierLawsTest}): the group-list quantifiers,
 * the field-fill quantifiers over a group scope, and the aggregates. The converter built each group operand
 * from its path alone, so the index was dropped and the F10 index-preservation guard kept the whole rule
 * {@code Opaque}. That was safe, but it made the rule unreadable.
 *
 * <p>Each row is stored with absolute paths, so the oracle is read-back identity: the renderer's default
 * spelling of the converted condition must be the stored text. Rows are grouped by the converter arm they
 * reach, and one grid reports every failing row at once.
 */
class SemanticIndexGroupOperandReadTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();

    @Test
    void everyAdmittedCarrierReadsTheIndexedGroupBack() {
        List<String> conditions = List.of(
                // group-list quantifiers: a starred group, several, a mixed list, a field key. One unstarred group
                // is the presence leaf, locked below.
                "NoGroupFilled(/Order/Packages/Parcels* For \"P1\")",
                "AtLeastOneGroupFilled(/Order/Packages/Parcels* For \"P1\")",
                "AllGroupsFilled(/Order/Packages For \"P1\", /Order/Packages For \"P2\")",
                "NotAllGroupsFilled(/Order/Packages For \"P1\", /Order/Packages For \"P2\")",
                "GroupsNotCollectivelyFilled(/Order/Packages For \"P1\", /Order/Packages For \"P2\")",
                "AtLeastOneGroupFilled(/Order/Packages For \"P1\", /Order/Title)",
                "AtLeastOneGroupFilled(/Order/Packages For /Order/Title, /Order/Title)",
                // field-fill quantifiers over a group scope
                "AllFieldsFilled(/Order/Packages For \"P1\")",
                "NoFieldFilled(/Order/Packages/Parcels* For \"P1\")",
                "AllFieldsFilled(/Order/Packages For \"P1\", /Order/Packages For \"P2\")",
                "AllFieldsFilled(/Order/Packages For \"P1\", /Order/Title)",
                // aggregates
                "NumberOfFilledFields(/Order/Packages For \"P1\") > 0",
                "NumberOfFilledFields(/Order/Packages/Parcels* For \"P1\") > 0",
                "NumberOfDifferentValues(/Order/Packages/Parcels* For \"P1\") > 0",
                "NumberOfValueInFields(\"x\" In /Order/Packages/Parcels* For \"P1\") > 0",
                "Sum(/Order/Packages/Dimensions For \"P1\") > 0",
                "MaxValue(/Order/Packages/Dimensions For \"P1\") > 0",
                "MinValue(/Order/Packages/Dimensions For \"P1\") > 0",
                // aggregates over several operands, an indexed group among them
                "NumberOfFilledFields(/Order/Packages For \"P1\", /Order/Title) > 0",
                "NumberOfFilledFields(/Order/Packages For \"P1\", /Order/Packages For \"P2\") > 0",
                "NumberOfDifferentValues(/Order/Packages/Parcels* For \"P1\", /Order/Title) > 0",
                "Sum(/Order/Packages/Dimensions For \"P1\", /Order/Packages/Dimensions For \"P2\") > 0",
                "MaxValue(/Order/Packages/Dimensions For \"P1\", /Order/Packages/Dimensions For \"P2\") > 0",
                "MinValue(/Order/Packages/Dimensions For \"P1\", /Order/Packages/Dimensions For \"P2\") > 0");

        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        for (String condition : conditions) {
            String stored = "FieldFilled(/Order/Title) And " + condition;
            expected.put(condition, stored);
            actual.put(condition, readBack(stored));
        }
        assertEquals(expected, actual);
    }

    /**
     * The control that separates the two mechanisms: the multi-operand aggregate arm took fields only, so an
     * UNINDEXED group member read {@code Opaque} as well — the index was not what stopped it (DEV-API-GAPS DG64).
     */
    @Test
    void aMultiOperandAggregateReadsAGroupMemberWithoutAnIndexToo() {
        for (String condition : List.of(
                "NumberOfFilledFields(/Order/Packages*, /Order/Title) > 0",
                "NumberOfDifferentValues(/Order/Packages*/Parcels*, /Order/Title) > 0")) {
            String stored = "FieldFilled(/Order/Title) And " + condition;
            assertEquals(stored, readBack(stored));
        }
    }

    /**
     * The single-group presence spellings. {@code GroupFilled(G For k)} is the kernel's one-operand
     * {@code AllGroupsFilled} node, which A12 refuses when spelled that way ({@code MVK_PARAMSIZE_INVALID2}), so the
     * presence spelling is the only one it can read back as. {@code GroupNotFilled(G For k)} is the one-operand
     * {@code NoGroupFilled} node, and it canonicalizes to the presence spelling as a bare group's does, so the
     * stored {@code NoGroupFilled} spelling reads as it too.
     */
    @Test
    void aSingleIndexedGroupPresenceReadsAsThePresenceLeaf() {
        assertEquals("FieldFilled(/Order/Title) And GroupFilled(/Order/Packages For \"P1\")",
                readBack("FieldFilled(/Order/Title) And GroupFilled(/Order/Packages For \"P1\")"));
        assertEquals("FieldFilled(/Order/Title) And GroupNotFilled(/Order/Packages For \"P1\")",
                readBack("FieldFilled(/Order/Title) And GroupNotFilled(/Order/Packages For \"P1\")"));
        assertEquals("FieldFilled(/Order/Title) And GroupNotFilled(/Order/Packages For \"P1\")",
                readBack("FieldFilled(/Order/Title) And NoGroupFilled(/Order/Packages For \"P1\")"));
        assertEquals("FieldFilled(/Order/Title) And GroupNotFilled(/Order/Packages For /Order/Title)",
                readBack("FieldFilled(/Order/Title) And NoGroupFilled(/Order/Packages For /Order/Title)"));
    }

    /** The renderer's spelling of what the adapter read, or the {@code Opaque} result that stopped it. */
    private static String readBack(String stored) {
        DraftRule rule = new DraftRule("/Order", "IndexedGroupRule", "../Title", stored, "C", "ERROR");
        assertTrue(VALIDATOR.check(model(), rule).ok(), () -> "the kernel must admit the row it reads: " + stored);
        ReadResult read = KernelAdapter.ofJson(VALIDATOR.inject(model(), rule)).read("/Order/IndexedGroupRule");
        return read instanceof ReadResult.Converted converted
                ? new DslRenderer(ConditionLanguage.EN).render(converted.condition())
                : read.toString();
    }

    /** Packages is the index group; below it a repeatable, a number-only and a date-only group. */
    private static String model() {
        return DocumentModelDraft.named("parcels").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Title", FieldKind.STRING)
                        .group("Packages", g -> g.repeatable(5).indexField("PackageCode")
                                .field("PackageCode", FieldKind.STRING)
                                .field("Carrier", FieldKind.STRING)
                                .group("Parcels", p -> p.repeatable(5).field("Label", FieldKind.STRING))
                                .group("Dimensions", d -> d.field("Length", FieldKind.NUMBER)
                                        .field("Width", FieldKind.NUMBER))
                                .group("Schedule", s -> s.dateField("ShippedOn", f -> f.format("yyyy-MM-dd"))
                                        .dateField("DeliveredOn", f -> f.format("yyyy-MM-dd")))))
                .build().toJson();
    }
}
