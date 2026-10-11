package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListValueListCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionReferences;
import io.github.mbackschat.a12.dm.rulekit.dsl.OperatorUsage;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.edit.GroupEditor;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DuplicateOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.ValueListOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.ValueListQuantifier;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

class IndexedValueListGroupLawsTest {
    private enum Side { FIELDS, VALUES, LITERAL_VALUES }
    private final EditSession session = EditSession.read(ModelDrafts.shipping().toJson());
    private final GroupEditor order = session.group("/Order");
    private final StringRef title = order.str("Title");

    private Rule rule(String name, Condition condition) {
        return order.rule(r -> r.name(name).code(name).errorField(title)
                .when(condition).message(Dsl.en("Check selected list.")).build());
    }

    @Test
    void homogeneousGroupsAreAdmittedOnBothSidesAndReadBackWithTheirSelection() {
        order.numberField("Amount", n -> n.fractionDigits(0));
        String model = session.toJson();
        var guard = rule("Probe", title.filled());
        var verdicts = new LinkedHashMap<String, List<String>>();
        var expected = new LinkedHashMap<String, List<String>>();
        var authors = new ArrayList<java.util.function.Supplier<Condition>>();
        var admittedConditions = new ArrayList<String>();
        for (boolean numeric : List.of(false, true)) {
            var scope = order.flattenGroup(numeric ? "Packages/Dimensions" : "Packages/Parcels*");
            ValueListOperand witness = numeric ? order.num("Amount").field() : title.field();
            var plain = order.flattenGroup(numeric ? "Packages*/Dimensions" : "Packages*/Parcels*");
            for (DuplicateOperand group : List.of(scope.forIndex("P1"), scope.forIndex(title.field()), plain)) {
                for (var side : Side.values()) {
                    for (var quantifier : List.of(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED,
                            ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED)) {
                        String left = side == Side.VALUES ? witness.renderedPath() : group.renderedPath();
                        String right = switch (side) {
                            case FIELDS -> witness.renderedPath();
                            case VALUES -> group.renderedPath();
                            case LITERAL_VALUES -> numeric ? "\"7\"" : "\"watched\"";
                        };
                        String condition = quantifier.operatorId() + "(" + left + " In " + right + ")";
                        // The unrepresentable carrier must reach the kernel before the typed arm is widened (SPEC16).
                        var result = KernelLaws.checkModel(model, order.ref().path().value(), guard.errorEntityRelPath(),
                                guard.render() + " And " + condition);
                        verdicts.put(condition, result.diagnostics().stream().map(d -> d.code().orElse("(uncoded)")).toList());
                        boolean rejectedLiteralKind = numeric && side == Side.LITERAL_VALUES;
                        expected.put(condition, rejectedLiteralKind ? List.of("MVK_INVALID_TYPES_FOR_COMPARISON") : List.of());
                        if (rejectedLiteralKind) continue;
                        admittedConditions.add(guard.render() + " And "
                                + condition.replace(order.ref().path().value() + "/", ""));
                        authors.add(() -> {
                            var operand = assertInstanceOf(ValueListOperand.class, group);
                            if (side == Side.LITERAL_VALUES) {
                                var literal = new StringLiteral(numeric ? "7" : "watched");
                                return new FieldListValueListCondition(quantifier, List.of(operand), List.of(literal));
                            }
                            ValueListOperand first = side == Side.FIELDS ? operand : witness;
                            var values = List.of(side == Side.FIELDS ? witness : operand);
                            return switch (quantifier) {
                                case AT_LEAST_ONE_FIELD_VALUE_INCLUDED -> Dsl.atLeastOneFieldValueIncludedInValueList(values, first);
                                case NO_FIELD_VALUE_INCLUDED -> Dsl.noFieldValueIncludedInValueList(values, first);
                                case NOT_ALL_FIELD_VALUES_INCLUDED -> Dsl.notAllFieldValuesIncludedInValueList(values, first);
                                default -> throw new AssertionError(quantifier);
                            };
                        });
                    }
                }
            }
        }
        assertEquals(54, verdicts.size());
        assertEquals(expected, verdicts);
        var rules = new ArrayList<Rule>();
        for (var author : authors) {
            var authored = rule("Selected" + rules.size(), title.filled().and(author.get()));
            assertEquals(admittedConditions.get(rules.size()), authored.render());
            rules.add(authored);
            session.addRule(m -> authored);
        }
        var adapter = KernelAdapter.ofJson(session.toJson());
        for (var authored : rules) {
            var read = assertInstanceOf(ReadResult.Converted.class, adapter.read(order.ref().path().value() + "/" + authored.name()));
            assertEquals(authored.render(), rule(authored.name(), read.condition()).render());
        }
    }

    @Test
    void listSelectionRetainsItsReferencesDiscoveryAndLocalizedExplanation() {
        order.stringField("Wanted", f -> {});
        var wanted = order.str("Wanted");
        var scope = order.flattenGroup("Packages/Parcels*");
        var selected = scope.forIndex(wanted.field());
        var condition = Dsl.atLeastOneFieldValueIncludedInValueList(List.of(title.field()), selected);
        var references = ConditionReferences.of(condition);
        assertEquals(Set.of(scope.fieldPath()), references.groupPaths());
        assertEquals(Set.of(title.field().path().value(), wanted.field().path().value()), references.fieldPaths());
        assertTrue(OperatorUsage.of(condition).contains("SemanticIndex"));
        var authored = rule("Metadata", condition);
        assertTrue(authored.render(ConditionLanguage.EN).contains(" For Wanted"));
        assertTrue(authored.render(ConditionLanguage.DE).contains(" Fuer Wanted"));
        var explanation = new ModelContext(BoundModel.ofJson(session.toJson())).explain(condition);
        assertEquals(List.of("SemanticIndex"), explanation.tree().children().stream().map(n -> n.operator()).toList());
        assertEquals(selected.renderedPath(), explanation.tree().children().getFirst().text());
    }

}
