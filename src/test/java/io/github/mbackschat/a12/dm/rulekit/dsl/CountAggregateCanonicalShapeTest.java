package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * DEV-API-GAPS DG77 — one count-aggregate text is one {@link CountAggregate} value. A group operand spelled as a
 * {@link GroupRef} and as the scope it renders as are the same operand to A12, so the two spellings must build equal
 * records, or the read path's choice between them leaks into record equality. Each pair's rendered text is compared
 * too, which is what makes it one text. Own domain: a warehouse item.
 */
class CountAggregateCanonicalShapeTest {

    private static final BoundModel M = BoundModel.ofJson(DocumentModelDraft.named("warehouse").locale("en_US")
            .rootGroup("Item", i -> i
                    .field("Title", FieldKind.STRING)
                    .group("Grading", g -> g.field("Grade", FieldKind.NUMBER))
                    .group("Specs", g -> g.field("Label", FieldKind.STRING))
                    .group("Batches", g -> g.repeatable(5).field("Lot", FieldKind.STRING)))
            .build().toJson());

    private static final GroupRef GRADING = M.group("/Item/Grading");
    private static final GroupRef SPECS = M.group("/Item/Specs");

    @Test
    void aFilledGroupCountOverGroupsEqualsTheSameCountOverTheirScopes() {
        assertOneValue(Dsl.numberOfFilledGroups(GRADING, SPECS),
                Dsl.numberOfFilledGroups(M.flattenGroup("/Item/Grading"), M.flattenGroup("/Item/Specs")));
    }

    @Test
    void aRepeatableGroupEqualsItsStarredScope() {
        assertOneValue(Dsl.numberOfFilledGroups(M.group("/Item/Batches")),
                Dsl.numberOfFilledGroups(M.flattenGroup("/Item/Batches*")));
    }

    @Test
    void aFilledFieldCountOverGroupsEqualsTheSameCountOverTheirScopes() {
        assertOneValue(Dsl.numberOfFilledFields(GRADING, SPECS),
                Dsl.numberOfFilledFields(List.of(M.flattenGroup("/Item/Grading"), M.flattenGroup("/Item/Specs"))));
    }

    private static void assertOneValue(NumberExpr fromGroups, NumberExpr fromScopes) {
        DslRenderer renderer = new DslRenderer();
        assertEquals(renderer.render(fromGroups.expression()), renderer.render(fromScopes.expression()));
        assertEquals(fromGroups.expression(), fromScopes.expression());
    }
}
