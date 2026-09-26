package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * A typed count over a repeatable group renders a text A12 admits wherever the rule sits. The renderer relativizes
 * a path against the rule's group, and A12 refuses a star on a parent step, {@code ..*}
 * ({@code MVK_INVALID_WILDCARD_REL}), so the star has to be part of the path the relativizer sees: a starred
 * segment then differs from the base's own segment, and the relativizer climbs past it and names it
 * ({@code ../../Parcels*}), or keeps a root absolute ({@code /Lot*}). Appending the star after relativizing gave
 * {@code ..*} for a rule inside the counted group.
 *
 * <p>Typed rows, because the rendered spelling is the subject: each row is built through the factory a user would
 * reach for, and the kernel column is the oracle. Own domain: an order shipped in parcels, and a repeatable lot.
 */
class StarredCountRenderBaseLawsTest {

    private static final String MODEL = DocumentModelDraft.named("shipping").locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Note", FieldKind.STRING)
                    .group("Parcels", p -> p.repeatable(5).field("Weight", FieldKind.NUMBER)
                            .group("Box", b -> b.field("Tag", FieldKind.STRING))))
            .rootGroup("Lot", l -> l.repeatable(3).field("Serial", FieldKind.STRING)
                    .group("Sub", s -> s.field("Code", FieldKind.STRING)))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(MODEL);

    /** One typed count at one rule group, the text it renders there, and A12's verdict on that text. */
    private record Row(String group, String errorField, Condition condition, String text) {
    }

    private static List<Row> rows() {
        Condition tag = Dsl.fieldFilled(M.field("/Order/Parcels/Box/Tag"));
        Condition weight = Dsl.fieldFilled(M.field("/Order/Parcels/Weight"));
        Condition code = Dsl.fieldFilled(M.field("/Lot/Sub/Code"));
        Condition serial = Dsl.fieldFilled(M.field("/Lot/Serial"));
        var parcels = M.group("/Order/Parcels");
        var lot = M.group("/Lot");
        return List.of(
                new Row("/Order/Parcels/Box", "../Tag",
                        tag.and(Dsl.numberOfFilledGroups(parcels).gt(1)), "NumberOfFilledGroups(../../Parcels*) > 1"),
                new Row("/Order/Parcels/Box", "../Tag",
                        tag.and(Dsl.numberOfFilledFields(parcels).gt(1)), "NumberOfFilledFields(../../Parcels*) > 1"),
                new Row("/Order/Parcels", "../Weight",
                        weight.and(Dsl.numberOfFilledGroups(parcels).gt(1)), "NumberOfFilledGroups(../Parcels*) > 1"),
                new Row("/Lot/Sub", "../Code",
                        code.and(Dsl.numberOfFilledRootGroups(lot).gt(1)), "NumberOfFilledGroups(/Lot*) > 1"),
                new Row("/Lot", "../Serial",
                        serial.and(Dsl.numberOfFilledRootGroups(lot).gt(1)), "NumberOfFilledGroups(/Lot*) > 1"));
    }

    @Test
    void eachCountRendersItsStarredPathAtEveryRuleGroup() {
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        for (Row row : rows()) {
            String rendered = KernelLaws.render(row.group(), row.condition());
            expected.put(row.group() + ": " + row.text(), row.text());
            actual.put(row.group() + ": " + row.text(), rendered.substring(rendered.indexOf(" And ") + 5));
        }
        assertEquals(expected, actual);
    }

    @Test
    void theKernelAdmitsEveryRenderedCount() {
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        for (Row row : rows()) {
            expected.put(row.group() + ": " + row.text(), KernelLaws.ADMITTED);
            actual.put(row.group() + ": " + row.text(),
                    KernelLaws.verdictModel(MODEL, row.group(), row.errorField(), row.condition()));
        }
        assertEquals(expected, actual);
    }
}
