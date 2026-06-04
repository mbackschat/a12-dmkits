package io.github.mbackschat.a12.rulekit.dsl;

import io.github.mbackschat.a12.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.rulekit.catalog.OperatorEntry;

/**
 * Renders a {@link Condition} AST to canonical A12 DSL text (proposal §8, component 4). Every
 * operator keyword — worded ({@code FieldFilled}, {@code And}/{@code Und}) or symbolic
 * ({@code <=}) — is looked up from the {@link OperatorCatalog} by the node's own
 * {@link Condition#operatorId() operator-id} (D14), so the same AST renders to EN or DE and no
 * keyword knowledge lives outside the catalog.
 *
 * <p>Paths render in absolute form for now. The model+rule-group-aware path policy (relative/short,
 * keyword-segment quoting, honoring ingested {@link PathStyle} hints — §18 fork 9, F3a) is a later
 * refinement.
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
            case FieldPresenceCondition f -> keyword(f.operatorId()) + "(" + f.field().path() + ")";
            case FieldListPredicate p -> keyword(p.operatorId()) + "("
                    + p.fields().stream().map(FieldRef::path).collect(java.util.stream.Collectors.joining(", "))
                    + ")";
            case ComparisonCondition c ->
                    render(c.left()) + " " + keyword(c.operatorId()) + " " + render(c.right());
            case LogicCondition l ->
                    child(l, l.left()) + " " + keyword(l.operatorId()) + " " + child(l, l.right());
            case GroupPresenceCondition g -> keyword(g.operatorId()) + "(" + g.group().path() + ")";
            case GroupListPredicate p -> keyword(p.operatorId()) + "("
                    + p.groups().stream().map(GroupRef::path).collect(java.util.stream.Collectors.joining(", "))
                    + ")";
            case PatternCondition p ->
                    "[" + p.field().path() + "] " + keyword(p.operatorId()) + " " + quote(p.regex());
            case ValueListCondition v -> keyword(v.operatorId()) + "(" + v.field().path() + ", "
                    + v.values().stream().map(this::render).collect(java.util.stream.Collectors.joining(", "))
                    + ")";
            case FieldListValueListCondition v -> keyword(v.operatorId()) + "("
                    + v.fields().stream().map(FieldRef::path).collect(java.util.stream.Collectors.joining(", "))
                    // 'In' is a language-independent grammar literal (inOp), like the comparison symbols
                    + " In "
                    + v.values().stream().map(this::render).collect(java.util.stream.Collectors.joining(", "))
                    + ")";
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

    /** Renders a value expression — e.g. a computation slot's {@code operation}. */
    public String render(Calculation calculation) {
        return switch (calculation) {
            case FieldValue v -> "[" + v.field().path() + "]";
            case NumberLiteral n -> n.value().toPlainString();
            case StringLiteral s -> quote(s.value());
            case EnumValueLiteral e -> quote(e.value());
            case BoolLiteral b -> keyword(b.operatorId());
            case LengthOf l -> keyword(l.operatorId()) + "(" + l.field().path() + ")";
            case EnumCategoryAccess c ->
                    "[" + c.field().path() + " " + keyword(c.operatorId()) + " " + c.category() + "]";
        };
    }

    private static String quote(String value) {
        // Kernel lexer (verified 2026-06-04): StringLiteral : '"' ( '""' | ~["] )* '"' — embedded
        // quotes are doubled; backslash is a plain character (no escape sequences).
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private String keyword(String operatorId) {
        OperatorEntry entry = catalog.byId(operatorId)
                .orElseThrow(() -> new IllegalStateException("no catalog entry for operator id: " + operatorId));
        return language == ConditionLanguage.DE ? entry.keywords().de() : entry.keywords().en();
    }
}
