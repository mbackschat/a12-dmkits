package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * A {@code Having} filter survives read on every carrier that admits one, and a conversion that loses one is
 * refused rather than published.
 *
 * <p>A filtered list reads only the repetitions its filter keeps, so dropping the filter broadens the rule while
 * the read still reports {@code Converted}. The literal value-list quantifiers did exactly that: they rebuilt
 * each starred member from its path, so {@code AtLeastOneFieldValueIncludedInValueList(Items*}{@code /Code Having
 * … In "a")} read back unfiltered. The grid is the carriers a filtered list reaches; every row is
 * kernel-admitted first, and the oracle is read-back identity.
 */
class HavingFilterPreservedReadTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();

    @Test
    void aFilteredListReadsBackWithItsFilterOnEveryCarrier() {
        assertReadsBack(List.of(
                "Sum(/Order/Items*/Qty Having [/Order/Items/Qty] > 1) > 0",
                "MaxValue(/Order/Items*/Qty Having [/Order/Items/Qty] > 1) > 0",
                "MinValue(/Order/Items*/Qty Having [/Order/Items/Qty] > 1) > 0",
                "Sum(/Order/Items*/Qty Having [/Order/Items/Qty] > 1, /Order/Limit) > 0",
                "NumberOfFilledFields(/Order/Items*/Sku Having [/Order/Items/Qty] > 1) > 0",
                "NumberOfDifferentValues(/Order/Items*/Sku Having [/Order/Items/Qty] > 1) > 0",
                "FirstFilledValue(/Order/Items*/Qty Having [/Order/Items/Qty] > 1) > 0",
                "MaxValue(/Order/Items*/ShippedOn Having [/Order/Items/Qty] > 1) == [/Order/PlacedOn]",
                "AtLeastOneFieldFilled(/Order/Items*/Sku Having [/Order/Items/Qty] > 1)",
                "NoFieldFilled(/Order/Items*/Sku Having [/Order/Items/Qty] > 1)",
                "NotAllFieldsFilled(/Order/Items*/Sku Having [/Order/Items/Qty] > 1, /Order/Title)",
                "FieldValuesNotUnique(/Order/Items*/Sku Having [/Order/Items/Qty] > 1)",
                "NumberOfValueInFields(\"x\" In /Order/Items*/Sku Having [/Order/Items/Qty] > 1) > 0",
                "AtLeastOneFieldValueIncludedInValueList(/Order/Items*/Code Having [/Order/Items/Qty] > 1 In \"a\", \"b\")",
                "NoFieldValueIncludedInValueList(/Order/Items*/Code Having [/Order/Items/Qty] > 1 In \"a\")",
                "AtLeastOneFieldValueIncludedInValueList(/Order/Title In /Order/Items*/Code Having [/Order/Items/Qty] > 1)"));
    }

    /** The count-check itself, driven directly, so it holds whichever conversion sites keep their filters. */
    @Test
    void aConversionThatRendersFewerFiltersThanTheSourceIsRefused() {
        ConditionConverter converter = new ConditionConverter(BoundModel.ofJson(model()));

        assertThrows(UnsupportedConstructException.class,
                () -> converter.verifyFilterPreserved("NoFieldFilled(/Order/Items*/Sku)", 1));
        assertThrows(UnsupportedConstructException.class,
                () -> converter.verifyFilterPreserved("[/Order/Title] == \"Having\"", 1),
                "the keyword inside a string literal is not a filter");
        assertDoesNotThrow(() -> converter.verifyFilterPreserved(
                "NoFieldFilled(/Order/Items*/Sku Having [/Order/Items/Qty] > 1)", 1));
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

    /** The renderer's spelling of what the adapter read, the {@code Opaque} that stopped it, or the kernel's refusal. */
    private static String readBack(String stored) {
        DraftRule rule = new DraftRule("/Order", "FilteredRule", "../Title", stored, "C", "ERROR");
        ValidationResult admitted = VALIDATOR.check(model(), rule);
        if (!admitted.ok()) {
            return "kernel refuses: " + admitted.diagnostics();
        }
        ReadResult read = KernelAdapter.ofJson(VALIDATOR.inject(model(), rule)).read("/Order/FilteredRule");
        return read instanceof ReadResult.Converted converted
                ? new DslRenderer(ConditionLanguage.EN).render(converted.condition())
                : read.toString();
    }

    /** One repeatable line group, a number to filter on, and the kinds each carrier reads. */
    private static String model() {
        return DocumentModelDraft.named("orders").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Title", FieldKind.STRING)
                        .field("Limit", FieldKind.NUMBER)
                        .dateField("PlacedOn", f -> f.format("yyyy-MM-dd"))
                        .group("Items", g -> g.repeatable(9)
                                .field("Sku", FieldKind.STRING)
                                .field("Code", FieldKind.STRING)
                                .field("Qty", FieldKind.NUMBER)
                                .dateField("ShippedOn", f -> f.format("yyyy-MM-dd"))))
                .build().toJson();
    }
}
