package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.CollectionKind.FILLED_GROUP_REPETITIONS;
import static io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.CollectionKind.FLATTENED_FIELD;
import static io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.CollectionKind.FLATTENED_GROUP;
import static io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.CollectionKind.ROOT_GROUP_REPETITIONS;
import static io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.OuterCorrelation.CURRENT_REPETITION;
import static io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.OuterCorrelation.ENUM_CATEGORY;
import static io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.OuterCorrelation.FIELD_LIST;
import static io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.OuterCorrelation.FIELD_OPERAND;
import static io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.OuterCorrelation.FIELD_VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.Arithmetic;
import io.github.mbackschat.a12.dm.rulekit.dsl.ComparisonCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.CountAggregate;
import io.github.mbackschat.a12.dm.rulekit.dsl.CurrentRepetition;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumCategoryAccess;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListPredicate;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupScopedFieldFill;
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedFieldValueAsString;
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.LogicCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.Quantifier;
import io.github.mbackschat.a12.dm.rulekit.dsl.RootGroupCount;
import io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndexFieldValue;

class ConditionComplexityTest {

    private static final GroupRef ROOT = new GroupRef("/Survey", true);
    private static final GroupRef SECTIONS = new GroupRef("/Survey/Sections", true);
    private static final GroupRef ANSWERS = new GroupRef("/Survey/Sections/Answers", true);
    private static final FieldRef SCORE =
            new FieldRef("/Survey/Sections/Answers/Score", ValueKind.NUMBER);
    private static final FieldRef KEY =
            new FieldRef("/Survey/Sections/Answers/Key", ValueKind.STRING);

    @Test
    void aTenThousandNodeLeftDeepBooleanTreeIsStackSafe() {
        Condition condition = new FieldPresenceCondition(SCORE, true);
        for (int i = 0; i < 10_000; i++) {
            condition = new LogicCondition(
                    LogicCondition.Op.AND, condition, new FieldPresenceCondition(SCORE, true));
        }

        var result = ConditionComplexity.of(condition);

        assertEquals(20_001, result.tree().nodeCount());
        assertEquals(10_001, result.tree().depth());
        assertEquals(10_001, result.tree().maxBooleanArity());
        assertEquals(10_000, result.operators().catalog().counts().get("And"));
        assertEquals(10_001, result.operators().catalog().counts().get("FieldFilled"));
    }

    @Test
    void onlyExplicitCollectionOperandsCountAsScansAndEveryFirstFilledOperandIsVisited() {
        var scalar = new ComparisonCondition(new FieldValue(SCORE), io.github.mbackschat.a12.dm.rulekit.dsl.CompareOp.GT,
                NumberLiteral.of(0));
        assertEquals(0, ConditionComplexity.of(scalar).collections().total());

        var deepList = new FieldListRef(SCORE, List.of(SECTIONS, ANSWERS))
                .having(new FieldPresenceCondition(KEY, true));
        FieldOperand plainTail = new FieldRef("/Survey/FallbackScore", ValueKind.NUMBER);
        var shallowList = new FieldListRef(
                new FieldRef("/Survey/Sections/Score", ValueKind.NUMBER), List.of(SECTIONS));
        var first = new FirstFilledValue(deepList, List.of(plainTail, shallowList));

        var result = ConditionComplexity.of(first);

        assertEquals(2, result.collections().counts().get(FLATTENED_FIELD));
        assertEquals(2, result.collections().total());
        assertEquals(2, result.collections().maxStarDepth());
        assertEquals(3, result.tree().maxVariadicArity());
        assertEquals(
                5,
                result.tree().nodeCount(),
                "FirstFilledValue, all three authored operands, and the Having condition");
        assertEquals(1, result.operators().catalog().counts().get("Having"));
        assertTrue(result.constructs().contains(ConditionComplexity.Construct.FILTER));
        assertEquals(
                List.of(
                        new ConditionComplexity.CollectionOccurrence(
                                FLATTENED_FIELD,
                                SCORE.path(),
                                List.of(SECTIONS.path(), ANSWERS.path()),
                                OptionalInt.of(1)),
                        new ConditionComplexity.CollectionOccurrence(
                                FLATTENED_FIELD,
                                "/Survey/Sections/Score",
                                List.of(SECTIONS.path()),
                                OptionalInt.empty())),
                result.collections().occurrences());
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.collections().occurrences().add(
                        result.collections().occurrences().getFirst()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.collections()
                        .occurrences()
                        .getFirst()
                        .repeatableGroupPaths()
                        .add("/Survey/Other"));
    }

    @Test
    void everyRenderedOuterCorrelationFormIsCounted() {
        var fieldOperand = SCORE.outer();
        var fieldList = new FieldListRef(SCORE, List.of(SECTIONS, ANSWERS)).outer();
        Condition operands = new FieldListPredicate(
                Quantifier.AT_LEAST_ONE_FIELD_FILLED, List.of(fieldOperand, fieldList));
        Condition value = new ComparisonCondition(
                new FieldValue(SCORE, true),
                io.github.mbackschat.a12.dm.rulekit.dsl.CompareOp.EQ,
                new EnumCategoryAccess(KEY, "Band", true));
        Condition repetition = new ComparisonCondition(
                new CurrentRepetition(ANSWERS, true),
                io.github.mbackschat.a12.dm.rulekit.dsl.CompareOp.GT,
                NumberLiteral.of(0));
        Condition all = new LogicCondition(
                LogicCondition.Op.AND,
                new FieldPresenceCondition(KEY.outer(), true),
                new LogicCondition(
                        LogicCondition.Op.AND,
                        operands,
                        new LogicCondition(LogicCondition.Op.AND, value, repetition)));

        var correlations = ConditionComplexity.of(all).correlations().counts();

        assertEquals(2, correlations.get(FIELD_OPERAND),
                "bare correlated presence and bare correlated list-slot field");
        assertEquals(1, correlations.get(FIELD_LIST));
        assertEquals(1, correlations.get(FIELD_VALUE));
        assertEquals(1, correlations.get(ENUM_CATEGORY));
        assertEquals(1, correlations.get(CURRENT_REPETITION));
        assertEquals(
                List.of(
                        new ConditionComplexity.CorrelationOccurrence(FIELD_OPERAND, KEY.path()),
                        new ConditionComplexity.CorrelationOccurrence(FIELD_OPERAND, SCORE.path()),
                        new ConditionComplexity.CorrelationOccurrence(FIELD_LIST, SCORE.path()),
                        new ConditionComplexity.CorrelationOccurrence(FIELD_VALUE, SCORE.path()),
                        new ConditionComplexity.CorrelationOccurrence(ENUM_CATEGORY, KEY.path()),
                        new ConditionComplexity.CorrelationOccurrence(
                                CURRENT_REPETITION, ANSWERS.path())),
                ConditionComplexity.of(all).correlations().occurrences());
        assertThrows(
                UnsupportedOperationException.class,
                () -> ConditionComplexity.of(all)
                        .correlations()
                        .occurrences()
                        .add(new ConditionComplexity.CorrelationOccurrence(
                                FIELD_VALUE, "/Survey/Other")));
    }

    @Test
    void groupCollectionsDistinguishFlattenedScopesAndFilledGroupCounts() {
        var groupFill = new GroupScopedFieldFill(
                Quantifier.ALL_FIELDS_FILLED,
                List.of(
                        new GroupListRef(ANSWERS, List.of()),
                        new GroupListRef(ANSWERS, List.of(SECTIONS, ANSWERS))));
        var fillResult = ConditionComplexity.of(groupFill);
        assertEquals(1, fillResult.collections().counts().get(FLATTENED_GROUP));
        assertEquals(2, fillResult.collections().maxStarDepth());
        assertEquals(
                List.of(
                        new ConditionComplexity.CollectionOccurrence(
                                FLATTENED_GROUP,
                                ANSWERS.path(),
                                List.of(SECTIONS.path(), ANSWERS.path()),
                                OptionalInt.empty())),
                fillResult.collections().occurrences());

        var filledGroups =
                new CountAggregate(CountAggregate.CountOp.FILLED_GROUPS, List.of(), List.of(ANSWERS));
        var filledGroupsResult = ConditionComplexity.of(filledGroups);
        assertEquals(1, filledGroupsResult.collections().counts().get(FILLED_GROUP_REPETITIONS));
        assertEquals(
                List.of(
                        new ConditionComplexity.CollectionOccurrence(
                                FILLED_GROUP_REPETITIONS,
                                ANSWERS.path(),
                                List.of(ANSWERS.path()),
                                OptionalInt.empty())),
                filledGroupsResult.collections().occurrences());

        var rootResult = ConditionComplexity.of(new RootGroupCount(ROOT));
        assertEquals(1, rootResult.collections().counts().get(ROOT_GROUP_REPETITIONS));
        assertEquals(
                List.of(
                        new ConditionComplexity.CollectionOccurrence(
                                ROOT_GROUP_REPETITIONS,
                                ROOT.path(),
                                List.of(ROOT.path()),
                                OptionalInt.empty())),
                rootResult.collections().occurrences());
    }

    @Test
    void indexedPresenceAndIndexedStringVisitTheirNestedSemanticIndexNode() {
        var index = new SemanticIndexFieldValue(SCORE, KEY);

        var presence = ConditionComplexity.of(new IndexedPresenceCondition(index, true));
        assertEquals(2, presence.tree().nodeCount());
        assertEquals(1, presence.operators().catalog().counts().get("FieldFilled"));
        assertEquals(1, presence.operators().catalog().counts().get("SemanticIndex"));
        assertTrue(presence.constructs().contains(ConditionComplexity.Construct.SEMANTIC_INDEX));

        var stringValue = ConditionComplexity.of(new IndexedFieldValueAsString(index));
        assertEquals(2, stringValue.tree().nodeCount());
        assertEquals(1, stringValue.operators().catalog().counts().get("FieldValueAsString"));
        assertEquals(1, stringValue.operators().catalog().counts().get("SemanticIndex"));
    }

    @Test
    void operatorHistogramsAreKeySortedAndImmutable() {
        var arithmetic = new Arithmetic(
                Arithmetic.Op.ADD,
                new Arithmetic(Arithmetic.Op.MULTIPLY, NumberLiteral.of(2), NumberLiteral.of(3)),
                NumberLiteral.of(4));
        Condition condition = new LogicCondition(
                LogicCondition.Op.OR,
                new ComparisonCondition(
                        arithmetic,
                        io.github.mbackschat.a12.dm.rulekit.dsl.CompareOp.GT,
                        NumberLiteral.of(0)),
                new FieldPresenceCondition(SCORE, true));

        var result = ConditionComplexity.of(condition);

        assertEquals(
                List.of("FieldFilled", "GreaterThan", "Or"),
                List.copyOf(result.operators().catalog().counts().keySet()));
        assertEquals(
                List.of("*", "+"),
                List.copyOf(result.operators().intrinsic().counts().keySet()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.operators().catalog().counts().put("Z", 1));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.operators().intrinsic().counts().put("/", 1));
    }
}
