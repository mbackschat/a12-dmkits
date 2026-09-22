package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.Backends;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.spi.BackendRequiredException;
import io.github.mbackschat.a12.dm.rulekit.spi.ReadBackendResolution;
import io.github.mbackschat.a12.dm.rulekit.spi.ReadBackends;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The <b>no-backend lane</b> — the Stage-3 acceptance bar (KERNEL-DECOUPLING-PROPOSAL §2c). It runs the kits
 * with <em>no kernel backend on the classpath</em> (a Maven-Central consumer's posture): this source set's
 * runtime classpath is kit main + Jackson + JUnit, deliberately NOT {@code :adapter} / {@code kernel-md-facade}
 * (build.gradle {@code noBackendTest}), so {@code ServiceLoader} finds no provider. It proves the §2c
 * degraded contract <b>row by row</b>, and above all that a green result can <b>never</b> be read as
 * kernel-confirmed without a backend (no fake green).
 *
 * <p>Fixture: an own-domain (insurance) model authored through modelkit — pure our-code, kernel-free.
 */
class NoBackendLaneTest {

    /** A self-contained insurance model, authored kernel-free through the typed modelkit surface. */
    private static String policyModel() {
        return DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> {
                    p.numberField("Premium", n -> n.fractionDigits(2));
                    p.group("Coverages", c -> {
                        c.repeatable(20);
                        c.numberField("Limit", n -> n.fractionDigits(2));
                    });
                })
                .build().toJson();
    }

    /** The environment itself has no backend — the single truth every gate stamps from. */
    @Test
    void theEnvironmentReportsNoBackend() {
        assertFalse(Backends.hasBackend(), "the no-backend lane must run with NO ConsistencyOracle provider");
        assertEquals(Verification.STRUCTURAL_ONLY, Backends.verification(),
                "with no backend, the environment verification is STRUCTURAL_ONLY");
        assertTrue(Backends.consistencyOracle().isEmpty(), "no ConsistencyOracle is discovered");
        assertTrue(Backends.backendInfo().isEmpty(), "no BackendInfo is discovered");
    }

    /** §2c row 1 — typed authoring is FULL kernel-free: the DSL builds a rule with no backend. */
    @Test
    void authoringIsFull() {
        BoundModel m = BoundModel.ofJson(policyModel());
        var condition = m.num("/Policy/Premium").filled()
                .and(m.num("/Policy/Premium").le(0));
        assertTrue(new io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer().render(condition).contains("Premium"),
                "authoring a condition through the DSL works with no backend");
    }

    /** §2c row 2 — model load + index is FULL structural, and the binding carries STRUCTURAL_ONLY. */
    @Test
    void modelLoadAndIndexIsFullStructural_bindingIsStructuralOnly() {
        BoundModel m = BoundModel.ofJson(policyModel());
        assertEquals(Optional.of(Verification.STRUCTURAL_ONLY), m.consistencyGate(),
                "a gated bind with no backend must carry STRUCTURAL_ONLY, never KERNEL_CONFIRMED — and the "
                        + "receipt must be PRESENT, because the gate did run; only an ungated bind has none");
        // the kernel-free index is fully available
        assertTrue(m.groupAt("/Policy/Coverages").isPresent(), "the index reads groups structurally");
        assertTrue(m.maxRepetitions("/Policy/Coverages") >= 2, "repeatability is read structurally");
        assertTrue(m.fieldAt("/Policy/Premium").isPresent(), "field paths read structurally");
        m.num("/Policy/Premium");   // the typed handle resolves — the field is a NUMBER (num() throws otherwise)

        // This lane is where the receipt's THIRD state earns its keep: with no backend a gated bind and a lenient
        // bind both reduce to "not kernel-confirmed", so under the old bare-Verification accessor they were
        // literally indistinguishable — and a consumer could publish STRUCTURAL_ONLY provenance for a path that
        // never gated. The gate that ran is present-and-degraded; the gate that never ran is absent.
        assertEquals(Optional.empty(),
                io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel.ofJsonLenient(policyModel(),
                        io.github.mbackschat.a12.dm.core.ReferenceResolvers.selfContained()).consistencyGate(),
                "an ungated bind must be distinguishable from a gated-but-unbacked one, even here");
    }

    /** §2c row 3 — the structural prechecks are FULL (a bad group path is rejected with RK_NO_SUCH_GROUP). */
    @Test
    void structuralPrechecksAreFull() {
        DraftRule badGroup = new DraftRule("/Policy/NotAGroup", "R", "../Premium",
                "FieldFilled(Premium)", "X", "ERROR");
        ValidationResult result = new RuleValidator().check(policyModel(), badGroup);
        assertFalse(result.ok(), "a precheck rejection is an ERROR");
        assertTrue(hasCode(result, "RK_NO_SUCH_GROUP"),
                () -> "the precheck fires with no backend, got: " + result.diagnostics());
    }

    /**
     * §2c row 4 — the MVK verdict degrades to STRUCTURAL_ONLY: no verdict is fabricated, a
     * {@code RK_NO_KERNEL_BACKEND} advisory rides the result, and — the acceptance bar — an {@code ok()} that
     * a consumer could misread as kernel-green is impossible: {@code verification()} is never KERNEL_CONFIRMED.
     */
    @Test
    void mvkVerdictIsStructuralOnly_neverAFakeGreen() {
        DraftRule wellFormed = new DraftRule("/Policy/Coverages", "LimitPositive", "../Limit",
                "FieldFilled(Limit) And [Limit] <= 0", "LIMIT_POSITIVE", "ERROR");
        ValidationResult result = new RuleValidator().check(policyModel(), wellFormed);

        assertEquals(Optional.of(Verification.STRUCTURAL_ONLY), result.consistencyGate(),
                "the consistency gate did not run — the verdict is structural-only");
        assertTrue(hasCode(result, "RK_NO_KERNEL_BACKEND"),
                () -> "a STRUCTURAL_ONLY consistency-gate result carries the RK_NO_KERNEL_BACKEND advisory, got: "
                        + result.diagnostics());
        // ok() keeps meaning "no ERROR among the checks that ran" — but it is NOT kernel-green, and the only
        // honest signal of that is verification(); the lane's core promise is that they can never be conflated.
        assertTrue(result.ok(), "no structural ERROR ran, so ok() is true");
        assertFalse(result.consistencyGate().filter(Verification.KERNEL_CONFIRMED::equals).isPresent(),
                "NO FAKE GREEN: with no backend an ok() result must never claim KERNEL_CONFIRMED");
    }

    /**
     * The strongest form of "no fake green": a candidate the KERNEL would reject (an unfilled-guard trap) is
     * NOT caught with no backend — ok() is still true — so a consumer that read ok() without verification()
     * would be misled. The contract holds precisely because verification() is STRUCTURAL_ONLY here.
     */
    @Test
    void aKernelRejectableCandidateIsNotFalselyGreen() {
        // A condition the kernel would reject (Coverages is repeatable, so a bare unscoped compare is an
        // MVK error) — but with no kernel gate it isn't caught. ok() must be paired with STRUCTURAL_ONLY.
        DraftRule candidate = new DraftRule("/Policy", "PremiumCheck", "Premium",
                "[/Policy/Coverages/Limit] <= 0", "BAD", "ERROR");
        ValidationResult result = new RuleValidator().check(policyModel(), candidate);
        assertEquals(Optional.of(Verification.STRUCTURAL_ONLY), result.consistencyGate(),
                "no backend ran, so whatever ok() says, the verdict is structural-only — not kernel-green");
    }

    /** §2c row 5 — condition read-back has no honest structural-only answer: it fails fast. */
    @Test
    void readBackFailsFast() {
        assertInstanceOf(ReadBackendResolution.None.class, ReadBackends.current(),
                "no ConditionReadBack provider is discovered");
        BackendRequiredException ex = assertThrows(BackendRequiredException.class, ReadBackends::require,
                "read-back with no backend must fail fast, not degrade");
        assertTrue(ex.getMessage().contains("read-back") || ex.getMessage().contains("read conditions"),
                () -> "the message is corrective, got: " + ex.getMessage());
    }

    private static boolean hasCode(ValidationResult result, String code) {
        List<io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic> ds = result.diagnostics();
        return ds.stream().anyMatch(d -> d.code().filter(code::equals).isPresent());
    }
}
