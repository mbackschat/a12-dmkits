package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledValue;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS KF71 — the ORDER of {@code FirstFilledValue}'s operand-list gates, and the class each draws.
 *
 * <p>The source counterpart is {@code CheckFeldListenOpImpl.check}: the reference list is built with its
 * duplicate check, then the root-group gate, then arity, then {@code checkNoBoolY} over every field the operands
 * expand to, then {@code checkAllFieldsMatchingOperationType} over the same fields. Both field-level gates read a
 * GROUP operand through its expansion, so a group mixing text and number is refused as one operand list. The grid
 * below confirms that order on lists breaking two gates at once, and measures the type gate across every
 * base-type pair the typed surface can author. The second grid places the root gate: after a duplicate, which
 * every other operand under that root is, and behind the operand's own shape classes, an empty root's included.
 *
 * <p>The mirrors hold rulekit to the same verdict on every list, through the record and through the typed
 * {@code Dsl} factories, which is what lets its list refusals cite their codes.
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
        lists.put("Title, Weight, Title", DUPLICATE);
        lists.put("Title, InStock, Title", DUPLICATE);
        lists.put("Flags, Title, Title", DUPLICATE);
        lists.put("Specs, Specs/Label", CONTAINED);
        lists.put("Specs, Weight, Specs/Label", CONTAINED);
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
                verdict(MODEL, "FirstFilledValue(" + list + ") == [" + comparand(list) + "]")));
        assertEquals(LISTS, actual);

        // each comparand compared with itself, so no row above is the comparison refusing
        Map<String, String> controls = new LinkedHashMap<>();
        LISTS.keySet().stream().map(FirstFilledValueGateOrderLawsTest::comparand).distinct()
                .forEach(field -> controls.put(field, verdict(MODEL, "[" + field + "] == [" + field + "]")));
        controls.values().forEach(verdict -> assertEquals(ADMITTED, verdict, controls.toString()));
    }

    @Test
    void rulekitRefusesEachListWithTheClassTheKernelReports() {
        BoundModel m = BoundModel.ofJson(MODEL);
        Map<String, String> actual = new LinkedHashMap<>();
        for (String list : LISTS.keySet()) {
            actual.put(list, clientVerdict(() -> {
                List<FirstFilledOperand> operands = new ArrayList<>();
                for (String name : list.split(", ")) {
                    operands.add(GROUPS.contains(name) ? m.firstFilledGroup("/Item/" + name)
                            : FirstFilledOperand.of(m.field("/Item/" + name)));
                }
                return new FirstFilledValue(operands.getFirst(), operands.subList(1, operands.size()));
            }));
        }
        assertEquals(LISTS, actual);
    }

    /**
     * A root group, beside a second root that is populated and a third that is empty. Every other operand of the
     * rule's own root lies under it, so a duplicate answers first there; an operand from another root does not,
     * and neither does a category projection. The root's own shape classes answer ahead of both.
     */
    private static final String ROOTS = DocumentModelDraft.named("catalogue").locale("en_US")
            .rootGroup("Item", i -> i
                    .field("Title", FieldKind.STRING)
                    .field("Weight", FieldKind.NUMBER)
                    .field("InStock", FieldKind.CONFIRM)
                    .enumField("Tier", e -> e.value("BASIC").value("PLUS").category("Band", "LOW", "HIGH")))
            .rootGroup("Other", o -> o.group("Inner", inner -> { }))
            .rootGroup("Extra", x -> x.field("Code", FieldKind.STRING))
            .build().toJson();

    private static final Map<String, String> ROOT_LISTS = rootLists();

    private static Map<String, String> rootLists() {
        Map<String, String> lists = new LinkedHashMap<>();
        lists.put("/Item", ROOT);
        lists.put("/Extra", ROOT);
        // a duplicate first: every other operand of the same root lies under it
        lists.put("/Item, /Item", DUPLICATE);
        lists.put("/Item, Title", CONTAINED);
        lists.put("Title, /Item", CONTAINED);
        lists.put("/Item, Weight", CONTAINED);
        lists.put("InStock, /Item", CONTAINED);
        lists.put("/Extra, /Extra/Code", CONTAINED);
        // …and where nothing else overlaps it, the root
        lists.put("/Item, /Extra/Code", ROOT);
        lists.put("/Extra, Title", ROOT);
        lists.put("/Extra, Weight", ROOT);
        lists.put("/Item, Tier -> Band", ROOT);
        lists.put("Title, /Extra/Code", ADMITTED);
        // the root's own shape classes answer ahead of both
        lists.put("/Other", ROOT_EMPTY);
        lists.put("/Other, Title, Title", ROOT_EMPTY);
        lists.put("Title, Title, /Other", ROOT_EMPTY);
        lists.put("/Other, /Other", ROOT_EMPTY);
        lists.put("/Item Having [Title] == \"a\"", NO_GROUPS);
        lists.put("/Item Having [Title] == \"a\", Title", NO_GROUPS);
        lists.put("/Item -> Band", NO_GROUPS);
        lists.put("/Item For \"k\"", NO_INDEX_FIELD);
        lists.put("/Item For \"k\", Title", NO_INDEX_FIELD);
        return lists;
    }

    @Test
    void theKernelAsksTheRootAfterADuplicate_andBehindTheOperandsOwnShape() {
        Map<String, String> actual = new LinkedHashMap<>();
        ROOT_LISTS.keySet().forEach(list -> actual.put(list,
                verdict(ROOTS, "FirstFilledValue(" + list + ") == [Title]")));
        assertEquals(ROOT_LISTS, actual);
    }

    /**
     * The same lists through rulekit. An indexed root is refused uncoded, since whether the index resolves is
     * not the record's to see, and the indexed root beside a field belongs to the index gate's own precedence,
     * which this record does not yet mirror.
     */
    @Test
    void rulekitAsksTheRootAfterADuplicate_andBehindTheOperandsOwnShape() {
        BoundModel m = BoundModel.ofJson(ROOTS);
        Map<String, String> expected = new LinkedHashMap<>(ROOT_LISTS);
        expected.put("/Item For \"k\"", UNCODED);
        expected.remove("/Item For \"k\", Title");

        Map<String, String> actual = new LinkedHashMap<>();
        for (String list : expected.keySet()) {
            actual.put(list, clientVerdict(() -> {
                List<FirstFilledOperand> operands = Arrays.stream(list.split(", "))
                        .map(token -> rootOperand(m, token)).toList();
                return new FirstFilledValue(operands.getFirst(), operands.subList(1, operands.size()));
            }));
        }
        assertEquals(expected, actual);
    }

    /** The typed factories route by the first operand's kind, and must do so only after the list's own gates. */
    @Test
    void theTypedFactoriesLetTheListGatesAnswerFirst() {
        BoundModel m = BoundModel.ofJson(MODEL);
        FirstFilledOperand specs = m.firstFilledGroup("/Item/Specs");
        FirstFilledOperand flags = m.firstFilledGroup("/Item/Flags");
        FirstFilledOperand title = FirstFilledOperand.of(m.field("/Item/Title"));
        FirstFilledOperand weight = FirstFilledOperand.of(m.field("/Item/Weight"));
        FirstFilledOperand label = FirstFilledOperand.of(m.field("/Item/Specs/Label"));
        BoundModel roots = BoundModel.ofJson(ROOTS);
        FirstFilledOperand root = roots.firstFilledGroup("/Item");
        FirstFilledOperand rootTitle = FirstFilledOperand.of(roots.field("/Item/Title"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Specs, Specs/Label", LISTS.get("Specs, Specs/Label"));
        expected.put("Specs, Weight, Specs/Label", LISTS.get("Specs, Weight, Specs/Label"));
        expected.put("Flags, Title, Title", LISTS.get("Flags, Title, Title"));
        expected.put("Flags", LISTS.get("Flags"));
        expected.put("/Item", ROOT_LISTS.get("/Item"));
        expected.put("/Item, Title", ROOT_LISTS.get("/Item, Title"));

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Specs, Specs/Label", clientVerdict(() -> Dsl.firstFilledString(specs, label).expression()));
        actual.put("Specs, Weight, Specs/Label",
                clientVerdict(() -> Dsl.firstFilledString(specs, weight, label).expression()));
        actual.put("Flags, Title, Title", clientVerdict(() -> Dsl.firstFilledString(flags, title, title).expression()));
        actual.put("Flags", clientVerdict(() -> Dsl.firstFilledString(flags).expression()));
        actual.put("/Item", clientVerdict(() -> Dsl.firstFilledString(root).expression()));
        actual.put("/Item, Title", clientVerdict(() -> Dsl.firstFilledString(root, rootTitle).expression()));
        assertEquals(expected, actual);
    }

    /** One token of a {@link #ROOT_LISTS} key, built through the typed surface a caller would use. */
    private static FirstFilledOperand rootOperand(BoundModel m, String token) {
        String having = " Having [Title] == \"a\"";
        String category = " -> Band";
        String index = " For \"k\"";
        if (token.endsWith(having)) {
            return target(m, token.substring(0, token.length() - having.length()))
                    .having(m.str("/Item/Title").eq("a"));
        }
        if (token.endsWith(category)) {
            return target(m, token.substring(0, token.length() - category.length()))
                    .withCategory(m.enumOf("/Item/Tier").category("Band"));
        }
        if (token.endsWith(index)) {
            return m.firstFilledGroupFor(token.substring(0, token.length() - index.length()), "k");
        }
        return target(m, token);
    }

    /** A slash-led root-level name is a group, an absolute field path a field, and a bare name a field of Item. */
    private static FirstFilledOperand target(BoundModel m, String name) {
        if (!name.startsWith("/")) {
            return FirstFilledOperand.of(m.field("/Item/" + name));
        }
        return name.indexOf('/', 1) < 0 ? m.firstFilledGroup(name) : FirstFilledOperand.of(m.field(name));
    }

    private static String clientVerdict(Supplier<?> authoring) {
        try {
            authoring.get();
            return ADMITTED;
        } catch (KernelCodeRefusal refusal) {
            return refusal.code().name();
        } catch (IllegalArgumentException uncoded) {
            return UNCODED;
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
    private static final String UNCODED = "uncoded";
    private static final String VARYING = "MVK_VARYING_TYPES_NOT_ALLOWED";
    private static final String BOOLY = "MVK_NO_BOOLY_ALLOWED";
    private static final String DUPLICATE = "MVK_DUPLICATE_PARAM1";
    private static final String CONTAINED = "MVK_DUPLICATE_PARAM2";
    private static final String ROOT = "MVK_ROOT_GROUP_REFERENCED";
    private static final String ROOT_EMPTY = "MVK_ROOT_GROUP_IS_EMPTY";
    private static final String NO_GROUPS = "MVK_NO_GROUPS_ALLOWED";
    private static final String NO_INDEX_FIELD = "MVK_NO_INDEX_FIELD";

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String model, String comparison) {
        ValidationResult r = KernelLaws.checkModel(model, "/Item", "../Title", "FieldFilled(Title) And " + comparison);
        return r.ok() ? ADMITTED : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).map(Object::toString).toList());
    }
}
