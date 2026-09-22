package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledValue;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS KF71 — the ORDER of {@code FirstFilledValue}'s operand-list gates, and the class each draws.
 *
 * <p>The source counterpart is {@code CheckFeldListenOpImpl.check}: the reference list is built with its
 * duplicate check, then arity, then {@code checkNoBoolY} over every field the operands expand to, then
 * {@code checkAllFieldsMatchingOperationType} over the same fields. Both field-level gates read a GROUP operand
 * through its expansion, so a group mixing text and number is refused as one operand list. The grid below
 * confirms that order on lists breaking two gates at once, and measures the type gate across every base-type
 * pair the typed surface can author.
 *
 * <p>{@link #rulekitRefusesEachListWithTheClassTheKernelReports} is the client mirror: rulekit's record must
 * reach the same verdict on every list, which is what lets its two field-level refusals cite their codes.
 */
class FirstFilledValueGateOrderLawsTest {

    private static final String MODEL = DocumentModelDraft.named("catalogue").locale("en_US")
            .rootGroup("Item", i -> i
                    .field("Title", FieldKind.STRING)
                    .field("Subtitle", FieldKind.STRING)
                    .field("Weight", FieldKind.NUMBER)
                    .field("InStock", FieldKind.CONFIRM)
                    .field("Active", FieldKind.BOOLEAN)
                    .field("Featured", FieldKind.BOOLEAN)
                    .enumField("Grade", e -> e.value("NEW").value("USED"))
                    .customField("StockCode", f -> f.type("SelectionCode"))
                    .dateField("ListedOn", d -> d.format("yyyy-MM-dd"))
                    .dateRangeField("Window", r -> r.format("yyyy-MM-dd").separator("/"))
                    .group("Specs", g -> g.field("Label", FieldKind.STRING).field("Height", FieldKind.NUMBER))
                    .group("Flags", g -> g.field("Note", FieldKind.STRING).field("Fragile", FieldKind.CONFIRM)))
            .build().toJson();

    private static final Set<String> GROUPS = Set.of("Specs", "Flags");

    /** Operand list → the class A12 reports for it: the grid both tests below hold rulekit and the kernel to. */
    private static final Map<String, String> LISTS = lists();

    private static Map<String, String> lists() {
        Map<String, String> lists = new LinkedHashMap<>();
        lists.put("Title, Subtitle", ADMITTED);
        lists.put("Title, Weight", VARYING);
        lists.put("Title, InStock", BOOLY);
        // two gates broken at once: the one the kernel asks first answers
        lists.put("Title, Weight, InStock", BOOLY);
        lists.put("Title, Weight, Title", "MVK_DUPLICATE_PARAM1");
        lists.put("Title, InStock, Title", "MVK_DUPLICATE_PARAM1");
        lists.put("Specs, Specs/Label", "MVK_DUPLICATE_PARAM2");
        lists.put("Specs, Weight, Specs/Label", "MVK_DUPLICATE_PARAM2");
        // a group is judged by the fields it expands to
        lists.put("Specs", VARYING);
        lists.put("Flags", BOOLY);
        lists.put("Specs, InStock", BOOLY);
        lists.put("Title, Specs", VARYING);
        // the type gate across base types
        lists.put("Active, Featured", ADMITTED);
        lists.put("Title, Active", VARYING);
        lists.put("Active, Weight", VARYING);
        lists.put("Grade, Weight", VARYING);
        lists.put("StockCode, Weight", VARYING);
        lists.put("Title, ListedOn", VARYING);
        lists.put("Title, Window", VARYING);
        lists.put("Weight, Window", VARYING);
        lists.put("Active, InStock", BOOLY);
        return lists;
    }

    @Test
    void theKernelAsksDuplicatesThenConfirmThenOneTypeOverEveryExpandedField() {
        Map<String, String> actual = new LinkedHashMap<>();
        LISTS.keySet().forEach(list -> actual.put(list,
                verdict("FirstFilledValue(" + list + ") == [" + comparand(list) + "]")));
        assertEquals(LISTS, actual);

        // each comparand compared with itself, so no row above is the comparison refusing
        Map<String, String> controls = new LinkedHashMap<>();
        LISTS.keySet().stream().map(FirstFilledValueGateOrderLawsTest::comparand).distinct()
                .forEach(field -> controls.put(field, verdict("[" + field + "] == [" + field + "]")));
        controls.values().forEach(verdict -> assertEquals(ADMITTED, verdict, controls.toString()));
    }

    @Test
    void rulekitRefusesEachListWithTheClassTheKernelReports() {
        BoundModel m = BoundModel.ofJson(MODEL);
        Map<String, String> actual = new LinkedHashMap<>();
        for (String list : LISTS.keySet()) {
            List<FirstFilledOperand> operands = new ArrayList<>();
            for (String name : list.split(", ")) {
                operands.add(GROUPS.contains(name) ? m.firstFilledGroup("/Item/" + name)
                        : FirstFilledOperand.of(m.field("/Item/" + name)));
            }
            actual.put(list, clientVerdict(operands));
        }
        assertEquals(LISTS, actual);
    }

    private static String clientVerdict(List<FirstFilledOperand> operands) {
        try {
            new FirstFilledValue(operands.getFirst(), operands.subList(1, operands.size()));
            return ADMITTED;
        } catch (KernelCodeRefusal refusal) {
            return refusal.code().name();
        } catch (IllegalArgumentException uncoded) {
            return "(uncoded: " + uncoded.getMessage() + ")";
        }
    }

    /** The first FIELD the list reads — a group's own first field — so the comparison fits every row. */
    private static String comparand(String list) {
        String first = Arrays.asList(list.split(", ")).getFirst();
        return switch (first) {
            case "Specs" -> "Specs/Label";
            case "Flags" -> "Flags/Note";
            default -> first;
        };
    }

    private static final String ADMITTED = "admitted";
    private static final String VARYING = "MVK_VARYING_TYPES_NOT_ALLOWED";
    private static final String BOOLY = "MVK_NO_BOOLY_ALLOWED";

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String comparison) {
        ValidationResult r = KernelLaws.checkModel(MODEL, "/Item", "../Title", "FieldFilled(Title) And " + comparison);
        return r.ok() ? ADMITTED : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).map(Object::toString).toList());
    }
}
