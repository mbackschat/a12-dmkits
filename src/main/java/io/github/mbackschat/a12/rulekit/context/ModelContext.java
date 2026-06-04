package io.github.mbackschat.a12.rulekit.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.mbackschat.a12.rulekit.catalog.GotchaKind;
import io.github.mbackschat.a12.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.dsl.Condition;
import io.github.mbackschat.a12.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.rulekit.dsl.ConditionReferences;
import io.github.mbackschat.a12.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.rulekit.dsl.FieldInfo;
import io.github.mbackschat.a12.rulekit.dsl.GroupInfo;
import io.github.mbackschat.a12.rulekit.dsl.OperatorUsage;
import io.github.mbackschat.a12.rulekit.model.Suggest;
import io.github.mbackschat.a12.rulekit.model.ValueKind;

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

    public ModelContext(BoundModel model) {
        this.model = model;
    }

    /**
     * Explain a condition (F9) — the operators it uses (with catalog meaning + relevant gotchas), the
     * fields/groups it references (with model kind), whether it iterates over a repeatable group, and
     * a one-line gloss. Works identically for a rulekit-built condition and a converted-foreign one
     * (F10 ingest): feed {@code adapter.ingest(rule)}'s {@code Converted.condition()} straight in.
     */
    public RuleExplanation explain(Condition condition) {
        String text = new DslRenderer(ConditionLanguage.EN).render(condition);

        List<RuleExplanation.Operator> operators = new ArrayList<>();
        for (String id : OperatorUsage.of(condition)) {
            CATALOG.byId(id).ifPresentOrElse(
                    e -> operators.add(new RuleExplanation.Operator(
                            e.id(), e.keywords().en(), e.kind().name(),
                            e.meaning() == null ? "" : e.meaning(),
                            e.gotchas().stream()
                                    .filter(g -> g.kind() != GotchaKind.RENDERING)
                                    .map(g -> g.text())
                                    .toList())),
                    () -> operators.add(new RuleExplanation.Operator(id, id, "?", "", List.of())));
        }

        ConditionReferences refs = ConditionReferences.of(condition);
        List<RuleExplanation.Reference> references = new ArrayList<>();
        refs.fieldPaths().stream().sorted().forEach(p ->
                references.add(new RuleExplanation.Reference(p,
                        model.fieldAt(p).map(f -> f.kind().name()).orElse("?"))));
        refs.groupPaths().stream().sorted().forEach(g ->
                references.add(new RuleExplanation.Reference(g, "GROUP")));

        Optional<String> iterationGroup = iterationScope(refs);
        return new RuleExplanation(text, operators, references,
                iterationGroup.isPresent(), iterationGroup, gloss(operators, references, iterationGroup));
    }

    /** The repeatable group the condition ranges over, if any (a referenced field's repeatable ancestor, or a repeatable referenced group). */
    private Optional<String> iterationScope(ConditionReferences refs) {
        for (String fieldPath : refs.fieldPaths()) {
            Optional<String> ancestor = model.nearestRepeatableAncestor(fieldPath);
            if (ancestor.isPresent()) {
                return ancestor;
            }
        }
        Set<String> repeatable = model.groups().filter(GroupInfo::repeating)
                .map(GroupInfo::path).collect(Collectors.toSet());
        return refs.groupPaths().stream()
                .filter(g -> repeatable.contains(g) || repeatable.stream().anyMatch(r -> g.startsWith(r + "/")))
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

    /** The model as a compact card — every field (kind + scale/enum-domain) and group (repeatability). */
    public ModelCard describe() {
        List<ModelCard.Field> fields = model.fields().map(this::fieldCard).toList();
        List<ModelCard.Group> groups = model.groups()
                .map(g -> new ModelCard.Group(g.path(), g.repeating()))
                .toList();
        return new ModelCard(fields, groups);
    }

    private ModelCard.Field fieldCard(FieldInfo field) {
        Optional<Integer> scale = field.kind() == ValueKind.NUMBER
                ? Optional.of(field.scale()) : Optional.empty();
        List<String> enumValues = field.kind() == ValueKind.ENUMERATION
                ? field.asEnum().allowedValues() : List.of();
        return new ModelCard.Field(field.path(), field.name(), field.kind().name(), scale, enumValues);
    }

    /** Fields matching {@code query}, best match first; a fuzzy fallback resolves typos when nothing else hits. */
    public List<FieldMatch> find(String query) {
        String q = query.strip();
        List<FieldInfo> all = model.fields().toList();

        List<FieldMatch> matches = new ArrayList<>();
        for (FieldInfo field : all) {
            FieldMatch.How how = classify(field, q);
            if (how != null) {
                matches.add(new FieldMatch(field.path(), field.name(), field.kind().name(), how));
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
                        .map(f -> new FieldMatch(f.path(), f.name(), f.kind().name(), FieldMatch.How.FUZZY))
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
