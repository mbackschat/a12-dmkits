package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry;
import io.github.mbackschat.a12.dm.rulekit.validate.Span;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Renders a {@link Condition} AST to canonical A12 DSL text (PROJECT-DESIGN §8, component 4). Every
 * operator keyword — worded ({@code FieldFilled}, {@code And}/{@code Und}) or symbolic
 * ({@code <=}) — is looked up from the {@link OperatorCatalog} by the node's own
 * {@link Condition#operatorId() operator-id} (D14), so the same AST renders to EN or DE and no
 * keyword knowledge lives outside the catalog.
 *
 * <p>Path policy (§18 fork 9, F3a): with a {@code baseGroup} set the renderer emits paths
 * <em>relative</em> within that root group (bare under the group, {@code ../} up to a sibling — both
 * kernel-verified, KERNEL-FINDINGS §10 (paths)). A reference into another root group remains
 * absolute because the DSL's parent navigation cannot cross a root boundary. This is how
 * {@code Rule}/{@code Computation} render; without a base group every path is absolute. A reference may
 * override that choice for itself with a {@link PathStyle} hint ({@link FieldRef#absolute()}); keyword-segment
 * quoting is applied to whichever form results.
 */
public final class DslRenderer {

    private final OperatorCatalog catalog;
    private final ConditionLanguage language;
    private final String baseGroup;
    private static final Set<String> NON_OPERATOR_RESERVED_NAMES = Set.of(
            "AND", "and", "OR", "or", "UND", "und", "ODER", "oder",
            "IN", "In", "in", "TO", "To", "to", "ZU", "Zu", "zu",
            "RuleGroup", "RegelKontext", "SuppressWarning", "UnterdrueckeWarnung",
            "VordruckAngegeben", "VordruckNichtAngegeben", "MindestensEinVordruckAngegeben",
            "KeinVordruckAngegeben", "ValueNotConsistent", "WertNichtKonsistent",
            "AktuelleVordruckNummer", "AnzahlAngegebeneUsb", "AnzahlAngegebenerVordrucke",
            "MaximalAngegebenerZeilenindex", "UsbNichtFortlaufend", "VordruckNichtFortlaufend",
            "ZeileNichtEindeutig", "ZeilenLueckenlos", "ZeilenNichtAufsteigend");

    private final Set<String> reservedNames;

    public DslRenderer() {
        this(ConditionLanguage.EN);
    }

    public DslRenderer(ConditionLanguage language) {
        this(OperatorCatalog.load(), language, null);
    }

    /** A renderer that emits same-root paths relative to {@code baseGroup}; {@code null} = absolute. */
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
        this.reservedNames = Stream.concat(
                catalog.entries().stream()
                        .flatMap(e -> Stream.of(e.keywords().en(), e.keywords().de())),
                NON_OPERATOR_RESERVED_NAMES.stream())
                .filter(k -> k != null && !k.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * A path in the configured form: relative to the rule's group when the target belongs to the
     * same root group, otherwise absolute. Same-root relative paths are the kernel's preferred,
     * readable form; cross-root parent navigation is not legal DSL.
     */
    private String path(String absolute) {
        return path(absolute, PathStyle.CANONICAL);
    }

    /** A reference's path, honouring the spelling it asks for. The funnel every ref-rendering site uses. */
    private String path(FieldRef ref) {
        return path(ref.path().value(), ref.styleHint());
    }

    /**
     * A field OPERAND: its path, behind the {@code $} when it reads the enclosing rule's row. Dropping the marker
     * renders a valid reference to the filter's candidate row instead, so the condition silently compares
     * something else; emitting it leaves admission to the kernel, which is the side that owns it.
     */
    private String operandPath(FieldRef ref) {
        return (ref.correlated() ? "$" : "") + path(ref);
    }

    /** {@link #operandPath}, spelled canonically whatever style the reference asks for. */
    private String canonicalOperandPath(FieldRef ref) {
        return (ref.correlated() ? "$" : "") + path(ref.path().value());
    }

    /**
     * The same, for an operand that states how it wants to be spelled — the per-reference override that
     * makes a mixed-style condition authorable (API-FINDINGS AF159). Path style used to be a property of the
     * renderer, so {@code FieldFilled(Effort) And Sum(/Project/Milestones*}{@code /Budget) > 0} could not be
     * built: relativizing rewrote the aggregate, and rendering absolute rewrote the error field too.
     *
     * <p>{@link PathStyle#RELATIVE} and {@link PathStyle#SHORT} REFUSE rather than fall back, because the
     * fallback would silently emit a different spelling than the one asked for — and a hint exists precisely
     * when the spelling is the point. {@code SHORT} has no producer, and the renderer could not honour one:
     * a bare name is legal only when the model sets {@code fieldRefByShortNameAllowed} and the name
     * is unique model-wide (KERNEL-FINDINGS §10), and the renderer holds a catalog, not a model.
     */
    private String path(String absolute, PathStyle style) {
        String form = switch (style) {
            case CANONICAL -> baseGroup == null ? absolute : relativize(baseGroup, absolute);
            case ABSOLUTE -> absolute;
            case RELATIVE -> {
                if (baseGroup == null) {
                    throw new IllegalArgumentException("a RELATIVE path hint needs a base group to "
                            + "relativize against, and this renderer has none — build the renderer with the "
                            + "rule's group, or leave the reference CANONICAL: " + absolute);
                }
                yield relativize(baseGroup, absolute);
            }
            case SHORT -> throw new IllegalArgumentException("a SHORT path hint cannot be rendered: a bare "
                    + "name is legal only when the model allows short-name references and the name is unique "
                    + "model-wide, and the renderer cannot see the model — use absolute() or CANONICAL: "
                    + absolute);
        };
        return quoteKeywordSegments(form);
    }

    /**
     * Single-quote any path segment that collides with a DSL keyword (fork #9 / F3a) — the kernel
     * rejects an unquoted keyword-named entity with {@code MVK_UNEXPECTED_TOKEN} (KERNEL-FINDINGS
     * §10, paths), and {@code '<name>'} is the grammar's escape (NameOhnePunktMitQuotes). Detection uses
     * the catalog's operator keywords plus the non-operator lexer terminals. The reverse parity and
     * renderer behavior guards derive completeness from the live kernel lexer.
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
        return reservedNames.contains(name) ? "'" + name + "'" + suffix : segment;
    }

    private static String relativize(String base, String absolute) {
        String[] b = base.split("/");
        String[] f = absolute.split("/");
        if (b.length > 1 && f.length > 1 && !b[1].equals(f[1])) {
            return absolute;
        }
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
        if (rel.isEmpty()) return absolute;
        // An ancestor ends at a parent-navigation segment; the kernel rejects its trailing separator (DG54).
        if (rel.charAt(rel.length() - 1) == '/') rel.setLength(rel.length() - 1);
        return rel.toString();
    }

    /**
     * The rendered text together with the character span each {@link Condition} node occupies in it
     * (the F4a emit-map for span-pointing). The {@code text} is byte-identical to {@link #render};
     * only {@code LogicCondition}'s compositional layout is walked here, every other (leaf) node
     * reuses {@link #render} so there is no second copy of the rendering rules.
     */
    public Rendered renderWithSpans(Condition condition) {
        StringBuilder out = new StringBuilder();
        Map<Condition, Span> spans = new IdentityHashMap<>();
        emit(condition, out, spans);
        return new Rendered(out.toString(), spans);
    }

    /** A rendered condition plus the per-node spans into its text (identity-keyed). */
    public record Rendered(String text, Map<Condition, Span> spans) {
    }

    private void emit(Condition condition, StringBuilder out, Map<Condition, Span> spans) {
        emit(condition, out, spans, null);
    }

    private void emit(Condition condition, StringBuilder out, Map<Condition, Span> spans,
            Comments comments) {
        if (comments != null) {
            comments.beforeOf(condition).ifPresent(text -> out.append(";; ").append(text).append("\n"));
        }
        int start = out.length();
        if (condition instanceof LogicCondition logic) {
            emitChild(logic, logic.left(), out, spans, comments);
            out.append(" ").append(keyword(logic.operatorId())).append(" ");
            emitChild(logic, logic.right(), out, spans, comments);
        } else {
            out.append(render(condition));
        }
        spans.put(condition, new Span(start, out.length()));   // the span covers the node's text, not its comments
        if (comments != null) {
            // newline-terminated so a following operator/token isn't swallowed by the ;; line comment
            comments.afterOf(condition).ifPresent(text -> out.append(" ;; ").append(text).append("\n"));
        }
    }

    private void emitChild(LogicCondition parent, Condition child, StringBuilder out,
            Map<Condition, Span> spans, Comments comments) {
        boolean parenthesize = child instanceof LogicCondition nested && nested.op() != parent.op();
        if (parenthesize) {
            out.append("(");
        }
        emit(child, out, spans, comments);
        if (parenthesize) {
            out.append(")");
        }
    }

    /** Render with side-channel {@link Comments} emitted as {@code ;;} line comments at each node's position. */
    public String render(Condition condition, Comments comments) {
        if (comments == null || comments.isEmpty()) {
            return render(condition);
        }
        StringBuilder out = new StringBuilder();
        emit(condition, out, new IdentityHashMap<>(), comments);
        return out.toString();
    }

    public String render(Condition condition) {
        return switch (condition) {
            case FieldPresenceCondition f ->
                    keyword(f.operatorId()) + "(" + operandPath(f.field()) + ")";
            case EntityListPredicate p -> keyword(p.operatorId()) + "("
                    + p.operands().stream().map(this::aggregateOperand)
                            .collect(Collectors.joining(", "))
                    + ")";
            case ComparisonCondition c ->
                    render(c.left()) + " " + keyword(c.operatorId()) + " " + render(c.right());
            case LogicCondition l ->
                    child(l, l.left()) + " " + keyword(l.operatorId()) + " " + child(l, l.right());
            case GroupPresenceCondition g ->
                    keyword(g.operatorId()) + "(" + (g.outer() ? "$" : "") + path(g.group().path().value()) + ")";
            case RuleGroupPresenceCondition r -> keyword(r.operatorId()) + "(" + ruleGroupKeyword() + ")";
            case IndexedPresenceCondition p -> keyword(p.operatorId()) + "(" + indexBody(p.index()) + ")";
            case IndexedGroupPresenceCondition p -> keyword(p.operatorId()) + "(" + groupIndexBody(p.group()) + ")";
            case GroupListPredicate p -> keyword(p.operatorId()) + "("
                    + p.operands().stream().map(this::entityOperand)
                            .collect(Collectors.joining(", "))
                    + ")";
            case PatternCondition p ->
                    "[" + entityOperand(p.field()) + "] " + keyword(p.operatorId()) + " " + quote(p.regex());
            case ValueListCondition v -> keyword(v.operatorId()) + "(" + entityOperand(v.field()) + ", "
                    + v.values().stream().map(this::render).collect(Collectors.joining(", "))
                    + ")";
            case CustomConditionRef c -> keyword(c.operatorId()) + " " + quoteSegment(c.name());
            case RepetitionNotUniqueCondition r -> keyword(r.operatorId()) + "("
                    + r.keys().stream().map(this::canonicalOperandPath).collect(Collectors.joining(", "))
                    + r.scope().map(s -> " " + keyword("FromScope") + " " + path(s.path().value())).orElse("")
                    + ")";
            case FieldListValueListCondition v -> keyword(v.operatorId()) + "("
                    + v.fields().stream().map(this::operand)
                            .collect(Collectors.joining(", "))
                    // 'In' is a language-independent grammar literal (inOp), like the comparison symbols
                    + " In "
                    + v.values().stream().map(this::render).collect(Collectors.joining(", "))
                    + ")";
            case FieldListEntityListCondition v -> keyword(v.operatorId()) + "("
                    + v.fields().stream().map(this::operand)
                            .collect(Collectors.joining(", "))
                    + " In "
                    // entity-valued: the value list is bare field/repetition-list paths, not quoted values
                    + v.valueFields().stream().map(this::operand)
                            .collect(Collectors.joining(", "))
                    + ")";
            case DateValidity v -> keyword(v.operatorId()) + "(" + renderArg(v.dateConstruct()) + ")";
            case CustomTypeValidity v ->
                    keyword(v.operatorId()) + "(" + operandPath(v.field()) + ", " + quote(v.typeName()) + ")";
        };
    }

    /**
     * The operand list of a count aggregate. <b>A repeatable group operand is always a wildcard scope</b>,
     * whichever count operator holds it, and mixing it with fixed groups is fine; the record holds a group as
     * that scope ({@code CountAggregate.scopeOf}), so it renders as any other scope does.
     *
     * <p>It used to be starred for {@code NumberOfFilledGroups} only, which made
     * {@code Dsl.numberOfFilledFields(GroupRef)} emit a condition the kernel refuses outright —
     * {@code MVK_NO_WILDCARD}, measured 2026-08-12 on a repeatable group, alongside the acceptance of the
     * same operand starred and of an unstarred NON-repeatable group. The star is a property of the operand,
     * not of the operator ({@code rulekit.validate.laws.AggregateGroupOperandLawsTest}).
     */
    private String countOperands(CountAggregate a) {
        return a.operands().stream().map(this::aggregateOperand).collect(Collectors.joining(", "));
    }

    /**
     * Render an arithmetic operand, wrapping it in a {@code { … }} group when its operator binds LOOSER than
     * the parent's (so the parse round-trips). Precedence: {@code +}/{@code -} &lt; {@code *}/{@code /} &lt;
     * {@code ^}. Every right same-precedence child needs a group because every arithmetic node rounds
     * independently, so even {@code +} and {@code *} are not freely associative. A left power child also
     * needs a group because the grammar admits only one direct power, and a left division under division
     * needs one to reconstruct the plain-arithmetic authoring-region reset. That reset is a static legality
     * boundary only; it does not hide the subtree from the later runtime division rewrite. DEV-API-GAPS DG7 — without this, e.g.
     * {@code times(plus(1, 1))} mis-rendered as {@code … * 1 + 1} (re-parsing to the wrong tree).
     */
    private String arithOperand(Calculation child, Arithmetic.Op parent, boolean isLeft) {
        if (child instanceof Arithmetic a) {
            if (needsArithmeticGroup(a, parent, isLeft)) {
                return "{ " + render(child) + " }";
            }
        }
        return render(child);
    }

    /** A `ValueAsDate` source: the bare field, or the keyed cell written as `Field For key` (KERNEL-FINDINGS KF531). */
    private String valueAsDateSource(ValueAsDate.Source source) {
        return switch (source) {
            case FieldRef field -> operandPath(field);
            case IndexedFieldValue indexed -> indexBody(indexed);
        };
    }

    /**
     * A concatenation part, with an enumeration read braced: A12 refuses an immediate one whose labels remap its
     * values and admits the braced one whatever its labels (KERNEL-FINDINGS KF396), a key-selected cell included
     * ({@code validate.laws.KeyedEnumerationConcatenationLawsTest}).
     */
    private String concatenationPart(Calculation part) {
        return (part instanceof FieldValue || part instanceof IndexedFieldValue) && part.kind() == ValueKind.ENUMERATION
                ? "{ " + render(part) + " }" : render(part);
    }

    private boolean needsArithmeticGroup(Arithmetic child, Arithmetic.Op parent, boolean isLeft) {
        int childPrec = arithPrec(child.op());
        int parentPrec = arithPrec(parent);
        return childPrec < parentPrec
                || (childPrec == parentPrec && !isLeft)
                || (childPrec == parentPrec && isLeft && parent == Arithmetic.Op.POWER)
                || (parent == Arithmetic.Op.DIVIDE && openDivisionContribution(child) > 0);
    }

    /**
     * The division count this expression contributes to its enclosing plain multiplication/division region
     * after this renderer's own child groups are applied. Addition, subtraction, and power are fresh regions.
     */
    private int openDivisionContribution(Calculation expression) {
        if (!(expression instanceof Arithmetic arithmetic)
                || arithmetic.op() == Arithmetic.Op.ADD
                || arithmetic.op() == Arithmetic.Op.SUBTRACT
                || arithmetic.op() == Arithmetic.Op.POWER) {
            return 0;
        }
        int contribution = arithmetic.op() == Arithmetic.Op.DIVIDE ? 1 : 0;
        if (arithmetic.left() instanceof Arithmetic left
                && !needsArithmeticGroup(left, arithmetic.op(), true)) {
            contribution += openDivisionContribution(left);
        }
        if (arithmetic.right() instanceof Arithmetic right
                && !needsArithmeticGroup(right, arithmetic.op(), false)) {
            contribution += openDivisionContribution(right);
        }
        return contribution;
    }

    private static int arithPrec(Arithmetic.Op op) {
        return switch (op) {
            case ADD, SUBTRACT -> 1;
            case MULTIPLY, DIVIDE -> 2;
            case POWER -> 3;
        };
    }

    /** A fixed group-list entity: a checked field renders its plain path, a group scope its starred path,
     *  an index-selected group or field its localized {@code … For key} body. */
    private String entityOperand(GroupListOperand op) {
        return switch (op) {
            case FieldRef field -> canonicalOperandPath(field);
            case GroupListRef scope -> groupScope(scope);
            case IndexedGroupValue indexed -> groupIndexBody(indexed);
            case IndexedFieldValue indexed -> indexBody(indexed);
        };
    }

    /**
     * A group scope with its {@code $} outer-iteration marker, when it carries one. The marker is a property
     * of the operand's POSITION, so it is emitted here rather than baked into
     * {@link GroupListRef#renderedPath()} — the same split {@link FieldRef} already uses.
     */
    private String groupScope(GroupListRef scope) {
        return (scope.correlated() ? "$" : "") + path(scope.renderedPath());
    }

    /**
     * An entity-list aggregate operand. A group scope renders as its starred path, plus its {@code $}
     * outer-iteration marker when it carries one, and no {@code Having} filter — the kernel rejects a filter
     * on a group operand outright ({@code MVK_NO_GROUPS_ALLOWED}, locked by
     * {@code adapter.laws.GroupStarValueAggregateDiffTest}). An index-selected group renders its localized index
     * body, stars included: A12 admits a star below the index group
     * ({@code validate.laws.SemanticIndexWildcardPlacementLawsTest}).
     */
    private String aggregateOperand(AggregateOperand op) {
        return switch (op) {
            case FieldOperand field -> operand(field);
            case GroupListRef group -> groupScope(group);
            case IndexedGroupValue indexed -> groupIndexBody(indexed);
        };
    }

    private String operand(ValueListOperand op) {
        // an enum category projection as a value-list quantifier member renders BARE: `path -> Cat` (no
        // brackets — the bracketed [field -> Cat] form is the comparison-operand rendering, not the
        // entity-list member form the kernel grammar uses here).
        if (op instanceof EnumCategoryAccess c) {
            return (c.outer() ? "$" : "") + path(c.field()) + " " + keyword(c.operatorId()) + " " + c.category();
        }
        // An index-selected field entity renders the same bare "Field For key" body a presence condition
        // does — indexBody already folds correlation and localizes/quotes the key, so this must not fall
        // through to the generic path(op.renderedPath()) below, which would emit that diagnostic form's
        // hardcoded "For" and unquoted key instead of the actual condition text.
        if (op instanceof IndexedFieldValue indexed) {
            return indexBody(indexed);
        }
        if (op instanceof FieldListRef list && list.semanticIndex().isPresent()) {
            return firstFilledOperand(list.firstFilledOperand());
        }
        // a $ outer-iteration correlation on a bare reference (D13): NoFieldFilled($a, $b), or the value
        // side of an entity-valued value-list ($Group*/field). Applies to both FieldRef and FieldListRef.
        String dollar = (op instanceof FieldRef fr && fr.correlated()) || (op instanceof FieldListRef fl && fl.correlated())
                ? "$" : "";
        if (op instanceof FieldListRef list && list.filter().isPresent()) {
            return dollar + path(list.renderedPath(), list.styleHint()) + " " + keyword("Having")
                    + " " + render(list.filter().get());
        }
        return dollar + path(op.renderedPath(),
                op instanceof FieldOperand fo ? fo.styleHint() : PathStyle.CANONICAL);
    }

    private String child(LogicCondition parent, Condition child) {
        String rendered = render(child);
        // Mixing And with Or requires parentheses; same-operator nesting does not.
        if (child instanceof LogicCondition nested && nested.op() != parent.op()) {
            return "(" + rendered + ")";
        }
        return rendered;
    }

    /**
     * Renders an {@code operation} with side-channel {@link Comments} — a leading {@code ;;} comment ahead
     * of it, a trailing one newline-terminated after it (the computation peer of {@link #render(Condition,
     * Comments)}; comments attach to the top-level operation node, the position the kernel grammar allows).
     */
    public String render(Calculation calculation, Comments comments) {
        if (comments == null || comments.isEmpty()) {
            return render(calculation);
        }
        StringBuilder out = new StringBuilder();
        comments.beforeOf(calculation).ifPresent(text -> out.append(";; ").append(text).append("\n"));
        out.append(render(calculation));
        comments.afterOf(calculation).ifPresent(text -> out.append(" ;; ").append(text).append("\n"));
        return out.toString();
    }

    /** Renders a flattened field-list operand with its key, filter and path-style modifiers. */
    public String render(FieldListRef list) {
        return operand(list);
    }

    /** Renders a value expression — e.g. a computation slot's {@code operation}. */
    public String render(Calculation calculation) {
        return switch (calculation) {
            case FieldValue v -> "[" + (v.outer() ? "$" : "") + path(v.field()) + "]";
            case NumberLiteral n -> n.value().toPlainString();
            case StringLiteral s -> quote(s.value());
            case DateLiteral d -> quote(d.value());
            case TimeLiteral t -> quote(t.value());
            case DateTimeLiteral dt -> quote(dt.value());
            case EnumValueLiteral e -> quote(e.value());
            case BoolLiteral b -> keyword(b.operatorId());
            case LengthOf l -> keyword(l.operatorId()) + "(" + entityOperand(l.field()) + ")";
            case EnumCategoryAccess c ->
                    "[" + (c.outer() ? "$" : "") + path(c.field()) + " " + keyword(c.operatorId())
                            + " " + c.category() + "]";
            case ListAggregate a -> keyword(a.operatorId()) + "("
                    + a.operands().stream().map(this::aggregateOperand)
                            .collect(Collectors.joining(", "))
                    + ")";
            case CountAggregate a -> keyword(a.operatorId()) + "(" + countOperands(a) + ")";
            case CurrentRepetition r ->
                    keyword(r.operatorId()) + "(" + (r.outer() ? "$" : "") + path(r.group().path().value()) + ")";
            case SemanticIndexValue v -> "[" + indexBody(v) + "]";
            case SemanticIndexFieldValue v -> "[" + indexBody(v) + "]";
            case Today t -> keyword(t.operatorId());
            case Now n -> keyword(n.operatorId());
            case BaseYear b -> keyword(b.operatorId());
            case DateDifference d -> keyword(d.operatorId())
                    + "(" + renderArg(d.left()) + ", " + renderArg(d.right()) + ")";
            case DateShift s -> keyword(s.operatorId())
                    + "(" + renderArg(s.date()) + ", " + render(s.amount()) + ")";
            case Arithmetic a -> arithOperand(a.left(), a.op(), true) + " " + a.op().symbol()
                    + " " + arithOperand(a.right(), a.op(), false);
            case Rounding r -> keyword(r.operatorId()) + "(" + render(r.operand())
                    + r.decimalPlaces().map(places -> ", " + places).orElse("") + ")";
            case Concatenation c -> c.parts().stream().map(this::concatenationPart)
                    .collect(Collectors.joining(" + "));
            case DateListAggregate a -> keyword(a.operatorId()) + "("
                    + a.operands().stream().map(this::aggregateOperand)
                            .collect(Collectors.joining(", "))
                    + ")";
            case FirstFilledValue a -> keyword(a.operatorId()) + "("
                    + a.operands().stream().map(this::firstFilledOperand)
                            .collect(Collectors.joining(", "))
                    + ")";
            case FieldValueAsString c -> keyword(c.operatorId()) + "(" + operandPath(c.field()) + ")";
            case IndexedFieldValueAsString c -> keyword(c.operatorId()) + "(" + indexBody(c.index()) + ")";
            case RangeAsString c -> keyword(c.operatorId()) + "(" + operandPath(c.field())
                    + ", " + c.start() + ", " + c.end() + ")";
            case FieldValueAsNumber c -> keyword(c.operatorId()) + "(" + numericTextSource(c.source()) + ")";
            case RangeAsNumber c -> keyword(c.operatorId()) + "(" + operandPath(c.field())
                    + ", " + c.start() + ", " + c.end() + ")";
            case OpListMath m -> keyword(m.operatorId()) + "("
                    + m.operands().stream().map(this::render).collect(Collectors.joining(", ")) + ")";
            case NumberOfValueInFields n -> keyword(n.operatorId()) + "(" + render(n.value()) + " In "
                    + n.fields().stream().map(this::aggregateOperand)
                            .collect(Collectors.joining(", ")) + ")";
            case ValueAsDate v -> keyword(v.operatorId()) + "(" + valueAsDateSource(v.source()) + ", "
                    + keyword(v.dayKeywordId()) + ")";
            case DateExtraction e -> keyword(e.operatorId()) + "(" + renderArg(e.source()) + ")";
            case DateTimePart e -> keyword(e.operatorId()) + "(" + renderArg(e.source()) + ")";
            case SumOfProducts s -> keyword(s.operatorId()) + "(" + operand(s.left()) + ", " + operand(s.right()) + ")";
            case DateConstruction dc -> keyword(dc.operatorId()) + "(" + dc.components().stream()
                    .map(this::renderDateParam).collect(Collectors.joining(", ")) + ")";
            case TimeConstruction tc -> keyword(tc.operatorId()) + "(" + tc.components().stream()
                    .map(this::renderDateParam).collect(Collectors.joining(", ")) + ")";
            case DateTimeConstruction dtc -> keyword(dtc.operatorId()) + "(" + renderArg(dtc.date()) + ", "
                    + renderArg(dtc.time()) + ")";
            case DateRangeConstruction dr -> keyword(dr.operatorId()) + "(" + renderArg(dr.start()) + ", "
                    + renderArg(dr.end()) + ")";
            case FieldRounding r -> keyword(r.operatorId()) + "(" + operandPath(r.field())
                    + r.decimalPlaces().map(places -> ", " + places).orElse("") + ")";
            case DateRangeBound b -> keyword(b.operatorId()) + "(" + renderArg(b.source()) + ")";
            // The star goes in before relativizing: appended after, a rule inside the root rendered `..*`, which
            // A12 refuses (validate.laws.StarredCountRenderBaseLawsTest)
            case RootGroupCount c -> keyword(c.operatorId()) + "(" + path(c.rootGroup().path().value() + "*") + ")";
        };
    }

    private String numericTextSource(NumericTextSource source) {
        return switch (source) {
            case FieldRef field -> canonicalOperandPath(field);
            case EnumRef field -> operandPath(field.field());
            case CategoryRef category -> operandPath(category.field())
                    + " " + keyword("EnumCategory") + " " + category.name();
        };
    }

    /**
     * A {@code datumParam} position — a {@code Date(...)} / {@code Time(...)} component. The kernel grammar
     * admits only {@code entitySpec | datumExtractOp | StringLiteral} there, with NO bare-number alternative,
     * so a numeric constant component must render QUOTED. Rendering it bare produces
     * {@code MVK_INVALID_ENTITY} at {@code rule check} (measured 2026-07-29): the kernel reads the digits as an
     * entity name. Fields render bare and extractors as their call, exactly as elsewhere.
     */
    private String renderDateParam(Calculation value) {
        if (value instanceof NumberLiteral n) {
            return quote(n.value().toPlainString());
        }
        return renderArg(value);
    }

    /**
     * Renders a value in <em>argument position</em>: a date/field reference is the bare entity path
     * (as in {@code DifferenceInDays(A, B)}), not the bracketed value form used in comparisons.
     */
    private String renderArg(Calculation value) {
        if (value instanceof FieldValue v) {
            return (v.outer() ? "$" : "") + path(v.field());
        }
        if (value instanceof IndexedFieldValue indexed) {
            return indexBody(indexed);
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

    /**
     * The reserved self-reference keyword for the rule's own group (a grammar keyword, not an operator —
     * so it isn't in the catalog): EN {@code RuleGroup}, DE {@code RegelKontext} (kernel lexer
     * {@code RULE_GROUP}, verified against source).
     */
    private String ruleGroupKeyword() {
        return language == ConditionLanguage.DE ? "RegelKontext" : "RuleGroup";
    }

    /**
     * The bare {@code Field For key} body of a semantic index — the value read wraps it in {@code [ ]}
     * ({@code [Field For key]}); a {@code FieldFilled}/{@code FieldNotFilled} presence renders it bare.
     */
    private String indexBody(IndexedFieldValue index) {
        return switch (index) {
            case SemanticIndexValue v ->   // literal key: a quoted constant
                    operandPath(v.field()) + " " + keyword(v.operatorId()) + " " + quote(v.indexValue());
            case SemanticIndexFieldValue v ->   // field key: a bare field path, not a quoted literal
                    operandPath(v.field()) + " " + keyword(v.operatorId()) + " "
                            + canonicalOperandPath(v.indexField());
        };
    }

    /**
     * The group analog of {@link #indexBody} — {@code Group For key} — for the entity-list / group-list
     * member form. The scope and the key each carry their own {@code $} marker (see {@link IndexedGroupValue}).
     */
    private String groupIndexBody(IndexedGroupValue index) {
        return switch (index) {
            case GroupSemanticIndexValue v ->
                    groupScope(v.group()) + " " + keyword("SemanticIndex") + " " + quote(v.indexValue());
            case GroupSemanticIndexFieldValue v ->
                    groupScope(v.group()) + " " + keyword("SemanticIndex") + " "
                            + canonicalOperandPath(v.indexField());
        };
    }

    /** Render the owner {@code entitySpec} union accepted by {@code FirstFilledValue}; semantic indexes
     *  are bare here (the surrounding operator already consumes entities), not value-bracketed. */
    private String firstFilledOperand(FirstFilledOperand operand) {
        StringBuilder rendered = new StringBuilder();
        if (operand.outerReference()) {
            rendered.append('$');
        }
        rendered.append(path(operand.renderedPath(),
                operand.field().map(FieldOperand::styleHint).orElse(PathStyle.CANONICAL)));
        operand.category().ifPresent(category -> rendered.append(" ").append(keyword("EnumCategory"))
                .append(" ").append(category));
        operand.semanticIndex().ifPresent(index -> {
            rendered.append(" ").append(keyword("SemanticIndex")).append(" ");
            switch (index) {
                case FirstFilledOperand.LiteralIndex literal -> rendered.append(quote(literal.value()));
                case FirstFilledOperand.FieldIndex field -> {
                    rendered.append(operandPath(field.field()));
                }
            }
        });
        operand.field().filter(FieldListRef.class::isInstance).map(FieldListRef.class::cast)
                .flatMap(FieldListRef::filter)
                .ifPresent(filter -> rendered.append(" ").append(keyword("Having")).append(" ")
                        .append(render(filter)));
        return rendered.toString();
    }
}
