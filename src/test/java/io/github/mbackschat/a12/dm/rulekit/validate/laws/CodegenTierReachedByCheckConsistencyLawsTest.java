package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * That {@code checkConsistency} runs the CODEGEN tier's own checks — measured, because a whole tier of 26
 * codes was scoped out of {@code docs/MVK-LEDGER.md} on the reading that it belongs to code generation, and
 * the coverage question a portable VALID rests on is per-CODE.
 *
 * <p><b>One reachable code settles the tier, not one code.</b> {@code NoMetaModelChecks} has exactly one
 * public entry point — {@code check()}, constructed over {@code CodeGenData} — and raises all 26 from
 * private methods beneath it (source-read, kernel {@code 30.8.1}). So this row is not about time zones: it
 * is the evidence that the tier's single door is opened from the consistency path at all. The 18 of those
 * 26 the portable checker does not report (2026-09-16, derived by
 * {@code CodegenTierCodeDenominatorGuardTest}, which pins both sets) are therefore each a candidate false
 * pass, which is what the coverage-residue record in
 * {@code docs/archived/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md} tracks.
 *
 * <p><b>And the door has a name, which matters because the route is narrower than "consistency re-runs
 * codegen".</b> {@code ExternalDocumentModelService.checkConsistency} generates nothing itself; it runs
 * {@code DocumentModelConsistencyRulesProvider}'s eight rules, of which {@code CodeGenerationRule} calls
 * {@code generateValidationCode(model, new GroovyCodeGeneratorConfig(null), null, …)} and reports the
 * notifications back. So the dialect is fixed at Groovy and the parameter object is caller-built — two
 * facts that decide several residue rows, because a check reading {@code getParams()} sees defaults no
 * stored model can populate. What makes the assertion below identify this tier rather than merely coincide
 * with it: {@code MVK_INVALID_TIME_ZONE} has exactly ONE raising site in the checkout
 * ({@code NoMetaModelChecks:145}).
 *
 * <p>The tier's reachability had already been established once, indirectly and without anyone reading it
 * that way: {@link GroupIndexAndSortReferenceLawsTest} expects {@code MVK_INVALID_INDEX_FIELD} — also a
 * {@code NoMetaModelCheckErrorType} member — from the real Kernel. This test states the fact directly so it
 * cannot be lost again behind a row about index fields.
 */
class CodegenTierReachedByCheckConsistencyLawsTest {

    /** Minimal and rule-free on purpose: nothing here can draw a finding except the member under test. */
    private static final String BASE = DocumentModelDraft.named("timezone-probe").locale("en_US")
            .rootGroup("Order", o -> o.field("Code", FieldKind.STRING))
            .build().toJson();

    private static String withTimeZone(String zone) {
        return ModelDocument.read(BASE).setTimeZone(zone).toJson();
    }

    /**
     * An unparseable {@code modelConfig.timeZone} draws the codegen tier's own class from
     * {@code checkConsistency}.
     *
     * <p>The second row is a WITHIN-MODEL control — the same model differing in exactly one builder call —
     * so the first cannot pass because the fixture is broken in some way nobody named.
     */
    @Test
    void anInvalidModelTimeZoneIsReportedByCheckConsistency() {
        assertEquals("MVK_INVALID_TIME_ZONE", KernelLaws.verdictOf(withTimeZone("Not/AZone")));
        assertEquals(KernelLaws.ADMITTED, KernelLaws.verdictOf(withTimeZone("Europe/Berlin")));
    }
}
