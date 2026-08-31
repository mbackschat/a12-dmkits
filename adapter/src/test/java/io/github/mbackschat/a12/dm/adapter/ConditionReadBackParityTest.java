package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.bridge.InterpreterConditionReadBack;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.spi.ConditionReadBack;

/**
 * The parity net for the two {@code ConditionReadBack} providers (KERNEL-DECOUPLING-PROPOSAL ruling 6):
 * {@link KernelConditionReadBack}, which parses through the kernel, and the kernel-free
 * {@link InterpreterConditionReadBack}, which parses through the interpreter and maps its AST onto rulekit's
 * typed {@code Condition}. A consumer resolves whichever is on the classpath, so a condition both providers
 * read must produce the same typed AST — measured as an identical rendering.
 *
 * <p>Lives in {@code :adapter} because this is the only module where both providers are on one classpath;
 * {@code :interpreter-bridge} itself is kernel-free in test scope too.
 *
 * <p>The corpus is <b>authored through the DSL</b> rather than read from a fixture file, so each case states
 * the construct it covers and the authored rendering is a third, independent witness: kernel-read, bridge-read
 * and as-written all have to agree.
 *
 * <p>The bridge maps a subset of the grammar and answers {@code Opaque} outside it. That asymmetry is data,
 * not a failure — but it is <b>pinned</b>: {@link #everyCorpusConditionIsReadByBothProviders} fails if a
 * condition this corpus covers stops converting, so a coverage regression cannot pass as "still tolerant".
 */
class ConditionReadBackParityTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    // Built ONCE for the class: :adapter:test carries timing-sensitive growth floors, and per-case kernel
    // work inflates their medians (CLAUDE.md, "A new test must not perturb timing-sensitive floors").
    private static final String ORDER_JSON = DocumentModelDraft.named("parity-order").locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Reference", FieldKind.STRING)
                    .field("Customer", FieldKind.STRING)
                    .numberField("Total", f -> f.fractionDigits(2))
                    .dateField("OrderedOn", f -> { })
                    .group("Lines", l -> l.repeatable(99)
                            .field("Article", FieldKind.STRING)
                            .numberField("Quantity", f -> f.fractionDigits(0))
                            .numberField("UnitPrice", f -> f.fractionDigits(2))))
            .build().toJson();

    /**
     * One case per mapped construct family. Each carries the rule group it is authored in and the error field
     * it reports on, because the kernel requires the error field to be referenced by the condition
     * ({@code MVK_ERROR_FIELD_NOT_REFERENCED}) and requires the asterisk on a reference that crosses into a
     * repeating group ({@code MVK_NO_WILDCARD}) — so a per-row construct is authored in {@code /Order/Lines}
     * and an aggregate over the rows in {@code /Order}.
     */
    private record Case(String name, String group, String errorField, Function<BoundModel, Condition> build) { }

    private static final List<Case> CORPUS = corpus();

    private static List<Case> corpus() {
        return List.of(
                order("FieldFilledRule", m -> Dsl.fieldFilled(m.field("/Order/Reference"))),
                order("NotFilledRule", m -> Dsl.fieldFilled(m.field("/Order/Reference"))
                        .and(Dsl.fieldNotFilled(m.field("/Order/Customer")))),
                order("LogicRule", m -> Dsl.fieldFilled(m.field("/Order/Reference"))
                        .and(Dsl.fieldFilled(m.field("/Order/Customer")))),
                order("OrRule", m -> Dsl.fieldFilled(m.field("/Order/Reference"))
                        .or(Dsl.fieldFilled(m.field("/Order/Customer")))),
                order("NumberLiteralRule", m -> Dsl.fieldFilled(m.field("/Order/Reference"))
                        .and(m.num("/Order/Total").gt(100))),
                order("StringLiteralRule", m -> m.str("/Order/Reference").eq("ACME")),
                order("PatternRule", m -> Dsl.matches(m.str("/Order/Reference"), "A.*")),
                order("PatternViolatedRule", m -> Dsl.fieldFilled(m.field("/Order/Reference"))
                        .and(Dsl.violates(m.str("/Order/Customer"), "B.*"))),
                order("AllFilledRule", m -> Dsl.allFieldsFilled(
                        m.field("/Order/Reference"), m.field("/Order/Customer"))),
                order("NoneFilledRule", m -> Dsl.noFieldFilled(
                        m.field("/Order/Reference"), m.field("/Order/Customer"))),
                order("DateRule", m -> Dsl.fieldFilled(m.field("/Order/Reference"))
                        .and(m.date("/Order/OrderedOn").before(Dsl.today()))),
                order("SumRule", m -> Dsl.fieldFilled(m.field("/Order/Reference"))
                        .and(Dsl.sum(m.flatten("/Order/Lines*/Quantity")).gt(0))),
                order("MaxRule", m -> Dsl.fieldFilled(m.field("/Order/Reference"))
                        .and(Dsl.maxValue(m.flatten("/Order/Lines*/UnitPrice")).gt(0))),
                order("DistinctRule", m -> Dsl.fieldFilled(m.field("/Order/Reference"))
                        .and(Dsl.numberOfDifferentValues(m.flatten("/Order/Lines*/Article")).gt(1))),
                order("SumOfProductsRule", m -> Dsl.fieldFilled(m.field("/Order/Reference"))
                        .and(Dsl.sumOfProducts(
                                m.flatten("/Order/Lines*/Quantity"), m.flatten("/Order/Lines*/UnitPrice"))
                                .gt(m.num("/Order/Total").asNumber()))),
                // Per-row: authored inside the repeating group, so the references need no asterisk.
                line("RowComparisonRule", m -> m.num("/Order/Lines/Quantity").asNumber()
                        .lt(m.num("/Order/Lines/UnitPrice").asNumber())),
                line("RowArithmeticRule", m -> m.num("/Order/Lines/Quantity").asNumber()
                        .times(m.num("/Order/Lines/UnitPrice").asNumber())
                        .gt(m.num("/Order/Total").asNumber())),
                line("RowFilledRule", m -> Dsl.allFieldsFilled(
                        m.field("/Order/Lines/Quantity"), m.field("/Order/Lines/Article"))));
    }

    private static Case order(String name, Function<BoundModel, Condition> build) {
        return new Case(name, "/Order", "/Order/Reference", build);
    }

    private static Case line(String name, Function<BoundModel, Condition> build) {
        return new Case(name, "/Order/Lines", "/Order/Lines/Quantity", build);
    }

    /** The model carrying every corpus rule, read back by both providers. */
    private static String ruledModelJson() {
        BoundModel m = BoundModel.ofJson(ORDER_JSON);
        List<Rule> rules = new ArrayList<>();
        for (Case c : CORPUS) {
            rules.add(m.rule(m.group(c.group())).name(c.name())
                    .errorField(m.field(c.errorField()))
                    .when(c.build().apply(m))
                    .code("C").severity(Severity.ERROR).message(en("x")).build());
        }
        return m.addAll(rules, List.of()).toJson();
    }

    @Test
    void everyCorpusConditionIsReadByBothProviders() {
        String json = ruledModelJson();
        Map<String, ReadResult> kernel = read(new KernelConditionReadBack(), json);
        Map<String, ReadResult> bridge = read(new InterpreterConditionReadBack(), json);

        java.util.Set<String> expected = new java.util.LinkedHashSet<>();
        CORPUS.forEach(c -> expected.add(c.name()));
        assertEquals(expected, ruleNames(kernel),
                "the kernel provider must find exactly the corpus rules");
        assertEquals(expected, ruleNames(bridge),
                "the bridge must find exactly the same rules as the kernel provider");

        BoundModel m = BoundModel.ofJson(ORDER_JSON);
        for (Case c : CORPUS) {
            String authored = RENDER.render(c.build().apply(m));
            assertEquals(authored, render(kernel, c.name()),
                    () -> c.name() + ": the kernel provider's read differs from what was authored");
            assertEquals(authored, render(bridge, c.name()),
                    () -> c.name() + ": the bridge's read differs from what was authored");
        }
    }

    /**
     * The anti-vacuity control: a corpus that silently shrank, or a rendering that collapsed to a constant,
     * would let the equality above pass while measuring nothing.
     */
    @Test
    void theCorpusCoversDistinctConstructs() {
        // The EXACT count, not a floor: a floor of 15 let three constructs disappear while still passing,
        // which is the shrink this assertion exists to catch. Adding a case updates this number deliberately.
        assertEquals(18, CORPUS.size(),
                "the corpus changed size — add the case's number here if that was intended");
        BoundModel m = BoundModel.ofJson(ORDER_JSON);
        assertEquals(CORPUS.size(),
                CORPUS.stream().map(c -> RENDER.render(c.build().apply(m))).distinct().count(),
                "every corpus entry must render to a distinct condition");
    }

    private static Map<String, ReadResult> read(ConditionReadBack provider, String json) {
        return provider.readAll(json, ReferenceResolvers.selfContained());
    }

    /** Both providers key by rule path; the corpus is keyed by bare name, so compare on the last segment. */
    private static java.util.Set<String> ruleNames(Map<String, ReadResult> results) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        results.keySet().forEach(k -> names.add(k.substring(k.lastIndexOf('/') + 1)));
        return names;
    }

    private static String render(Map<String, ReadResult> results, String ruleName) {
        ReadResult r = results.entrySet().stream()
                .filter(e -> e.getKey().endsWith("/" + ruleName) || e.getKey().equals(ruleName))
                .map(Map.Entry::getValue).findFirst().orElseThrow();
        return RENDER.render(assertInstanceOf(ReadResult.Converted.class, r,
                () -> ruleName + " did not convert: " + r).condition());
    }
}
