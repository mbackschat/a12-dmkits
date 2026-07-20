package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.AggregateForm.COUNT;
import static io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.AggregateForm.DATE_LIST;
import static io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.AggregateForm.FIRST_FILLED;
import static io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.AggregateForm.LIST;
import static io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.AggregateForm.NUMBER_OF_VALUE_IN_FIELDS;
import static io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.AggregateForm.ROOT_GROUP_COUNT;
import static io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.AggregateForm.SUM_OF_PRODUCTS;
import static io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.GroupFillForm.GROUP_LIST_PREDICATE;
import static io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.GroupFillForm.GROUP_PRESENCE;
import static io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.GroupFillForm.GROUP_SCOPED_FIELD_FILL;
import static io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.GroupFillForm.RULE_GROUP_PRESENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.CountAggregate;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateListAggregate;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupScopedFieldFill;
import io.github.mbackschat.a12.dm.rulekit.dsl.ListAggregate;
import io.github.mbackschat.a12.dm.rulekit.dsl.LogicCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberOfValueInFields;
import io.github.mbackschat.a12.dm.rulekit.dsl.Quantifier;
import io.github.mbackschat.a12.dm.rulekit.dsl.RepetitionNotUniqueCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.RootGroupCount;
import io.github.mbackschat.a12.dm.rulekit.dsl.RuleGroupPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndexFieldValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndexValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.SumOfProducts;

class ConditionComplexityEvidenceTest {

    private static final GroupRef ROOT = new GroupRef("/Survey", true);
    private static final GroupRef SECTIONS = new GroupRef("/Survey/Sections", true);
    private static final GroupRef ANSWERS = new GroupRef("/Survey/Sections/Answers", true);
    private static final FieldRef KEY =
            new FieldRef("/Survey/Sections/Answers/Key", ValueKind.STRING);
    private static final FieldRef SCORE =
            new FieldRef("/Survey/Sections/Answers/Score", ValueKind.NUMBER);
    private static final FieldRef FALLBACK =
            new FieldRef("/Survey/FallbackScore", ValueKind.NUMBER);
    private static final FieldRef WEIGHT =
            new FieldRef("/Survey/Sections/Answers/Weight", ValueKind.NUMBER);
    private static final FieldRef OBSERVED =
            new FieldRef("/Survey/Sections/Answers/Observed", ValueKind.DATE);

    @Test
    void referencesAreDistinctKeySortedAndImmutable() {
        var condition = new LogicCondition(
                LogicCondition.Op.AND,
                new FieldPresenceCondition(SCORE, true),
                new LogicCondition(
                        LogicCondition.Op.AND,
                        new FieldPresenceCondition(SCORE, false),
                        new GroupPresenceCondition(ANSWERS, true)));

        var references = ConditionComplexity.of(condition).references();

        assertEquals(List.of(SCORE.path()), List.copyOf(references.fieldPaths()));
        assertEquals(List.of(ANSWERS.path()), List.copyOf(references.groupPaths()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> references.fieldPaths().add(KEY.path()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> references.groupPaths().add(SECTIONS.path()));
    }

    @Test
    void everyGroupFillShapeRetainsItsConcreteTargetWhenOneExists() {
        var directAndRuleGroup = new LogicCondition(
                LogicCondition.Op.AND,
                new GroupPresenceCondition(ANSWERS, true),
                new RuleGroupPresenceCondition(true));

        var contextual = ConditionComplexity.of(directAndRuleGroup, SECTIONS.path());

        assertEquals(
                List.of(
                        new ConditionComplexity.GroupFillOccurrence(
                                GROUP_PRESENCE, Optional.of(ANSWERS.path())),
                        new ConditionComplexity.GroupFillOccurrence(
                                RULE_GROUP_PRESENCE, Optional.of(SECTIONS.path()))),
                contextual.groupFills().occurrences());
        assertEquals(
                List.of(SECTIONS.path(), ANSWERS.path()),
                List.copyOf(contextual.references().groupPaths()));

        var contextFree = ConditionComplexity.of(new RuleGroupPresenceCondition(false));
        assertEquals(
                List.of(new ConditionComplexity.GroupFillOccurrence(
                        RULE_GROUP_PRESENCE, Optional.empty())),
                contextFree.groupFills().occurrences());
        assertEquals(List.of(), List.copyOf(contextFree.references().groupPaths()));

        var scoped = ConditionComplexity.of(new GroupScopedFieldFill(
                Quantifier.ALL_FIELDS_FILLED,
                List.of(
                        new GroupListRef(SECTIONS, List.of()),
                        new GroupListRef(ANSWERS, List.of(SECTIONS, ANSWERS)))));
        assertEquals(
                List.of(
                        new ConditionComplexity.GroupFillOccurrence(
                                GROUP_SCOPED_FIELD_FILL, Optional.of(SECTIONS.path())),
                        new ConditionComplexity.GroupFillOccurrence(
                                GROUP_SCOPED_FIELD_FILL, Optional.of(ANSWERS.path()))),
                scoped.groupFills().occurrences());
        assertThrows(
                UnsupportedOperationException.class,
                () -> scoped.groupFills().occurrences().add(
                        scoped.groupFills().occurrences().getFirst()));

        var starredPredicate = ConditionComplexity.of(Dsl.noGroupFilled(
                new GroupListRef(ANSWERS, List.of(SECTIONS, ANSWERS))));
        assertEquals(
                List.of(new ConditionComplexity.GroupFillOccurrence(
                        GROUP_LIST_PREDICATE, Optional.of(ANSWERS.path()))),
                starredPredicate.groupFills().occurrences());
        assertEquals(
                List.of(ANSWERS.path()),
                List.copyOf(starredPredicate.references().groupPaths()));
        assertTrue(starredPredicate.constructs().contains(
                ConditionComplexity.Construct.GROUP_FILL));
    }

    @Test
    void semanticIndexEvidenceNamesTheResolvedCellRatherThanItsLookupKey() {
        var fieldKeyed = ConditionComplexity.of(new SemanticIndexFieldValue(SCORE, KEY));
        var literalKeyed = ConditionComplexity.of(new SemanticIndexValue(SCORE, "high"));

        assertEquals(
                List.of(new ConditionComplexity.SemanticIndexOccurrence(SCORE.path())),
                fieldKeyed.semanticIndexes().occurrences());
        assertEquals(
                List.of(KEY.path(), SCORE.path()),
                List.copyOf(fieldKeyed.references().fieldPaths()));
        assertEquals(
                List.of(new ConditionComplexity.SemanticIndexOccurrence(SCORE.path())),
                literalKeyed.semanticIndexes().occurrences());
        assertThrows(
                UnsupportedOperationException.class,
                () -> fieldKeyed.semanticIndexes().occurrences().clear());
    }

    @Test
    void uniquenessEvidenceRetainsCompositeKeysAndTheOptionalFromScope() {
        var uniqueness = new RepetitionNotUniqueCondition(
                List.of(KEY, SCORE), Optional.of(SECTIONS));

        var result = ConditionComplexity.of(uniqueness);

        assertEquals(
                List.of(new ConditionComplexity.UniquenessOccurrence(
                        List.of(KEY.path(), SCORE.path()), Optional.of(SECTIONS.path()))),
                result.uniquenessChecks().occurrences());
        assertEquals(
                List.of(KEY.path(), SCORE.path()),
                List.copyOf(result.references().fieldPaths()));
        assertEquals(
                List.of(),
                List.copyOf(result.references().groupPaths()),
                "the @From scope controls the uniqueness domain but is not a directly read entity");
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.uniquenessChecks()
                        .occurrences()
                        .getFirst()
                        .keyFieldPaths()
                        .add(FALLBACK.path()));
    }

    @Test
    void everyAggregateFormRetainsOnlyItsActualCellOperands() {
        var filteredScores = new FieldListRef(SCORE, List.of(SECTIONS, ANSWERS))
                .having(new FieldPresenceCondition(KEY, true));
        var weights = new FieldListRef(WEIGHT, List.of(SECTIONS, ANSWERS));
        var observed = new FieldListRef(OBSERVED, List.of(SECTIONS, ANSWERS));

        assertAggregate(
                new ListAggregate(ListAggregate.AggOp.SUM, List.of(filteredScores)),
                LIST,
                List.of(SCORE.path()));
        assertAggregate(
                new CountAggregate(
                        CountAggregate.CountOp.FILLED_FIELDS,
                        List.of(SCORE, FALLBACK),
                        List.of()),
                COUNT,
                List.of(SCORE.path(), FALLBACK.path()));
        assertAggregate(
                new DateListAggregate(DateListAggregate.Mode.LATEST, observed),
                DATE_LIST,
                List.of(OBSERVED.path()));
        assertAggregate(
                new FirstFilledValue(filteredScores, List.of(FALLBACK)),
                FIRST_FILLED,
                List.of(SCORE.path(), FALLBACK.path()));
        assertAggregate(
                new NumberOfValueInFields(NumberLiteral.of(1), List.of(SCORE, FALLBACK)),
                NUMBER_OF_VALUE_IN_FIELDS,
                List.of(SCORE.path(), FALLBACK.path()));
        assertAggregate(
                new SumOfProducts(filteredScores, weights),
                SUM_OF_PRODUCTS,
                List.of(SCORE.path(), WEIGHT.path()));
        assertAggregate(new RootGroupCount(ROOT), ROOT_GROUP_COUNT, List.of());

        var filtered = ConditionComplexity.of(
                new ListAggregate(ListAggregate.AggOp.SUM, List.of(filteredScores)));
        assertEquals(
                List.of(KEY.path(), SCORE.path()),
                List.copyOf(filtered.references().fieldPaths()),
                "the filter remains a reference, but not an aggregated cell");
        assertThrows(
                UnsupportedOperationException.class,
                () -> filtered.aggregates()
                        .occurrences()
                        .getFirst()
                        .operandFieldPaths()
                        .add(KEY.path()));
    }

    private static void assertAggregate(
            Calculation calculation,
            ConditionComplexity.AggregateForm form,
            List<String> operandFieldPaths) {
        var result = ConditionComplexity.of(calculation);
        assertEquals(
                List.of(new ConditionComplexity.AggregateOccurrence(form, operandFieldPaths)),
                result.aggregates().occurrences());
    }
}
