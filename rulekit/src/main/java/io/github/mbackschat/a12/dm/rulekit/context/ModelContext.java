package io.github.mbackschat.a12.dm.rulekit.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.mbackschat.a12.dm.rulekit.catalog.GotchaKind;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionReferences;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldInfo;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupInfo;
import io.github.mbackschat.a12.dm.rulekit.dsl.OperatorUsage;
import io.github.mbackschat.a12.dm.core.Suggest;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * The F9a model-context surface — the <em>inverse</em> of authoring: an agent's entry point is
 * usually a name or "what does this model look like?", not a handle. A pure projection over a
 * {@link BoundModel}'s index; nothing else depends on it.
 *
 * <ul>
 *   <li>{@link #describe()} — the model card (the agent's map, JSON-serializable).</li>
 *   <li>{@link #find(String)} — resolve a name to fields (exact path/name, substring, then a fuzzy
 *       fallback for typos), so "explain the rule on <em>fee</em>" can locate the field first.</li>
 * </ul>
 */
public final class ModelContext {

    private static final OperatorCatalog CATALOG = OperatorCatalog.load();

    private final BoundModel model;
    private final Set<String> repeatableGroupPaths;

    public ModelContext(BoundModel model) {
        this(model, model::groups);
    }

    ModelContext(BoundModel model, Supplier<Stream<GroupInfo>> groups) {
        this.model = model;
        this.repeatableGroupPaths = groups.get()
                .filter(GroupInfo::repeating)
                .map(GroupInfo::path)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Explain a condition (F9) — the operators it uses (with catalog meaning + relevant gotchas), the
     * fields/groups it references (with model kind), whether it iterates over a repeatable group, and
     * a one-line gloss. Works identically for a rulekit-built condition and a converted-foreign one
     * (F10 read): feed {@code adapter.read(rule)}'s {@code Converted.condition()} straight in.
     */
    public RuleExplanation explain(Condition condition) {
        DslRenderer renderer = new DslRenderer(ConditionLanguage.EN);
        String text = renderer.render(condition);

        List<RuleExplanation.Operator> operators = glossary(OperatorUsage.of(condition));
        ConditionReferences refs = ConditionReferences.of(condition);
        List<RuleExplanation.Reference> references = references(refs);

        Optional<String> iterationGroup = iterationScope(refs);
        RuleExplanation.Node tree = new ExplanationTreeBuilder(renderer).build(condition);
        return new RuleExplanation(text, operators, references,
                iterationGroup.isPresent(), iterationGroup, tree, gloss(operators, references, iterationGroup));
    }

    /**
     * Explain a computation (F9, the calculated-field peer of {@link #explain}) — the field it computes
     * (path + model kind), the common precondition and each alternative's {@code (precondition, operation)}
     * projected like a rule (operators with catalog meaning/gotchas + referenced fields), the self-validation
     * messages, and a one-line gloss. Feed {@code adapter.readComputation(name)}'s {@code Converted}
     * computation straight in — it is an ordinary rulekit {@link Computation} by the time it reaches here.
     */
    public ComputationExplanation explainComputation(Computation computation) {
        String field = computation.computedField().path();
        String kind = computation.computedField().kind().name();   // from the typed ref — always a kind, never UNKNOWN

        Optional<RuleExplanation> commonPrecondition = computation.commonPrecondition().map(this::explain);
        List<ComputationExplanation.Alternative> alternatives = computation.alternatives().stream()
                .map(a -> new ComputationExplanation.Alternative(
                        a.precondition().map(this::explain), explainOperation(a.operation()), a.toleranceRangeOp()))
                .toList();
        List<ComputationExplanation.Message> messages = computation.messages().stream()
                .map(t -> new ComputationExplanation.Message(t.locale(), t.text()))
                .toList();

        return new ComputationExplanation(field, kind, commonPrecondition, alternatives, messages,
                computationGloss(field, commonPrecondition, alternatives));
    }

    /** Project one computation operation (a value expression) — its rendered formula, operators, and references. */
    private ComputationExplanation.OperationExplanation explainOperation(Calculation operation) {
        String text = new DslRenderer(ConditionLanguage.EN).render(operation);
        return new ComputationExplanation.OperationExplanation(text,
                glossary(OperatorUsage.of(operation)), references(ConditionReferences.of(operation)));
    }

    /**
     * Explicit, self-describing marker for a kind the projection can't resolve — an operator absent from
     * the catalog, or a referenced field not in the model (only reachable for an inconsistent rule read
     * leniently). A named token, not a cryptic {@code "?"}, so a reader/agent sees what it means.
     */
    private static final String UNKNOWN = "UNKNOWN";

    /** Look up each operator-id in the catalog → keyword/kind/meaning/author-relevant gotchas (rendering elided). */
    private static List<RuleExplanation.Operator> glossary(Set<String> operatorIds) {
        List<RuleExplanation.Operator> operators = new ArrayList<>();
        for (String id : operatorIds) {
            CATALOG.byId(id).ifPresentOrElse(
                    e -> operators.add(new RuleExplanation.Operator(
                            e.id(), e.keywords().en(), e.kind().name(),
                            e.meaning() == null ? "" : e.meaning(),
                            e.gotchas().stream()
                                    .filter(g -> g.kind() != GotchaKind.RENDERING)
                                    .map(g -> g.text())
                                    .toList())),
                    () -> operators.add(new RuleExplanation.Operator(id, id, UNKNOWN, "", List.of())));
        }
        return operators;
    }

    /** The referenced fields (with model kind) and groups (kind {@code GROUP}), sorted. */
    private List<RuleExplanation.Reference> references(ConditionReferences refs) {
        List<RuleExplanation.Reference> references = new ArrayList<>();
        refs.fieldPaths().stream().sorted().forEach(p ->
                references.add(new RuleExplanation.Reference(p,
                        model.fieldAt(p).map(f -> f.kind().name()).orElse(UNKNOWN))));
        refs.groupPaths().stream().sorted().forEach(g ->
                references.add(new RuleExplanation.Reference(g, "GROUP")));
        return references;
    }

    private static String computationGloss(String field, Optional<RuleExplanation> commonPrecondition,
            List<ComputationExplanation.Alternative> alternatives) {
        String tiers = alternatives.size() == 1 && alternatives.get(0).precondition().isEmpty()
                ? "unconditionally"
                : alternatives.size() + " alternative" + (alternatives.size() == 1 ? "" : "s");
        return "Computes " + field + " " + tiers
                + commonPrecondition.map(p -> " (when " + p.text() + ")").orElse("") + ".";
    }

    /**
     * The fields a candidate condition could legally use as its <b>error field</b> (FEATURE-GAPS G4) — the
     * referenced fields that satisfy the kernel's {@code MVK_ERROR_FIELD_NOT_REFERENCED} law: the error
     * field must be <em>referenced</em> by the condition and, when the condition iterates a repeatable group,
     * lie <em>inside</em> that group (so it has a per-row instance to attach the error to). Returns the
     * qualifying referenced field paths, sorted; empty when the condition references only groups, or only
     * fields outside its iteration scope (then no referenced field is a valid error field — the author must
     * reference one inside the scope). A discoverability aid that turns the reject→fix round-trip into an
     * up-front pick; the kernel stays the authority on the final verdict.
     */
    public List<String> errorFieldCandidates(Condition condition) {
        return errorFieldCandidates(ConditionReferences.of(condition));
    }

    /**
     * The {@link #errorFieldCandidates(Condition) error-field candidates} from a set of already-resolved
     * referenced paths (a field or a group) — the entry for the kernel's own {@code getDirectlyReferencedEntities}
     * answer, which is robust where the rulekit converter is not (it never NPEs on an inconsistent candidate).
     * Each path is classified field-vs-group via the model index before the same scope filter is applied.
     */
    public List<String> errorFieldCandidates(Set<String> referencedPaths) {
        Set<String> fields = new java.util.LinkedHashSet<>();
        Set<String> groups = new java.util.LinkedHashSet<>();
        for (String p : referencedPaths) {
            (model.fieldAt(p).isPresent() ? fields : groups).add(p);
        }
        return errorFieldCandidates(new ConditionReferences(fields, groups));
    }

    private List<String> errorFieldCandidates(ConditionReferences refs) {
        Optional<String> scope = iterationScope(refs);
        return refs.fieldPaths().stream().sorted()
                .filter(p -> scope.isEmpty() || p.startsWith(scope.get() + "/"))
                .toList();
    }

    /** The repeatable group the condition ranges over, if any (a referenced field's repeatable ancestor, or a repeatable referenced group). */
    private Optional<String> iterationScope(ConditionReferences refs) {
        for (String fieldPath : refs.fieldPaths()) {
            Optional<String> ancestor = model.nearestRepeatableAncestor(fieldPath);
            if (ancestor.isPresent()) {
                return ancestor;
            }
        }
        return refs.groupPaths().stream()
                .filter(g -> repeatableGroupPaths.contains(g)
                        || repeatableGroupPaths.stream().anyMatch(r -> g.startsWith(r + "/")))
                .sorted().findFirst();
    }

    private static String gloss(List<RuleExplanation.Operator> operators,
            List<RuleExplanation.Reference> references, Optional<String> iterationGroup) {
        String ops = operators.stream().map(RuleExplanation.Operator::keyword).collect(Collectors.joining(", "));
        String fields = references.stream().map(RuleExplanation.Reference::path).collect(Collectors.joining(", "));
        return "Uses " + (ops.isEmpty() ? "no catalog operators" : ops)
                + (fields.isEmpty() ? "" : " over " + fields)
                + iterationGroup.map(g -> "; iterates within " + g).orElse("") + ".";
    }

    /** The model as a compact card — every field (kind + scale/enum-domain), group (repeatability), and
     *  the declared message locales (the per-locale-message orientation cue). */
    public ModelCard describe() {
        List<ModelCard.Field> fields = model.fields().map(this::fieldCard).toList();
        List<ModelCard.Group> groups = model.groups()
                .map(g -> new ModelCard.Group(g.path(), g.repeating(), g.maxRepetitions()))
                .toList();
        return new ModelCard(fields, groups, model.locales());
    }

    private ModelCard.Field fieldCard(FieldInfo field) {
        Optional<Integer> scale = field.kind() == ValueKind.NUMBER
                ? Optional.of(field.scale()) : Optional.empty();
        List<String> enumValues = field.kind() == ValueKind.ENUMERATION
                ? field.asEnum().allowedValues() : List.of();
        return new ModelCard.Field(field.path(), field.name(), field.kind().name(), scale,
                field.numberMin(), field.numberMax(), field.minLength(), field.maxLength(), field.pattern(),
                enumValues);
    }

    /** Fields matching {@code query}, best match first; a fuzzy fallback resolves typos when nothing else hits. */
    public List<FieldMatch> find(String query) {
        String q = query.strip();
        List<FieldInfo> all = model.fields().toList();

        List<FieldMatch> matches = new ArrayList<>();
        for (FieldInfo field : all) {
            FieldMatch.How how = classify(field, q);
            if (how != null) {
                matches.add(new FieldMatch(field.path(), field.name(), field.kind(), how));
            }
        }
        if (!matches.isEmpty()) {
            matches.sort(Comparator.comparingInt((FieldMatch m) -> m.how().ordinal()).thenComparing(FieldMatch::path));
            return List.copyOf(matches);
        }

        // fuzzy fallback — nearest field name by edit distance (typo-plausible threshold)
        return Suggest.nearest(q, all.stream().map(FieldInfo::name).toList())
                .map(name -> all.stream()
                        .filter(f -> f.name().equals(name))
                        .map(f -> new FieldMatch(f.path(), f.name(), f.kind(), FieldMatch.How.FUZZY))
                        .toList())
                .orElseGet(List::of);
    }

    private FieldMatch.How classify(FieldInfo field, String q) {
        if (field.path().equals(q)) {
            return FieldMatch.How.EXACT_PATH;
        }
        if (field.name().equalsIgnoreCase(q)) {
            return FieldMatch.How.EXACT_NAME;
        }
        String lower = q.toLowerCase(java.util.Locale.ROOT);
        if (field.name().toLowerCase(java.util.Locale.ROOT).contains(lower)
                || field.path().toLowerCase(java.util.Locale.ROOT).contains(lower)) {
            return FieldMatch.How.SUBSTRING;
        }
        return null;
    }
}
