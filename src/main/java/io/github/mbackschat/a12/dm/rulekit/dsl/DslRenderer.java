package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry;
import io.github.mbackschat.a12.dm.rulekit.validate.Span;

/**
 * Renders a {@link Condition} AST to canonical A12 DSL text (proposal §8, component 4). Every
 * operator keyword — worded ({@code FieldFilled}, {@code And}/{@code Und}) or symbolic
 * ({@code <=}) — is looked up from the {@link OperatorCatalog} by the node's own
 * {@link Condition#operatorId() operator-id} (D14), so the same AST renders to EN or DE and no
 * keyword knowledge lives outside the catalog.
 *
 * <p>Path policy (§18 fork 9, F3a): with a {@code baseGroup} set the renderer emits paths
 * <em>relative</em> to the rule's group (bare under the group, {@code ../} up to a sibling — both
 * kernel-verified, KERNEL-FINDINGS §paths), which is how {@code Rule}/{@code Computation} render;
 * without one it emits absolute paths. Keyword-segment quoting and honoring ingested
 * {@link PathStyle} hints are the remaining refinements.
 */
public final class DslRenderer {

    private final OperatorCatalog catalog;
    private final ConditionLanguage language;
    private final String baseGroup;
    private final java.util.Set<String> keywords;

    public DslRenderer() {
        this(ConditionLanguage.EN);
    }

    public DslRenderer(ConditionLanguage language) {
        this(OperatorCatalog.load(), language, null);
    }

    /** A renderer that emits paths relative to {@code baseGroup} (fork #9 / F3a); {@code null} = absolute. */
    public DslRenderer(ConditionLanguage language, String baseGroup) {
        this(OperatorCatalog.load(), language, baseGroup);
    }

    public DslRenderer(OperatorCatalog catalog, ConditionLanguage language) {
        this(catalog, language, null);
    }

    public DslRenderer(OperatorCatalog catalog, ConditionLanguage language, String baseGroup) {
        this.catalog = catalog;
        this.language = language;
        this.baseGroup = baseGroup;
        this.keywords = catalog.entries().stream()
                .flatMap(e -> java.util.stream.Stream.of(e.keywords().en(), e.keywords().de()))
                .filter(k -> k != null && !k.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * A path in the configured form: relative to the rule's group when a {@code baseGroup} is set
     * (fork #9 — the kernel's preferred, readable form, verified accepted: bare under the group,
     * {@code ../} up to a sibling), else the absolute path. Both are kernel-valid; relative is the
     * authoring norm.
     */
    private String path(String absolute) {
        String form = baseGroup == null ? absolute : relativize(baseGroup, absolute);
        return quoteKeywordSegments(form);
    }

    /**
     * Single-quote any path segment that collides with a DSL keyword (fork #9 / F3a) — the kernel
     * rejects an unquoted keyword-named entity with {@code MVK_UNEXPECTED_TOKEN} (KERNEL-FINDINGS
     * §paths), and {@code '<name>'} is the grammar's escape (NameOhnePunktMitQuotes). Detection uses
     * the catalog's operator keywords; the full lexer keyword set rides the N3 parity anchor.
     */
    private String quoteKeywordSegments(String renderedPath) {
        String[] segments = renderedPath.split("/", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(quoteSegment(segments[i]));
        }
        return out.toString();
    }

    private String quoteSegment(String segment) {
        if (segment.isEmpty() || segment.equals("..")) {
            return segment;
        }
        String name = segment;
        String suffix = "";
        if (name.endsWith("*")) {           // a flattened repetition segment — quote the name, keep the '*'
            suffix = "*";
            name = name.substring(0, name.length() - 1);
        }
        return keywords.contains(name) ? "'" + name + "'" + suffix : segment;
    }

    private static String relativize(String base, String absolute) {
        String[] b = base.split("/");
        String[] f = absolute.split("/");
        int common = 0;
        while (common < b.length && common < f.length && b[common].equals(f[common])) {
            common++;
        }
        StringBuilder rel = new StringBuilder();
        for (int i = common; i < b.length; i++) {
            rel.append("../");
        }
        for (int i = common; i < f.length; i++) {
            rel.append(f[i]);
            if (i < f.length - 1) {
                rel.append('/');
            }
        }
        return rel.isEmpty() ? absolute : rel.toString();
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
            case FieldPresenceCondition f ->
                    keyword(f.operatorId()) + "(" + (f.field().outer() ? "$" : "") + path(f.field().path()) + ")";
            case FieldListPredicate p -> keyword(p.operatorId()) + "("
                    + p.fields().stream().map(this::operand)
                            .collect(java.util.stream.Collectors.joining(", "))
                    + ")";
            case ComparisonCondition c ->
                    render(c.left()) + " " + keyword(c.operatorId()) + " " + render(c.right());
            case LogicCondition l ->
                    child(l, l.left()) + " " + keyword(l.operatorId()) + " " + child(l, l.right());
            case GroupPresenceCondition g -> keyword(g.operatorId()) + "(" + path(g.group().path()) + ")";
            case GroupListPredicate p -> keyword(p.operatorId()) + "("
                    + p.groups().stream().map(g -> path(g.path())).collect(java.util.stream.Collectors.joining(", "))
                    + ")";
            case PatternCondition p ->
                    "[" + path(p.field().path()) + "] " + keyword(p.operatorId()) + " " + quote(p.regex());
            case ValueListCondition v -> keyword(v.operatorId()) + "(" + path(v.field().path()) + ", "
                    + v.values().stream().map(this::render).collect(java.util.stream.Collectors.joining(", "))
                    + ")";
            case CustomConditionRef c -> keyword(c.operatorId()) + " " + c.name();
            case RepetitionNotUniqueCondition r -> keyword(r.operatorId()) + "("
                    + r.keys().stream().map(k -> path(k.path())).collect(java.util.stream.Collectors.joining(", "))
                    + r.scope().map(s -> " " + keyword("FromScope") + " " + path(s.path())).orElse("")
                    + ")";
            case FieldListValueListCondition v -> keyword(v.operatorId()) + "("
                    + v.fields().stream().map(this::operand)
                            .collect(java.util.stream.Collectors.joining(", "))
                    // 'In' is a language-independent grammar literal (inOp), like the comparison symbols
                    + " In "
                    + v.values().stream().map(this::render).collect(java.util.stream.Collectors.joining(", "))
                    + ")";
            case FieldListEntityListCondition v -> keyword(v.operatorId()) + "("
                    + v.fields().stream().map(this::operand)
                            .collect(java.util.stream.Collectors.joining(", "))
                    + " In "
                    // entity-valued: the value list is bare field/repetition-list paths, not quoted values
                    + v.valueFields().stream().map(this::operand)
                            .collect(java.util.stream.Collectors.joining(", "))
                    + ")";
            case GroupScopedFieldFill v -> keyword(v.operatorId()) + "("
                    + v.scopes().stream().map(s -> path(s.renderedPath()))
                            .collect(java.util.stream.Collectors.joining(", "))
                    + ")";
            case DateValidity v -> keyword(v.operatorId()) + "(" + renderArg(v.dateConstruct()) + ")";
        };
    }

    /**
     * The operand list of a count aggregate. A lone repeatable group is the wildcard form
     * {@code NumberOfFilledGroups(Group*)} — count its filled repetitions; every other shape renders
     * its fields/groups bare (distinct entities).
     */
    private String countOperands(CountAggregate a) {
        if (a.op() == CountAggregate.CountOp.FILLED_GROUPS
                && a.fields().isEmpty() && a.groups().size() == 1 && a.groups().get(0).repeating()) {
            return path(a.groups().get(0).path()) + "*";
        }
        return java.util.stream.Stream.concat(
                        a.fields().stream().map(this::operand),
                        a.groups().stream().map(g -> path(g.path())))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** A field-list operand: its path, plus a {@code Having <filter>} clause for a filtered list. */
    private String operand(FieldOperand op) {
        // a $ outer-iteration correlation on a bare reference (D13): NoFieldFilled($a, $b), or the value
        // side of an entity-valued value-list ($Group*/field). Applies to both FieldRef and FieldListRef.
        String dollar = (op instanceof FieldRef fr && fr.outer()) || (op instanceof FieldListRef fl && fl.outer())
                ? "$" : "";
        if (op instanceof FieldListRef list && list.filter().isPresent()) {
            return dollar + path(list.renderedPath()) + " " + keyword("Having") + " " + render(list.filter().get());
        }
        return dollar + path(op.renderedPath());
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
            case FieldValue v -> "[" + (v.outer() ? "$" : "") + path(v.field().path()) + "]";
            case NumberLiteral n -> n.value().toPlainString();
            case StringLiteral s -> quote(s.value());
            case DateLiteral d -> quote(d.value());
            case TimeLiteral t -> quote(t.value());
            case EnumValueLiteral e -> quote(e.value());
            case BoolLiteral b -> keyword(b.operatorId());
            case LengthOf l -> keyword(l.operatorId()) + "(" + path(l.field().path()) + ")";
            case EnumCategoryAccess c ->
                    "[" + (c.outer() ? "$" : "") + path(c.field().path()) + " " + keyword(c.operatorId())
                            + " " + c.category() + "]";
            case ListAggregate a -> keyword(a.operatorId()) + "("
                    + a.operands().stream().map(this::operand)
                            .collect(java.util.stream.Collectors.joining(", "))
                    + ")";
            case CountAggregate a -> keyword(a.operatorId()) + "(" + countOperands(a) + ")";
            case CurrentRepetition r ->
                    keyword(r.operatorId()) + "(" + (r.outer() ? "$" : "") + path(r.group().path()) + ")";
            case SemanticIndexValue v ->
                    "[" + path(v.field().path()) + " " + keyword(v.operatorId()) + " " + quote(v.indexValue()) + "]";
            case Today t -> keyword(t.operatorId());
            case Now n -> keyword(n.operatorId());
            case BaseYear b -> keyword(b.operatorId());
            case DateDifference d -> keyword(d.operatorId())
                    + "(" + renderArg(d.left()) + ", " + renderArg(d.right()) + ")";
            case DateShift s -> keyword(s.operatorId())
                    + "(" + renderArg(s.date()) + ", " + render(s.amount()) + ")";
            case Arithmetic a -> render(a.left()) + " " + a.op().symbol() + " " + render(a.right());
            case Rounding r -> keyword(r.operatorId()) + "(" + render(r.operand()) + ", " + r.decimalPlaces() + ")";
            case Concatenation c -> c.parts().stream().map(this::render)
                    .collect(java.util.stream.Collectors.joining(" + "));
            case DateListAggregate a -> keyword(a.operatorId()) + "(" + operand(a.scope()) + ")";
            case FirstFilledValue a -> keyword(a.operatorId()) + "(" + operand(a.scope()) + ")";
            case FieldValueAsString c -> keyword(c.operatorId()) + "(" + path(c.field().path()) + ")";
            case RangeAsString c -> keyword(c.operatorId()) + "(" + path(c.field().path())
                    + ", " + c.start() + ", " + c.end() + ")";
            case FieldValueAsNumber c -> keyword(c.operatorId()) + "(" + path(c.field().path()) + ")";
            case RangeAsNumber c -> keyword(c.operatorId()) + "(" + path(c.field().path())
                    + ", " + c.start() + ", " + c.end() + ")";
            case OpListMath m -> keyword(m.operatorId()) + "("
                    + m.operands().stream().map(this::render).collect(java.util.stream.Collectors.joining(", ")) + ")";
            case NumberOfValueInFields n -> keyword(n.operatorId()) + "(" + render(n.value()) + " In "
                    + n.fields().stream().map(this::operand).collect(java.util.stream.Collectors.joining(", ")) + ")";
            case ValueAsDate v -> keyword(v.operatorId()) + "(" + path(v.field().path()) + ", "
                    + keyword(v.dayKeywordId()) + ")";
            case DateExtraction e -> keyword(e.operatorId()) + "(" + renderArg(e.source()) + ")";
            case DateTimePart e -> keyword(e.operatorId()) + "(" + renderArg(e.source()) + ")";
            case SumOfProducts s -> keyword(s.operatorId()) + "(" + operand(s.left()) + ", " + operand(s.right()) + ")";
            case DateConstruction dc -> keyword(dc.operatorId()) + "(" + renderArg(dc.day()) + ", "
                    + renderArg(dc.month()) + ", " + renderArg(dc.year1())
                    + dc.year2().map(y -> ", " + renderArg(y)).orElse("") + ")";
            case TimeConstruction tc -> keyword(tc.operatorId()) + "(" + renderArg(tc.hour()) + ", "
                    + renderArg(tc.minute()) + ", " + renderArg(tc.second()) + ")";
            case DateTimeConstruction dtc -> keyword(dtc.operatorId()) + "(" + renderArg(dtc.date()) + ", "
                    + renderArg(dtc.time()) + ")";
            case FieldRounding r -> keyword(r.operatorId()) + "(" + path(r.field().path()) + ")";
            case DateRangeBound b -> keyword(b.operatorId()) + "(" + renderArg(b.source()) + ")";
            case RootGroupCount c -> keyword(c.operatorId()) + "(" + path(c.rootGroup().path()) + "*)";
        };
    }

    /**
     * Renders a value in <em>argument position</em>: a date/field reference is the bare entity path
     * (as in {@code DifferenceInDays(A, B)}), not the bracketed value form used in comparisons.
     */
    private String renderArg(Calculation value) {
        if (value instanceof FieldValue v) {
            return path(v.field().path());
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
