package io.github.mbackschat.a12.dm.adapter.review;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * MODEL-REVIEW — the perf-scaling lock for {@code model report} (Set RP). The engine used to recompute the
 * field→referrers inverse from scratch <b>per field</b> — {@link ModelReport#assemble} called the adapter's
 * per-field {@code whereUsed}/{@code whereUsedInComputations} once for every field, and each of those parses
 * every rule's/computation's condition through the kernel. That is {@code O(F·(R+C))} kernel condition-parses,
 * so the report did not finish on huge models (the shipped defect this set fixes).
 *
 * <p>The lock is <b>deterministic</b>, not wall-clock: {@link KernelAdapter#referenceParseCount()} counts the
 * kernel reference-parse ({@code getDirectlyReferencedEntities}), and one report must parse each rule and each
 * computation <b>at most once</b> (the {@code O(R+C)} single-pass inverse), never once per field. The
 * F-independence assertion pins it can't quietly regress by growing with the field count. The wall-clock
 * before/after evidence lives in the opt-in {@link #reportTiming()} (perf evidence is a measurement, not a gate).
 */
class ModelReportSinglePassTest {

    /** An insurance-domain model with {@code fields} number fields, {@code rules} validation rules (each over
     *  a distinct low field), and {@code comps} computations (each computing a distinct high field) — authored
     *  through the typed EditSession surface (dogfood), never hand-written DM-JSON. */
    private static String bulkModel(int fields, int rules, int comps) {
        DocumentModelDraft.Builder draft = DocumentModelDraft.named("bulk-policy").locale("en_US");
        draft.rootGroup("Policy", g -> {
            for (int i = 0; i < fields; i++) {
                g.field("F" + i, FieldKind.NUMBER);   // pure structure — no kernel cost
            }
        });
        EditSession s = EditSession.read(draft.build().toJson());
        for (int i = 0; i < rules; i++) {
            int fi = i;                               // a rule over F{i} — references the low fields
            s.addRule(m -> m.rule(m.group("/Policy"))
                    .name("R" + fi).errorField(m.num("/Policy/F" + fi)).when(m.num("/Policy/F" + fi).gt(0))
                    .code("R" + fi + "_CODE").severity(Severity.ERROR)
                    .message(en("F" + fi + " must be positive.")).build());
        }
        for (int j = 0; j < comps; j++) {
            int computed = fields - 1 - j;            // a computation filling a high field from a low one
            int source = j;
            s.addComputation(m -> m.computation(m.num("/Policy/F" + computed))
                    .name("Comp" + source).calc(m.num("/Policy/F" + source).value())
                    .message(en("F" + computed + " mirrors F" + source + ".")).build());
        }
        return s.toJson();
    }

    @Test
    void oneReportParsesEachRuleAndComputationAtMostOnce_notOncePerField() {
        String json = bulkModel(24, 6, 2);
        BoundModel model = BoundModel.ofJson(json);
        KernelAdapter adapter = KernelAdapter.of(model);
        int rules = adapter.ruleFullNames().size();
        int comps = adapter.computationFullNames().size();

        long before = adapter.referenceParseCount();
        ModelReport.assemble(json, model, adapter);
        long parses = adapter.referenceParseCount() - before;

        // the O(R+C) single-pass contract: never the O(F·(R+C)) per-field recomputation (here F=24 ⇒ ~192)
        assertTrue(parses <= rules + comps,
                () -> "one model report must parse each rule + computation at most once (R+C=" + (rules + comps)
                        + "), but it parsed " + parses + " times — the per-field where-used recomputation is back");
    }

    @Test
    void theInverseCostIsIndependentOfTheFieldCount() {
        // Same rule/computation shape, 4× the fields: a single-pass inverse parses the SAME number of times;
        // a per-field recomputation would parse ~4× as often. Pins the scaling, not just a threshold.
        long few = reportParses(bulkModel(20, 6, 2));
        long many = reportParses(bulkModel(80, 6, 2));
        assertTrue(many == few,
                () -> "quadrupling the field count changed the report's kernel parse count " + few + " → " + many
                        + " — the inverse must be built once (O(R+C)), independent of F");
    }

    /** The kernel reference-parses one {@link ModelReport} assembly triggers over {@code json}. */
    private static long reportParses(String json) {
        BoundModel model = BoundModel.ofJson(json);
        KernelAdapter adapter = KernelAdapter.of(model);
        long before = adapter.referenceParseCount();
        ModelReport.assemble(json, model, adapter);
        return adapter.referenceParseCount() - before;
    }

    /**
     * Opt-in wall-clock evidence (not a gate): {@code ./build.sh :adapter:test --tests "*ModelReportSinglePassTest"
     * -Drp.perf=true}. Prints the end-to-end {@code model report} assembly time on a several-hundred-field model,
     * so the before (per-field) vs after (single-pass) numbers can be recorded in the set report.
     */
    @Test
    @EnabledIfSystemProperty(named = "rp.perf", matches = "true")
    void reportTiming() throws Exception {
        String json = bulkModel(250, 250, 25);
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/rp-large.dm.json"), json);   // fixture for the CLI before/after
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t = System.nanoTime();
            ModelReport.of(json);
            best = Math.min(best, System.nanoTime() - t);
        }
        System.out.printf("[rp.perf] model report on 250 fields / 250 rules / 25 computations: %.1f ms%n",
                best / 1_000_000.0);
    }
}
