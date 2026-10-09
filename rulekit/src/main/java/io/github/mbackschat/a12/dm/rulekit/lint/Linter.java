package io.github.mbackschat.a12.dm.rulekit.lint;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.github.mbackschat.a12.dm.core.Severity;

import io.github.mbackschat.a12.dm.rulekit.catalog.EmptyBehavior;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.dsl.Arithmetic;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateDifference;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExtraction;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRounding;
import io.github.mbackschat.a12.dm.rulekit.dsl.LengthOf;
import io.github.mbackschat.a12.dm.rulekit.dsl.OpListMath;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rounding;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.CompareOp;
import io.github.mbackschat.a12.dm.rulekit.dsl.ComparisonCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Concatenation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.CustomConditionRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListEntityListCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.EntityListPredicate;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListValueListCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListPredicate;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupSemanticIndexFieldValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedGroupPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndexFieldValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.RuleGroupPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.LogicCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.PatternCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.CustomTypeValidity;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateValidity;
import io.github.mbackschat.a12.dm.rulekit.dsl.RepetitionNotUniqueCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.ValueListCondition;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DiagnosticSource;

/**
 * The F4a <b>lint layer</b> — valid-but-suspicious findings over the typed AST (PROJECT-DESIGN §F4a;
 * study finding 7). This is the diagnostic kind {@code checkConsistency} cannot give an LLM: the
 * kernel only reports structural <em>errors</em>, so "compiles and is accepted, but probably not
 * what you meant" must come from rulekit.
 *
 * <p>Two tiers: <b>pure-AST</b> structural heuristics (run on any {@link Condition}) and
 * <b>model-context</b> heuristics (run on a {@link Rule} bound to a {@link BoundModel}, where the
 * rule's iteration is known). Each diagnostic is {@code source = LINT}, {@code severity = WARNING},
 * with an {@code RK_*} code and the offending operator in {@code where.operator}.
 */
public final class Linter {

    /**
     * The positive LIST quantifiers that count as guarding a negative condition under iteration. The singular
     * {@code FieldFilled}/{@code GroupFilled} forms are not here: they are matched structurally, by their own
     * record's {@code filled()} flag, so a name in this set would never be consulted for them.
     */
    private static final Set<String> POSITIVE_LIST_QUANTIFIERS = Set.of(
            "AllFieldsFilled", "AtLeastOneFieldFilled",
            "AllGroupsFilled", "AtLeastOneGroupFilled");

    /** The empty-operand facet source (CATALOG-SPEC §8) — drives the unguarded-comparison lint. */
    private final OperatorCatalog catalog = OperatorCatalog.load();

    /** Lint {@code condition} with no rule context (the {@code where.rule} is left empty). */
    public List<Diagnostic> lint(Condition condition) {
        return lint("", condition);
    }

    /** Lint {@code condition} as the error condition of the rule at {@code rulePath}. */
    public List<Diagnostic> lint(String rulePath, Condition condition) {
        List<Diagnostic> out = new ArrayList<>();
        visit(condition, null, rulePath, out);
        unguardedNumberComparison(condition, rulePath, out);
        unguardedStringConcatComparison(condition, rulePath, out);
        return List.copyOf(out);
    }

    /**
     * Pre-warn the <b>empty-string-in-concatenation</b> trap (KERNEL-FINDINGS §2, surfaced by an N6 run):
     * a plain string comparison is not evaluated when empty, but a string field that is an <em>operand of
     * a concatenation</em> concatenates as {@code ""}, so {@code [Full] != [First] + " " + [Last]} fires on
     * an empty {@code First}/{@code Last} (it reads {@code [Full] != " "}). Flags a comparison whose operand
     * is a concatenation containing a string field with no presence guard. Advisory (the other operand's
     * value is unknown, so we can't prove it fires — but the substitution is a real trap). Pure-AST.
     */
    private void unguardedStringConcatComparison(Condition condition, String rulePath, List<Diagnostic> out) {
        Set<String> guarded = new HashSet<>();
        collectGuardedFields(condition, guarded);
        collectUnguardedConcatCompares(condition, guarded, rulePath, out);
    }

    private void collectUnguardedConcatCompares(Condition c, Set<String> guarded, String rulePath,
            List<Diagnostic> out) {
        switch (c) {
            case LogicCondition l -> {
                collectUnguardedConcatCompares(l.left(), guarded, rulePath, out);
                collectUnguardedConcatCompares(l.right(), guarded, rulePath, out);
            }
            case ComparisonCondition cmp -> {
                List<String> unguarded = new ArrayList<>();
                concatUnguardedStringFields(cmp.left(), guarded, unguarded);
                concatUnguardedStringFields(cmp.right(), guarded, unguarded);
                if (!unguarded.isEmpty()) {
                    flagUnguardedConcat(unguarded, cmp, rulePath, out);
                }
            }
            default -> { }
        }
    }

    /**
     * The kinds a concatenation joins as text and so reads as {@code ""} when empty: a string, and an enumeration,
     * whose stored value it joins (KERNEL-FINDINGS KF530).
     */
    private static final Set<ValueKind> CONCATENATED_AS_TEXT = EnumSet.of(ValueKind.STRING, ValueKind.ENUMERATION);

    /** If {@code op} is a concatenation, collect its text field parts that aren't presence-guarded. */
    private static void concatUnguardedStringFields(Calculation op, Set<String> guarded, List<String> out) {
        if (op instanceof Concatenation concat) {
            for (Calculation part : concat.parts()) {
                if (part instanceof FieldValue fv && CONCATENATED_AS_TEXT.contains(fv.field().kind())
                        && !guarded.contains(fv.field().path().value()) && !out.contains(fv.field().path().value())) {
                    out.add(fv.field().path().value());
                }
            }
        }
    }

    private void flagUnguardedConcat(List<String> fields, ComparisonCondition cmp, String rulePath,
            List<Diagnostic> out) {
        String list = String.join(", ", fields);
        out.add(new Diagnostic(Severity.WARNING, DiagnosticSource.LINT,
                Optional.of("RK_UNGUARDED_STRING_CONCAT"),
                "the concatenation compares text field(s) " + list + " whose presence isn't guarded — an"
                        + " empty string or enumeration concatenates as \"\" (it is NOT 'not evaluated' like a"
                        + " plain string comparison), so the rule also fires when " + list + " is empty",
                new Diagnostic.Where(rulePath, Optional.of(cmp.operatorId())),
                Optional.of("AllFieldsFilled(" + list + ") And <the comparison>"),
                Optional.of("unlike a plain string comparison (an empty string is not evaluated), a string in a"
                        + " concatenation concatenates as \"\" (KERNEL-FINDINGS §2), so guard the concatenated"
                        + " operands with AllFieldsFilled if an empty value should not trip the rule")));
    }

    /**
     * Lint {@code rule} against {@code model} — the pure-AST lints plus the model-context ones that
     * need the rule's iteration (whether its error field lies under a repeatable group).
     */
    public List<Diagnostic> lint(BoundModel model, Rule rule) {
        String rulePath = rule.group().path() + "/" + rule.name();
        List<Diagnostic> out = new ArrayList<>(lint(rulePath, rule.condition()));
        invertedRequiredness(rule.condition(), rule.errorField().path().value(), rulePath, out);
        if (model.iteratesForErrorField(rule.errorField().path().value())) {
            negativeUnderIteration(rule.condition(), rule.errorField().path().value(), rule.group().path().value(),
                    model, rulePath, out);
        }
        return List.copyOf(out);
    }

    /**
     * Lint a candidate condition for a rule whose error field is {@code errorFieldPath}, without a
     * built {@link Rule} — the entry the CLI's {@code add} uses on a condition read from text
     * (F10), so the model-context lints (inverted requiredness, negative-under-iteration) can fire on
     * the BA path. The rule's group is taken as the error field's parent (the {@code add} default).
     */
    public List<Diagnostic> lint(BoundModel model, String errorFieldPath, Condition condition) {
        List<Diagnostic> out = new ArrayList<>(lint("", condition));
        invertedRequiredness(condition, errorFieldPath, "", out);
        if (model.iteratesForErrorField(errorFieldPath)) {
            int slash = errorFieldPath.lastIndexOf('/');
            String groupPath = slash > 0 ? errorFieldPath.substring(0, slash) : errorFieldPath;
            negativeUnderIteration(condition, errorFieldPath, groupPath, model, "", out);
        }
        return List.copyOf(out);
    }

    /**
     * An {@code errorCondition} is the <em>error scenario</em> (true ⇒ the data is invalid), so a
     * requiredness rule must fire on <em>absence</em>. A bare {@code FieldFilled(E)} on the rule's
     * own error field {@code E} fires on <em>presence</em> — flagging valid-looking data, almost
     * always an inversion ("did you mean {@code FieldNotFilled}?"). Conservative: only the bare
     * (sole-predicate) form — a guarded {@code FieldFilled(E) And <ctx>} is a legitimate
     * "{@code E} must be empty in this context" rule, so it is left alone.
     */
    private void invertedRequiredness(Condition condition, String errorFieldPath, String rulePath,
            List<Diagnostic> out) {
        if (condition instanceof FieldPresenceCondition f
                && f.filled()
                && f.field().path().value().equals(errorFieldPath)) {
            out.add(new Diagnostic(Severity.WARNING, DiagnosticSource.LINT,
                    Optional.of("RK_INVERTED_REQUIREDNESS"),
                    "the rule errors when '" + f.field().path() + "' IS filled — an errorCondition fires on"
                            + " the invalid case, so a requiredness rule should fire on absence",
                    new Diagnostic.Where(rulePath, Optional.of("FieldFilled")),
                    Optional.of("FieldNotFilled(" + f.field().path() + ")"),
                    Optional.of("an errorCondition is the error scenario (true ⇒ invalid); FieldFilled is true"
                            + " when the field HAS a value, so this flags present values, not missing ones —"
                            + " did you mean FieldNotFilled?")));
        }
    }

    /**
     * An iterating rule (error field under a repeatable group) whose condition carries a negative
     * presence predicate with <em>no</em> positive presence guard anywhere — the case the kernel
     * rejects with {@code MVK_NEG_CONDITION_IN_ITERATION} (KERNEL-FINDINGS §9). Conservative by
     * design: the kernel's full positive/negative classification is context-sensitive (~800 lines,
     * kernel-delegated to F4), so this fires only on the clearly-unguarded shape.
     */
    private void negativeUnderIteration(Condition condition, String errorFieldPath, String ruleGroupPath,
            BoundModel model, String rulePath, List<Diagnostic> out) {
        List<String[]> negatives = new ArrayList<>();
        reportUnguarded(condition, Set.of(), model, ruleGroupPath, negatives);
        if (negatives.isEmpty()) {
            return;
        }
        String[] negative = negatives.get(0);
        String guardGroup = model.nearestRepeatableAncestor(errorFieldPath)
                .orElse(ruleGroupPath);
        out.add(new Diagnostic(Severity.WARNING, DiagnosticSource.LINT,
                Optional.of("RK_NEG_CONDITION_IN_ITERATION"),
                "this rule iterates (error field '" + errorFieldPath + "' is under a repeatable group)"
                        + " and uses the negative condition " + negative[0] + "(" + negative[1] + ") with no"
                        + " positive presence guard — the kernel rejects unguarded negatives in iteration",
                new Diagnostic.Where(rulePath, Optional.of(negative[0])),
                Optional.of("guard it, e.g. GroupFilled(" + guardGroup + ") And <the existing condition>"),
                Optional.of("a negative condition under iteration must be guarded by a positive presence check so"
                        + " the kernel can bound the iteration (MVK_NEG_CONDITION_IN_ITERATION; KERNEL-FINDINGS §9)")));
    }

    /**
     * A positive presence guard licenses a negative only when it references the negative's OWN repeatable
     * level and is CONJOINED with it. Both halves are measured — an ancestor-level guard and a direct
     * {@code Or} sibling are each refused by the kernel
     * ({@code validate.laws.IterationGuardLevelLawsTest}, {@code …IterationGuardCompositionLawsTest}).
     *
     * <p>Under {@code And}, either side's positives are available to both; under {@code Or}, neither side
     * lends to the other — and that holds at every depth, not just between direct siblings. A positive reached
     * through ANY {@code Or} is unavailable, so only one reached through {@code And} alone licenses
     * ({@code validate.laws.IterationGuardNestedCompositionLawsTest}: the kernel refuses
     * {@code (same-level positive Or anything) And negative}). This cannot introduce a false positive on a
     * legal shape, because an admitted one still needs a same-level positive reached conjunctively, which is
     * untouched.
     */
    private void reportUnguarded(Condition condition, Set<String> available, BoundModel model,
            String ruleGroupPath, List<String[]> out) {
        if (condition instanceof LogicCondition logic) {
            Set<String> inner = available;
            if (logic.op() == LogicCondition.Op.AND) {
                inner = new HashSet<>(available);
                inner.addAll(positiveLevels(logic.left(), model));
                inner.addAll(positiveLevels(logic.right(), model));
            }
            reportUnguarded(logic.left(), inner, model, ruleGroupPath, out);
            reportUnguarded(logic.right(), inner, model, ruleGroupPath, out);
            return;
        }
        String[] negative = negativeOf(condition);
        if (negative == null) {
            return;
        }
        String level = negativeLevel(negative, model, ruleGroupPath);
        // A negative that sits at NO repeatable level does not iterate, so it is not this rule's business at
        // all. Distinguishing that from "a guard whose level could not be resolved" is why the two are
        // separate values: conflating them warned on `FieldFilled(row) And FieldNotFilled(nonRepeating)`,
        // which the kernel admits (under And, one admitted iterating child admits the condition).
        if (level == null) {
            return;
        }
        if (!available.contains(ANY_LEVEL) && !available.contains(level)) {
            out.add(negative);
        }
    }

    /**
     * The repeatable level a negative occupies, or {@code null} when it occupies none.
     *
     * <p>A GROUP negative is levelled like its positive mirror — the group itself when it repeats — so
     * {@code GroupNotFilled(X)} and {@code GroupFilled(X)} cannot disagree about X. A bare rule-group
     * negative is levelled by the rule's own group for the same reason.
     */
    private String negativeLevel(String[] negative, BoundModel model, String ruleGroupPath) {
        String path = negative[1];
        if ("RuleGroup".equals(path)) {
            path = ruleGroupPath;
        }
        boolean group = "GroupNotFilled".equals(negative[0]);
        String level = group ? groupLevelOf(path, model) : levelOf(path, model);
        if (ANY_LEVEL.equals(level)) {
            return null;
        }
        // A bare negative over a repeatable group is admitted when that group DECLARES AN INDEX FIELD and
        // its maximum repeatability is TWO. Both conjuncts are necessary and neither is sufficient, measured
        // over the whole grid at both positions (validate.laws.IterationGuardAdmissionRoutesLawsTest): three
        // repetitions refuse with an index, and two refuse without one. Conditioning on either alone loses
        // agreeing refusals — which is how this arrived, as a count-only suppression corrected from a corpus.
        // No mechanism is claimed for the boundary, only its location, so this reads the two facts.
        if (model.maxRepetitions(level) == 2 && model.declaresIndexField(level)) {
            return null;
        }
        return level;
    }

    /** The repeatable levels this subtree offers a positive presence for; {@link #ANY_LEVEL} when unknown. */
    private Set<String> positiveLevels(Condition condition, BoundModel model) {
        Set<String> levels = new HashSet<>();
        collectPositiveLevels(condition, model, levels);
        return levels;
    }

    private void collectPositiveLevels(Condition condition, BoundModel model, Set<String> out) {
        switch (condition) {
            // An OR lends nothing: neither arm holds on its own, so a positive inside one is not available to
            // a negative conjoined with the whole disjunction. Measured — the kernel refuses
            // `(same-level positive Or anything) And negative` exactly as it refuses the unguarded form
            // (`validate.laws.IterationGuardNestedCompositionLawsTest`).
            case LogicCondition l -> {
                if (l.op() == LogicCondition.Op.AND) {
                    collectPositiveLevels(l.left(), model, out);
                    collectPositiveLevels(l.right(), model, out);
                }
            }
            // An UNSTARRED field presence licenses ANY level, measured: a plain read into a sibling repeatable
            // group admits a negative at another level, while the STARRED quantifier over the same field does
            // not (validate.laws.IterationGuardAdmissionRoutesLawsTest). A starred quantifier is a different
            // condition record and reaches no arm here, so it contributes nothing and stays level-scoped —
            // which is the whole distinction, and the reason the level rule reads as it does only for it.
            case FieldPresenceCondition f -> {
                if (f.filled()) {
                    out.add(ANY_LEVEL);
                }
            }
            case GroupPresenceCondition g -> {
                if (g.filled()) {
                    String path = g.group().path().value();
                    out.add(licensesAnyLevel(path, model) ? ANY_LEVEL : groupLevelOf(path, model));
                }
            }
            case RuleGroupPresenceCondition r -> {
                if (r.filled()) {
                    out.add(ANY_LEVEL);
                }
            }
            // A value COMPARISON licenses as well as a presence check: the rule asks for a child that
            // REFERENCES the level, not one that asserts presence at it, and a comparison on a field there
            // does reference it (validate.laws.IterationGuardAdmissionRoutesLawsTest). Reading only presence
            // predicates was one of the routes the advisory warned over.
            case ComparisonCondition c -> {
                comparisonLevel(c.left(), model, out);
                comparisonLevel(c.right(), model, out);
            }
            case IndexedPresenceCondition p -> {
                if (p.filled()) {
                    out.add(levelOf(p.index().field().path().value(), model));
                }
            }
            // the row peer of the indexed field presence above, counted as a guard on the same unmeasured,
            // under-warning basis negativeOf states
            case IndexedGroupPresenceCondition p -> {
                if (p.filled()) {
                    out.add(groupLevelOf(p.group().group().path(), model));
                }
            }
            // A list predicate's operands are paths, but its admission rule at a level is the group-list
            // topology of KERNEL-FINDINGS §9 rather than a plain reference — unmeasured for this lint, so it
            // keeps the old blanket suppression rather than risking a false positive on a legal model.
            case EntityListPredicate p -> {
                if (POSITIVE_LIST_QUANTIFIERS.contains(p.operatorId())) {
                    out.add(ANY_LEVEL);
                }
            }
            case GroupListPredicate p -> {
                if (POSITIVE_LIST_QUANTIFIERS.contains(p.operatorId())) {
                    out.add(ANY_LEVEL);
                }
            }
            default -> { }
        }
    }

    /** The negative presence this condition IS, or {@code null}. */
    private String[] negativeOf(Condition condition) {
        return switch (condition) {
            case FieldPresenceCondition f ->
                    f.filled() ? null : new String[] {"FieldNotFilled", f.field().path().value()};
            case GroupPresenceCondition g ->
                    g.filled() ? null : new String[] {"GroupNotFilled", g.group().path().value()};
            case RuleGroupPresenceCondition r ->
                    r.filled() ? null : new String[] {"GroupNotFilled", "RuleGroup"};
            // An index selection pins a row only when its KEY is a constant; a key read from a field at a
            // repeatable level ITERATES, and the kernel refuses an unguarded negative over it
            // (validate.laws.IndexedNegativeKeyFormLawsTest). So the suppression turns on the key's FORM, not
            // on the selection: a constant-keyed negative is admitted and must not be flagged
            // (validate.laws.IndexedOperandIterationLawsTest), a field-keyed one is levelled by its KEY.
            // The POSITIVE arm still counts as a guard in collectPositiveLevels whatever the key, deliberately:
            // whether an index selection can license a plain negative at that level is not measured, and
            // counting it is the under-warning direction.
            case IndexedPresenceCondition i when !i.filled() ->
                    i.index() instanceof SemanticIndexFieldValue keyed
                            ? new String[] {"FieldNotFilled", keyed.indexField().path().value()}
                            : null;
            case IndexedPresenceCondition ignored -> null;
            // The row peer splits on the key's form the same way (validate.laws.IndexedNegativeKeyFormLawsTest).
            case IndexedGroupPresenceCondition g when !g.filled() ->
                    g.group() instanceof GroupSemanticIndexFieldValue keyed
                            ? new String[] {"FieldNotFilled", keyed.indexField().path().value()}
                            : null;
            default -> null;
        };
    }

    /** The level a comparison operand references, when it is a plain field read. */
    private void comparisonLevel(Calculation operand, BoundModel model, Set<String> out) {
        if (operand instanceof FieldValue value) {
            out.add(levelOf(value.field().path().value(), model));
        }
    }

    /**
     * An UNSTARRED reference to a repeatable group licenses a negative at ANY level, where the starred form
     * licenses only its own — measured, and it is what admits a guard over a sibling repeatable group
     * (validate.laws.IterationGuardAdmissionRoutesLawsTest). Root-versus-nested is not an axis for it.
     */
    private boolean licensesAnyLevel(String groupPath, BoundModel model) {
        return model.isRepeatingGroup(groupPath);
    }

    /** A GROUP operand's level is the group itself when it repeats, else its nearest repeatable ancestor. */
    private String groupLevelOf(String groupPath, BoundModel model) {
        return model.isRepeatingGroup(groupPath) ? groupPath : levelOf(groupPath, model);
    }

    private String levelOf(String path, BoundModel model) {
        return model.nearestRepeatableAncestor(path).orElse(ANY_LEVEL);
    }

    /** A guard whose level this lint cannot resolve; it suppresses, as it always did. */
    private static final String ANY_LEVEL = "\u0000any";


    /**
     * Flags a <b>number</b> comparison where an <em>unguarded</em> empty field would make the rule fire.
     * An unspecified number participates as {@code 0} in a comparison (KERNEL-SEMANTICS §2), so
     * {@code [Amount] < 100} fires on an EMPTY Amount ({@code 0 < 100}) — usually unintended. The check is
     * driven by the catalog's {@code emptyOperand} facet, in two lanes (detailed on
     * {@link #maybeFlagComparison}):
     * <ul>
     *   <li><b>PRECISE</b> — for each unguarded field whose emptiness injects a value into a side (the
     *       facet's {@code AS_ZERO}: a plain number field, a field inside arithmetic, or a field behind an
     *       AS_ZERO-reaching function such as {@code Length} or the date/time extractors), partially
     *       evaluate both sides under "that field is empty" and flag exactly when the comparison then
     *       holds — across <em>all</em> comparison operators (ordering, {@code ==}/{@code !=}, the
     *       tolerance family), not only ordering.</li>
     *   <li><b>ADVISORY</b> — flag a plain number field against an aggregate or expression whose value the
     *       partial evaluation cannot pin (the {@code [X] < Sum(…)} shape); AND (G41) an
     *       <b>ordering between two unguarded number fields</b> ({@code [A] < [B]}), where substituting
     *       either side empty leaves the other unpinned but the kernel still reads the empty side as 0 —
     *       so the comparison fires on the absence of either field. Both are real-but-unprovable, hence
     *       advisory.</li>
     * </ul>
     *
     * <p>The remaining exclusion is <b>equality/inequality field-vs-field</b> ({@code [A] == [B]}): a
     * different trap shape with its own scale gate, out of scope here. (Empty string/date/enum comparisons
     * are simply not evaluated, so they never need a guard — this check is number-specific.) Pure-AST — the
     * field kind is in the AST — so it fires on every lint entry point.
     */
    private void unguardedNumberComparison(Condition condition, String rulePath, List<Diagnostic> out) {
        Set<String> guarded = new HashSet<>();
        collectGuardedFields(condition, guarded);
        collectUnguardedNumberCompares(condition, guarded, rulePath, out);
    }

    /** Field paths positively guarded somewhere in the condition (FieldFilled / AllFieldsFilled). */
    private void collectGuardedFields(Condition c, Set<String> guarded) {
        switch (c) {
            case FieldPresenceCondition f -> {
                if (f.filled()) {
                    guarded.add(f.field().path().value());
                }
            }
            case EntityListPredicate p -> {
                if ("AllFieldsFilled".equals(p.operatorId())) {
                    for (var operand : p.operands()) {
                        if (operand instanceof FieldRef fr) {
                            guarded.add(fr.path().value());
                        }
                    }
                }
            }
            case LogicCondition l -> {
                collectGuardedFields(l.left(), guarded);
                collectGuardedFields(l.right(), guarded);
            }
            default -> { }
        }
    }

    private void collectUnguardedNumberCompares(Condition c, Set<String> guarded, String rulePath,
            List<Diagnostic> out) {
        switch (c) {
            case LogicCondition l -> {
                collectUnguardedNumberCompares(l.left(), guarded, rulePath, out);
                collectUnguardedNumberCompares(l.right(), guarded, rulePath, out);
            }
            case ComparisonCondition cmp -> maybeFlagComparison(cmp, guarded, rulePath, out);
            default -> { }
        }
    }

    /**
     * The two lanes, both driven by the catalog's {@code emptyOperand} facet (CATALOG-SPEC §8):
     *
     * <ol>
     *   <li><b>PRECISE</b> — for every unguarded field whose emptiness <em>injects a value</em> into a
     *       side (the facet's {@code AS_ZERO}: a plain number field, a field inside arithmetic, or a
     *       field behind an AS_ZERO function — {@code Length}, the date/time extractors, the
     *       {@code DifferenceIn*} family, the operand-list {@code Min}/{@code Max}), partially evaluate
     *       BOTH sides under "that field is empty" and flag exactly when the comparison then holds —
     *       direction-aware over <em>all</em> comparison operators (ordering, {@code ==}/{@code !=},
     *       the tolerance family).</li>
     *   <li><b>ADVISORY</b> — a plain number field against an aggregate/expression whose value the
     *       partial evaluation cannot pin (the T4 {@code [X] < Sum(…)} shape): the empty-as-0 trap is
     *       real but unprovable, so flag it advisorily. AND (G41) an <b>ordering between two unguarded
     *       number fields</b> ({@code [A] < [B]}): substituting either side empty leaves the other
     *       unpinned, but the kernel reads the empty side as 0, so the ordering can fire on the absence of
     *       either field — flagged once, naming both, only when NEITHER is guarded (a partial guard is a
     *       deliberate author choice, left alone). Equality/inequality field-vs-field stays out of scope
     *       (its own scale gate; an aggregate's empty-SET identity is the {@code aggregateIdentity}
     *       dimension, WI-5b — star/field-list scopes are not per-field guardable anyway).</li>
     * </ol>
     */
    private void maybeFlagComparison(ComparisonCondition cmp, Set<String> guarded, String rulePath,
            List<Diagnostic> out) {
        Set<String> flagged = new HashSet<>();
        for (FieldRef field : emptySubstitutableFields(cmp)) {
            String path = field.path().value();
            if (guarded.contains(path) || flagged.contains(path)) {
                continue;
            }
            BigDecimal left = valueWhenEmpty(cmp.left(), path);
            BigDecimal right = valueWhenEmpty(cmp.right(), path);
            if (left != null && right != null && holds(left, cmp.operator(), right)) {
                flagged.add(path);
                flagUnguardedNumber(field, cmp, rulePath, out);
            }
        }
        // ADVISORY — a plain number field vs a side the partial evaluation can't pin (aggregate/expression).
        String leftField = numberFieldPath(cmp.left());
        String rightField = numberFieldPath(cmp.right());
        if (leftField != null && !flagged.contains(leftField) && !guarded.contains(leftField)
                && isComplexNumberOperand(cmp.right()) && valueWhenEmpty(cmp.right(), leftField) == null) {
            flagUnguardedNumber(((FieldValue) cmp.left()).field(), cmp, rulePath, out);
        } else if (rightField != null && !flagged.contains(rightField) && !guarded.contains(rightField)
                && isComplexNumberOperand(cmp.left()) && valueWhenEmpty(cmp.left(), rightField) == null) {
            flagUnguardedNumber(((FieldValue) cmp.right()).field(), cmp, rulePath, out);
        } else if (isOrdering(cmp.operator()) && leftField != null && rightField != null
                && !guarded.contains(leftField) && !guarded.contains(rightField)
                && !flagged.contains(leftField) && !flagged.contains(rightField)) {
            // ADVISORY (field-vs-field ordering, G41) — both sides plain number fields, an ORDERING operator,
            // and NEITHER guarded. Substituting either side empty leaves the other unpinned, so the PRECISE
            // lane can't decide it; but the kernel reads whichever side is empty as 0, so `[A] < [B]` can fire
            // on the ABSENCE of either field (0 < [B] when B>0; [A] < 0 when A<0) — data-dependently. A partial
            // (one-side) guard is a deliberate author choice and left alone.
            flagUnguardedFieldVsField(leftField, rightField, cmp, rulePath, out);
        }
    }

    /**
     * G41 advisory — an unguarded number <b>field-vs-field ordering</b> ({@code [A] < [B]}, both sides plain
     * number fields, neither presence-guarded). One diagnostic naming both fields, since an empty value on
     * EITHER side reads as 0 and can make the ordering hold.
     */
    private void flagUnguardedFieldVsField(String leftPath, String rightPath, ComparisonCondition cmp,
            String rulePath, List<Diagnostic> out) {
        out.add(new Diagnostic(Severity.WARNING, DiagnosticSource.LINT,
                Optional.of("RK_UNGUARDED_NUMBER_COMPARISON"),
                "'" + leftPath + "' and '" + rightPath + "' are numbers compared with an ordering operator but"
                        + " neither's presence is guarded — an empty number reads as 0, so the rule also fires"
                        + " on the absence of either '" + leftPath + "' or '" + rightPath + "'",
                new Diagnostic.Where(rulePath, Optional.of(cmp.operatorId())),
                Optional.of("decide by intent: if a missing '" + leftPath + "' or '" + rightPath + "' should NOT"
                        + " trip the rule, guard both — AllFieldsFilled(" + leftPath + ", " + rightPath + ") And"
                        + " <the comparison>; if empty-as-0 firing IS the intent, the unguarded form is fine"),
                Optional.of("an unspecified number defaults to 0 in a comparison (KERNEL-SEMANTICS §2), so an"
                        + " ordering between two unguarded number fields fires whenever either side is empty and"
                        + " the resulting 0 satisfies the comparison — only a trap when empty should be NEUTRAL")));
    }

    /**
     * The fields whose emptiness injects a VALUE into this comparison — each is a candidate for the
     * empty-fires trap. Membership is decided by the catalog facet ({@code emptyOperandBehavior} ==
     * {@code AS_ZERO} for the node's operand at the field's position), never a hand-listed operator set.
     * Star/field-list aggregate scopes are excluded (not per-field guardable; their empty-set identity
     * is a different facet dimension).
     */
    private List<FieldRef> emptySubstitutableFields(ComparisonCondition cmp) {
        List<FieldRef> found = new ArrayList<>();
        collectSubstitutable(cmp.left(), found);
        collectSubstitutable(cmp.right(), found);
        return found;
    }

    private void collectSubstitutable(Calculation calc, List<FieldRef> found) {
        switch (calc) {
            case FieldValue fv -> {
                if (defaultsToZero(fv.field().kind())) {   // the NUMBER kind default (the header table)
                    found.add(fv.field());
                }
            }
            case Arithmetic a -> {
                collectSubstitutable(a.left(), found);
                collectSubstitutable(a.right(), found);
            }
            case LengthOf l -> {
                if (l.field() instanceof FieldRef field && asZero("Length", 0, field.kind())) {
                    found.add(field);
                }
            }
            case DateExtraction e -> {
                if (e.source() instanceof FieldValue fv && asZero(e.operatorId(), 0, fv.field().kind())) {
                    found.add(fv.field());
                }
            }
            case DateDifference d -> {
                for (Calculation side : List.of(d.left(), d.right())) {
                    if (side instanceof FieldValue fv && asZero(d.operatorId(), 0, fv.field().kind())) {
                        found.add(fv.field());
                    }
                }
            }
            case OpListMath ol -> ol.operands().forEach(o -> collectSubstitutable(o, found));
            case Rounding r -> collectSubstitutable(r.operand(), found);
            case FieldRounding fr -> {
                if (asZero(fr.operatorId(), 0, fr.field().kind())) {
                    found.add(fr.field());
                }
            }
            default -> { }
        }
    }

    /** Whether the facet says {@code operatorId}'s operand at {@code index} reads an empty {@code kind} value as 0. */
    private boolean asZero(String operatorId, int index, ValueKind kind) {
        return catalog.emptyOperandBehavior(operatorId, index, kind)
                .filter(b -> b == EmptyBehavior.AS_ZERO)
                .isPresent();
    }

    /** Whether the kind's header default reads an empty value as 0 (a bare comparison/arithmetic operand). */
    private boolean defaultsToZero(ValueKind kind) {
        return catalog.defaultEmptyBehavior(kind).filter(b -> b == EmptyBehavior.AS_ZERO).isPresent();
    }

    /**
     * Partial evaluation of {@code calc} under "the field at {@code emptyField} is empty" — the value
     * the side takes, or {@code null} when it cannot be pinned (another field's unknown value, a
     * star-scoped aggregate, division/power). The empty field's substitution comes from the facet
     * ({@code AS_ZERO} → 0); every other leaf must be a literal for the evaluation to stay concrete.
     */
    private BigDecimal valueWhenEmpty(Calculation calc, String emptyField) {
        return switch (calc) {
            case NumberLiteral n -> n.value();
            case FieldValue fv -> fv.field().path().value().equals(emptyField)
                    && defaultsToZero(fv.field().kind()) ? BigDecimal.ZERO : null;
            case Arithmetic a -> {
                BigDecimal l = valueWhenEmpty(a.left(), emptyField);
                BigDecimal r = valueWhenEmpty(a.right(), emptyField);
                if (l == null || r == null) {
                    yield null;
                }
                yield switch (a.op()) {
                    case ADD -> l.add(r);
                    case SUBTRACT -> l.subtract(r);
                    case MULTIPLY -> l.multiply(r);
                    // division/power have quiet non-execution edges (÷0, exponent range) — stay unknown
                    case DIVIDE, POWER -> null;
                };
            }
            case LengthOf l -> l.field() instanceof FieldRef field && field.path().value().equals(emptyField)
                    && asZero("Length", 0, field.kind()) ? BigDecimal.ZERO : null;
            case DateExtraction e -> e.source() instanceof FieldValue fv
                    && fv.field().path().value().equals(emptyField)
                    && asZero(e.operatorId(), 0, fv.field().kind()) ? BigDecimal.ZERO : null;
            case DateDifference d -> referencesField(d.left(), emptyField) || referencesField(d.right(), emptyField)
                    ? (asZero(d.operatorId(), 0, ValueKind.DATE) ? BigDecimal.ZERO : null)
                    : null;   // either operand empty → the whole difference reads 0 (IF73)
            case OpListMath ol -> {
                List<BigDecimal> vals = new ArrayList<>();
                for (Calculation o : ol.operands()) {
                    BigDecimal v = valueWhenEmpty(o, emptyField);
                    if (v == null) {
                        yield null;
                    }
                    vals.add(v);
                }
                yield switch (ol.op()) {
                    case MIN -> vals.stream().min(BigDecimal::compareTo).orElseThrow();
                    case MAX -> vals.stream().max(BigDecimal::compareTo).orElseThrow();
                    case ABS -> vals.get(0).abs();
                };
            }
            // rounding of an exact 0 is 0 in every mode; any other pinned value would need mode/places math
            case Rounding r -> {
                BigDecimal v = valueWhenEmpty(r.operand(), emptyField);
                yield v != null && v.signum() == 0 ? BigDecimal.ZERO : null;
            }
            case FieldRounding fr -> fr.field().path().value().equals(emptyField)
                    && defaultsToZero(fr.field().kind()) ? BigDecimal.ZERO : null;
            default -> null;
        };
    }

    private static boolean referencesField(Calculation calc, String path) {
        return calc instanceof FieldValue fv && fv.field().path().value().equals(path);
    }

    private void flagUnguardedNumber(FieldRef field, ComparisonCondition cmp, String rulePath, List<Diagnostic> out) {
        String path = field.path().value();
        String summary = field.kind() == ValueKind.NUMBER && isOrdering(cmp.operator())
                // the established wording for the classic shape (pinned by the CLI tour capture)
                ? "'" + path + "' is a number compared with an ordering operator but its presence isn't"
                        + " guarded — an empty number reads as 0, so the rule also fires on an empty '" + path + "'"
                // the facet-driven shapes: equality/tolerance ops, or a field reaching the comparison
                // through an AS_ZERO function (Length, YearFromDate, DifferenceIn*, …)
                : "'" + path + "' feeds this comparison but its presence isn't guarded — an empty '" + path
                        + "' still evaluates as 0 here (the operator's empty-operand semantics), making the"
                        + " comparison hold, so the rule also fires on an empty '" + path + "'";
        out.add(new Diagnostic(Severity.WARNING, DiagnosticSource.LINT,
                Optional.of("RK_UNGUARDED_NUMBER_COMPARISON"),
                summary,
                new Diagnostic.Where(rulePath, Optional.of(cmp.operatorId())),
                Optional.of("decide by intent: if an empty/missing '" + path + "' should NOT trip the rule, guard"
                        + " it — FieldFilled(" + path + ") And <the comparison>; if a missing value IS a violation"
                        + " (empty-as-0 should fire), the unguarded form is intentional — keep it"),
                Optional.of("an unspecified number defaults to 0 in a comparison (KERNEL-SEMANTICS §2), and the"
                        + " value functions (Length, the date/time extractors, DifferenceIn*) read an empty operand"
                        + " as 0 too — unlike a bare string/date/enum comparison, which an empty operand suppresses"
                        + " — so this is only a trap when empty should be NEUTRAL; an empty-as-violation rule is"
                        + " legitimately unguarded")));
    }

    /** A numeric operand that is neither a plain field nor a literal — an aggregate/function (Sum, Count, Length, …). */
    private static boolean isComplexNumberOperand(Calculation calc) {
        return calc.kind() == ValueKind.NUMBER
                && !(calc instanceof FieldValue) && !(calc instanceof NumberLiteral);
    }

    private static boolean isOrdering(CompareOp op) {
        return op == CompareOp.LT || op == CompareOp.LE || op == CompareOp.GT || op == CompareOp.GE;
    }

    /** Whether {@code l op r} holds — "would the comparison fire with the empty field substituted?" —
     *  over ALL comparison operators (the tolerance family fires when the difference EXCEEDS its range). */
    private static boolean holds(BigDecimal l, CompareOp op, BigDecimal r) {
        int c = l.compareTo(r);
        BigDecimal diff = l.subtract(r).abs();
        return switch (op) {
            case LT -> c < 0;
            case LE -> c <= 0;
            case GT -> c > 0;
            case GE -> c >= 0;
            case EQ -> c == 0;
            case NE -> c != 0;
            case TOLERANCE_1 -> diff.compareTo(BigDecimal.ONE) > 0;
            case TOLERANCE_2 -> diff.compareTo(BigDecimal.valueOf(2)) > 0;
            case TOLERANCE_5 -> diff.compareTo(BigDecimal.valueOf(5)) > 0;
            case TOLERANCE_10 -> diff.compareTo(BigDecimal.TEN) > 0;
        };
    }

    private static String numberFieldPath(Calculation calc) {
        return calc instanceof FieldValue fv && fv.field().kind() == ValueKind.NUMBER
                ? fv.field().path().value() : null;
    }

    /**
     * Analyze each maximal same-operator And/Or chain exactly once (at its root — where the parent
     * operator differs), then descend into the operands so nested chains of the other operator are
     * reached too.
     */
    private void visit(Condition condition, LogicCondition.Op parentOp, String rulePath, List<Diagnostic> out) {
        if (!(condition instanceof LogicCondition logic)) {
            return;
        }
        if (logic.op() != parentOp) {
            analyze(logic.op(), flatten(logic, logic.op()), rulePath, out);
        }
        visit(logic.left(), logic.op(), rulePath, out);
        visit(logic.right(), logic.op(), rulePath, out);
    }

    /** Flatten a chain of {@code op} into its operands (the leaves where the operator changes). */
    private List<Condition> flatten(Condition condition, LogicCondition.Op op) {
        if (condition instanceof LogicCondition logic && logic.op() == op) {
            List<Condition> operands = new ArrayList<>(flatten(logic.left(), op));
            operands.addAll(flatten(logic.right(), op));
            return operands;
        }
        return List.of(condition);
    }

    private void analyze(LogicCondition.Op op, List<Condition> operands, String rulePath, List<Diagnostic> out) {
        reportDuplicates(op, operands, rulePath, out);
        reportPresencePairs(op, operands, rulePath, out);
    }

    /** A structurally identical operand appearing twice in the same And/Or chain has no effect. */
    private void reportDuplicates(LogicCondition.Op op, List<Condition> operands, String rulePath, List<Diagnostic> out) {
        List<Condition> seen = new ArrayList<>();
        for (Condition operand : operands) {
            if (seen.contains(operand)) {
                out.add(lint("RK_REDUNDANT_OPERAND", op,
                        "the same operand appears more than once in this " + op.name()
                                + " — the duplicate has no effect",
                        Optional.of("remove the duplicate operand"),
                        Optional.of("a repeated And/Or operand is dead weight; And C And C ≡ C, Or C Or C ≡ C"),
                        rulePath));
                continue;
            }
            seen.add(operand);
        }
    }

    /**
     * A field appearing as both {@code FieldFilled} and {@code FieldNotFilled} in the same chain:
     * under {@code And} it is a contradiction (never fires); under {@code Or} it is a false
     * tautology (a formal-error value satisfies neither — they are not strict complements).
     */
    private void reportPresencePairs(LogicCondition.Op op, List<Condition> operands, String rulePath, List<Diagnostic> out) {
        for (int i = 0; i < operands.size(); i++) {
            if (!(operands.get(i) instanceof FieldPresenceCondition a)) {
                continue;
            }
            for (int j = i + 1; j < operands.size(); j++) {
                if (operands.get(j) instanceof FieldPresenceCondition b
                        && a.field().path().equals(b.field().path())
                        && a.filled() != b.filled()) {
                    out.add(presencePairLint(op, a.field().path().value(), rulePath));
                }
            }
        }
    }

    private Diagnostic presencePairLint(LogicCondition.Op op, String fieldPath, String rulePath) {
        if (op == LogicCondition.Op.AND) {
            return lint("RK_CONTRADICTORY_PRESENCE", op,
                    "'" + fieldPath + "' is required to be both filled and not filled — this And can never be true,"
                            + " so the rule never fires",
                    Optional.of("drop one side — a field cannot be both filled and not filled"),
                    Optional.of("FieldFilled(X) And FieldNotFilled(X) is unsatisfiable"),
                    rulePath);
        }
        return lint("RK_PRESENCE_NOT_COMPLEMENTARY", op,
                "'" + fieldPath + "' is tested as both filled and not filled in this Or — that is not the"
                        + " tautology it looks like",
                Optional.empty(),
                Optional.of("FieldFilled and FieldNotFilled are not strict complements: a field holding a"
                        + " formal-error value satisfies neither, so this Or is not always true"),
                rulePath);
    }

    private Diagnostic lint(String code, LogicCondition.Op op, String summary,
            Optional<String> fix, Optional<String> explain, String rulePath) {
        return new Diagnostic(Severity.WARNING, DiagnosticSource.LINT, Optional.of(code), summary,
                new Diagnostic.Where(rulePath, Optional.of(op.operatorId())), fix, explain);
    }
}
