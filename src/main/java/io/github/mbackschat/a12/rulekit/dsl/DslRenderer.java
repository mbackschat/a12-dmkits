package io.github.mbackschat.a12.rulekit.dsl;

import io.github.mbackschat.a12.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.rulekit.catalog.OperatorEntry;
import io.github.mbackschat.a12.rulekit.validate.Span;

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

    /**
     * The rendered text together with the character span each {@link Condition} node occupies in it
     * (the F4a emit-map for span-pointing). The {@code text} is byte-identical to {@link #render};
     * only {@code LogicCondition}'s compositional layout is walked here, every other (leaf) node
     * reuses {@link #render} so there is no second copy of the rendering rules.
     */
    public Rendered renderWithSpans(Condition condition) {
        StringBuilder out = new StringBuilder();
        java.util.Map<Condition, Span> spans = new java.util.IdentityHashMap<>();
        emit(condition, out, spans);
        return new Rendered(out.toString(), spans);
    }

    /** A rendered condition plus the per-node spans into its text (identity-keyed). */
    public record Rendered(String text, java.util.Map<Condition, Span> spans) {
    }

    private void emit(Condition condition, StringBuilder out, java.util.Map<Condition, Span> spans) {
        int start = out.length();
        if (condition instanceof LogicCondition logic) {
            emitChild(logic, logic.left(), out, spans);
            out.append(" ").append(keyword(logic.operatorId())).append(" ");
            emitChild(logic, logic.right(), out, spans);
        } else {
            out.append(render(condition));
        }
        spans.put(condition, new Span(start, out.length()));
    }

    private void emitChild(LogicCondition parent, Condition child, StringBuilder out,
            java.util.Map<Condition, Span> spans) {
        boolean parenthesize = child instanceof LogicCondition nested && nested.op() != parent.op();
        if (parenthesize) {
            out.append("(");
        }
        emit(child, out, spans);
        if (parenthesize) {
            out.append(")");
        }
    }

    public String render(Condition condition) {
        return switch (condition) {
            case FieldPresenceCondition f -> keyword(f.operatorId()) + "(" + f.field().path() + ")";
            case FieldListPredicate p -> keyword(p.operatorId()) + "("
                    + p.fields().stream().map(this::operand)
                            .collect(java.util.stream.Collectors.joining(", "))
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
            case CustomConditionRef c -> keyword(c.operatorId()) + " " + c.name();
            case RepetitionNotUniqueCondition r -> keyword(r.operatorId()) + "("
                    + r.keys().stream().map(FieldRef::path).collect(java.util.stream.Collectors.joining(", "))
                    + r.scope().map(s -> " " + keyword("FromScope") + " " + s.path()).orElse("")
                    + ")";
            case FieldListValueListCondition v -> keyword(v.operatorId()) + "("
                    + v.fields().stream().map(this::operand)
                            .collect(java.util.stream.Collectors.joining(", "))
                    // 'In' is a language-independent grammar literal (inOp), like the comparison symbols
                    + " In "
                    + v.values().stream().map(this::render).collect(java.util.stream.Collectors.joining(", "))
                    + ")";
        };
    }

    /** A field-list operand: its path, plus a {@code Having <filter>} clause for a filtered list. */
    private String operand(FieldOperand op) {
        if (op instanceof FieldListRef list && list.filter().isPresent()) {
            return list.renderedPath() + " " + keyword("Having") + " " + render(list.filter().get());
        }
        return op.renderedPath();
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
            case ListAggregate a -> keyword(a.operatorId()) + "("
                    + a.operands().stream().map(this::operand)
                            .collect(java.util.stream.Collectors.joining(", "))
                    + ")";
            case CountAggregate a -> keyword(a.operatorId()) + "("
                    + java.util.stream.Stream.concat(
                            a.fields().stream().map(this::operand),
                            a.groups().stream().map(GroupRef::path))
                    .collect(java.util.stream.Collectors.joining(", "))
                    + ")";
            case CurrentRepetition r -> keyword(r.operatorId()) + "(" + r.group().path() + ")";
            case SemanticIndexValue v ->
                    "[" + v.field().path() + " " + keyword(v.operatorId()) + " " + quote(v.indexValue()) + "]";
            case Today t -> keyword(t.operatorId());
            case DateDifference d -> keyword(d.operatorId())
                    + "(" + renderArg(d.left()) + ", " + renderArg(d.right()) + ")";
            case DateShift s -> keyword(s.operatorId())
                    + "(" + renderArg(s.date()) + ", " + s.amount() + ")";
        };
    }

    /**
     * Renders a value in <em>argument position</em>: a date/field reference is the bare entity path
     * (as in {@code DifferenceInDays(A, B)}), not the bracketed value form used in comparisons.
     */
    private String renderArg(Calculation value) {
        if (value instanceof FieldValue v) {
            return v.field().path();
        }
        return render(value);
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
