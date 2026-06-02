package io.github.mbackschat.a12.rulekit.dsl;

import io.github.mbackschat.a12.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.rulekit.catalog.OperatorEntry;

/**
 * Renders a {@link Condition} AST to canonical A12 DSL text (proposal §8, layer 3). Worded keywords
 * ({@code FieldFilled}, {@code And}, …) come from the {@link OperatorCatalog} in the chosen
 * {@link ConditionLanguage}, so the same AST renders to EN or DE; comparison symbols are intrinsic.
 *
 * <p>Paths render in absolute form for now. The model+rule-group-aware path policy (relative/short,
 * keyword-segment quoting — §18.9, F3) is a later refinement.
 */
public final class DslRenderer {

    private final OperatorCatalog catalog;
    private final ConditionLanguage language;

    public DslRenderer() {
        this(ConditionLanguage.EN);
    }

    public DslRenderer(ConditionLanguage language) {
        this(OperatorCatalog.load(), language);
    }

    public DslRenderer(OperatorCatalog catalog, ConditionLanguage language) {
        this.catalog = catalog;
        this.language = language;
    }

    public String render(Condition condition) {
        return switch (condition) {
            case FieldPresenceCondition f ->
                    keyword(f.filled() ? "FieldFilled" : "FieldNotFilled") + "(" + f.field().path() + ")";
            case ComparisonCondition c ->
                    render(c.left()) + " " + c.operator().symbol() + " " + render(c.right());
            case LogicCondition l ->
                    child(l, l.left()) + " " + keyword(l.op() == LogicCondition.Op.AND ? "And" : "Or")
                            + " " + child(l, l.right());
        };
    }

    private String child(LogicCondition parent, Condition child) {
        String rendered = render(child);
        // Mixing And with Or requires parentheses; same-operator nesting does not.
        if (child instanceof LogicCondition nested && nested.op() != parent.op()) {
            return "(" + rendered + ")";
        }
        return rendered;
    }

    private String render(Calculation calculation) {
        return switch (calculation) {
            case FieldValue v -> "[" + v.field().path() + "]";
            case NumberLiteral n -> n.value().toPlainString();
            case StringLiteral s -> quote(s.value());
            case EnumValueLiteral e -> quote(e.value());
            case LengthOf l -> keyword("Length") + "(" + l.field().path() + ")";
        };
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private String keyword(String operatorId) {
        OperatorEntry entry = catalog.byId(operatorId)
                .orElseThrow(() -> new IllegalStateException("no catalog entry for operator id: " + operatorId));
        return language == ConditionLanguage.DE ? entry.keywords().de() : entry.keywords().en();
    }
}
