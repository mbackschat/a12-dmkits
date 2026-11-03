package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.edit.GroupEditor;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValue;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * Which enumeration and category sources A12 admits into a text computed field: a grid over plain and category,
 * unkeyed and keyed, and an enumeration with and without display labels. The two enumerations differ only in their
 * labels, so the label is the one variable each pair moves. An assignment walks the comparison's ladder
 * (KERNEL-FINDINGS KF610), and on kernel 31.1.1 that ladder has no display gate, so every row is admitted
 * (KF655); kernel 30.8.1 refused the display-bearing plain and keyed reads (KF384). The keyed rows extend KF610's
 * unkeyed category row to a key-selected read.
 *
 * <p>The typed builder refused all eight by kind (2026-10-25 cold review, R14); it builds every row, which is
 * what A12 admits.
 */
class EnumIntoTextComputationLawsTest {

    private static final String ORDER = DocumentModelDraft.named("order-enum-into-text").locale("en_US")
            .rootGroup("Order", o -> o
                    .stringField("Pick", f -> f.maxLength(20))
                    .enumField("Labelled", e -> e.value("OPEN", l -> l.label("en_US", "Open"))
                            .value("SHIPPED", l -> l.label("en_US", "Shipped")).category("Tier", "High", "Low"))
                    .enumField("Textless", e -> e.value("OPEN").value("SHIPPED").category("Tier", "High", "Low"))
                    .group("Lines", l -> l.repeatable(5).indexField("Sku")
                            .stringField("Sku", f -> f.maxLength(10))
                            .enumField("Labelled", e -> e.value("OPEN", v -> v.label("en_US", "Open"))
                                    .value("SHIPPED", v -> v.label("en_US", "Shipped")).category("Tier", "High", "Low"))
                            .enumField("Textless", e -> e.value("OPEN").value("SHIPPED")
                                    .category("Tier", "High", "Low"))))
            .build().toJson();

    private final GroupEditor order = EditSession.read(ORDER).group("/Order");

    private Map<String, Calculation> grid() {
        Map<String, Calculation> rows = new LinkedHashMap<>();
        for (String enumeration : List.of("Labelled", "Textless")) {
            rows.put(enumeration + " plain", new FieldValue(order.field(enumeration)));
            rows.put(enumeration + " category", order.enumOf(enumeration).category("Tier").value());
            rows.put(enumeration + " keyed", order.enumOf("Lines/" + enumeration).forIndex("a").expression());
            rows.put(enumeration + " keyed category",
                    order.enumOf("Lines/" + enumeration).category("Tier").forIndex("a").value());
        }
        return rows;
    }

    private Computation computation(Calculation operation) {
        return order.computation(order.str("Pick"), b -> b.name("PickComp").calc(operation)
                .message(Dsl.en("The picked value.")).build());
    }

    private static String verdict(Computation computation) {
        var result = new RuleValidator().check(ORDER, computation.draft());
        return result.ok() ? "admitted" : String.join(" + ",
                result.diagnostics().stream().map(d -> d.code().orElse("(no code)")).toList());
    }

    @Test
    void everyEnumerationAndCategorySourceIsAdmitted() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        grid().forEach((row, operation) -> verdicts.put(row, verdict(computation(operation))));
        Map<String, String> expected = new LinkedHashMap<>();
        verdicts.keySet().forEach(row -> expected.put(row, "admitted"));
        assertEquals(expected, verdicts);
    }

    /** The text form of the same rows, so the grid does not rest on the builder's rendering alone. */
    @Test
    void theTextFormsAgree() {
        assertEquals(List.of(), kernel("[/Order/Labelled]"));
        assertEquals(List.of(), kernel("[/Order/Textless]"));
        assertEquals(List.of(), kernel("[/Order/Lines/Labelled For \"a\"]"));
        assertEquals(List.of(), kernel("[/Order/Lines/Labelled -> Tier For \"a\"]"));
    }

    private static List<String> kernel(String operation) {
        var result = new RuleValidator().check(ORDER, new DraftComputation("/Order", "PickComp", "../Pick", null,
                List.of(new DraftComputation.Alternative(null, operation)), List.of()));
        return result.diagnostics().stream().map(d -> d.code().orElse("(no code)")).toList();
    }
}
