package io.github.mbackschat.a12.dm.rulekit.validate;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * Contract lock for the two claims {@code schema/diagnostic.schema.json} makes about ATTRIBUTION — the
 * published description of {@code source} and {@code code}. Both were wrong on a shipped surface until
 * 2026-08-30, in the same direction: they told a consumer that reading one field settles who decided.
 *
 * <p>a12-kernel-lean reported the first and proposed, as the corrected form, that <i>"an {@code MVK_*}-coded
 * diagnostic can only come from a kernel notification"</i>. That is falsified below, which is why this class
 * exists rather than a prose fix: rulekit's own typed builders deliberately mint {@code MVK_*} codes at
 * {@code PRECHECK} to name the kernel class they anticipate, so the code alone attributes nothing.
 *
 * <p>The two halves are deliberately opposite failure directions, and each is the other's control:
 *
 * <ul>
 *   <li>a {@code KERNEL} source may carry <b>no code</b> — the deserialize/expand tier refuses before
 *       {@code checkConsistency} can classify, so codelessness is the tell for which kernel tier ran;</li>
 *   <li>an {@code MVK_*} code may ride a <b>{@code PRECHECK}</b> source — so a kernel basis is the PAIR,
 *       never either field read on its own.</li>
 * </ul>
 */
class DiagnosticAttributionContractTest {

    private static final BoundModel m = BoundModel.ofJson(Fixtures.subscriptionJson());

    /**
     * The gate ran and refused, but before any notification existed. {@code source} is derived from which
     * gate ran, so it is honestly {@code KERNEL} — and the absent code is the only thing separating this
     * tier from a {@code checkConsistency} verdict.
     */
    @Test
    void aKernelSourcedDiagnosticMayCarryNoCodeAtAll() {
        Diagnostic refusal = Diagnostic.gateRefusal(Verification.KERNEL_CONFIRMED,
                "the model could not be expanded", new Diagnostic.Where("/Subscription", Optional.empty()));

        assertEquals(DiagnosticSource.KERNEL, refusal.source(),
                "the kernel deserializer refused, so the origin is the kernel");
        assertEquals(Optional.empty(), refusal.code(),
                () -> "this tier fails before checkConsistency can classify, so it has no MVK_* code: " + refusal);
    }

    /** The control for the row above: without a kernel gate the SAME refusal is not attributed to the kernel. */
    @Test
    void theSameRefusalWithoutAKernelGateIsNotAttributedToIt() {
        Diagnostic refusal = Diagnostic.gateRefusal(Verification.STRUCTURAL_ONLY,
                "the model could not be expanded", new Diagnostic.Where("/Subscription", Optional.empty()));

        assertEquals(DiagnosticSource.PRECHECK, refusal.source(),
                "no kernel ran, so the kernel cannot be named as the decider");
    }

    /**
     * <b>THE DISCRIMINATOR against "an MVK_* code means the kernel decided".</b> This diagnostic is produced
     * entirely client-side, with no kernel on the path, and carries a kernel class name.
     */
    @Test
    void anMvkCodedDiagnosticCanBePurelyCLIENTSide() {
        Rule unreferenced = m.rule(m.group("/Subscription/Addons"))
                .name("UnreferencedErrorField")
                .errorField(m.num("/Subscription/Addons/MonthlyFee"))
                .when(m.field("/Subscription/Addons/Name").filled())
                .code("UNREF").severity(Severity.ERROR)
                .message(en("Name required."), de("Name erforderlich."))
                .build();

        List<Diagnostic> deferred = unreferenced.deferredChecks();

        assertTrue(deferred.stream().anyMatch(d -> d.source() == DiagnosticSource.PRECHECK
                        && d.code().filter("MVK_ERROR_FIELD_NOT_REFERENCED"::equals).isPresent()),
                () -> "a client-side pre-check mints the kernel's own class name at PRECHECK, so the code alone"
                        + " does not attribute the decision: " + deferred);
    }
}
