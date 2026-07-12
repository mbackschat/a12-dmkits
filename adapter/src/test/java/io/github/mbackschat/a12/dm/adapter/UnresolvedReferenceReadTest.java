package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.ICondition;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * The N8 tolerance hardening for an <b>unresolved reference</b> on read:
 * {@link KernelAdapter#read}'s {@code catch (RuntimeException)} branch degrades a rule the kernel
 * accepts — but whose entity rulekit's content-only {@code ModelIndex} can't resolve — to a verbatim
 * {@link ReadResult.Opaque}, rather than letting the exception escape and crash a whole-DM read. This is
 * distinct from the two already-locked tolerance paths: {@code UnsupportedConstructException} (a modeled
 * field, unmodeled operator — {@code ReadRoundTripTest.preservesAnUnconvertedConstructAsOpaque}) and
 * the {@code $}-drop count-check ({@code OuterCorrelationBackstopTest}).
 *
 * <p><b>Why this is white-box (drives the converter, not the full load→read path).</b> For a
 * self-contained single-document model the branch is <em>unreachable</em> through {@code ofJson}: a
 * reference not under {@code modelRoot} fails {@code checkConsistency} with {@code MVK_INVALID_ENTITY}
 * (so the model never loads), and {@code ModelIndex} indexes <em>all</em> of {@code modelRoot}, so every
 * consistency-passing reference resolves. The branch defends an <em>asymmetry</em> — the kernel's
 * parse/consistency view holds an entity rulekit's content index lacks (a header entity, or a
 * form-level {@code HModel} reference in a multi-form document). This test reproduces the converter-side
 * effect of that asymmetry directly and deterministically: parse a rule the kernel accepts against a
 * model that <em>has</em> the field, then convert it against a model whose index legitimately lacks it.
 *
 * <p>The property locked: such a failure surfaces as a {@link RuntimeException} that is <em>not</em> an
 * {@link UnsupportedConstructException} — so it is routed to the second {@code catch} (→ Opaque,
 * verbatim) and not the first. If {@code ModelIndex} were ever changed to return an {@code Optional} /
 * throw a checked exception, or {@code UnsupportedConstructException} were widened, this fails loudly.
 */
class UnresolvedReferenceReadTest {

    /** A timesheet that HAS a {@code Total} field — the rule referencing it is kernel-valid here. */
    private static String withTotal() {
        return DocumentModelDraft.named("timesheet").locale("en_US")
                .rootGroup("Timesheet", t -> t
                        .dateField("PeriodEnd", d -> d.format("yyyy-MM-dd"))
                        .numberField("Total", n -> { }))
                .build().toJson();
    }

    /** The same timesheet WITHOUT {@code Total} — its content index can't resolve {@code /Timesheet/Total}. */
    private static String withoutTotal() {
        return DocumentModelDraft.named("timesheet").locale("en_US")
                .rootGroup("Timesheet", t -> t
                        .dateField("PeriodEnd", d -> d.format("yyyy-MM-dd")))
                .build().toJson();
    }

    @Test
    void anUnresolvableReferenceSurfacesAsACatchableRuntimeException() {
        // Parse a rule the kernel accepts (Total exists in this model)…
        BoundModel full = BoundModel.ofJson(withTotal());
        // A presence predicate (FieldFilled) — its converter path resolves the entity through the strict
        // model.field(path) → ModelIndex.require lookup. (Comparisons use the lenient model.fieldAt peek,
        // which degrades a missing field to UnsupportedConstructException — the already-locked first catch.)
        Rule rule = full.rule(full.group("/Timesheet")).name("TotalRule")
                .errorField(full.field("/Timesheet/Total"))
                .when(full.field("/Timesheet/Total").filled())
                .code("C").severity(Severity.ERROR).message(en("x")).build();
        ICondition parsed = KernelAdapter.of(full.add(rule)).parseCondition("/Timesheet/TotalRule");

        // …then convert it against a model whose content index lacks Total (the real asymmetry, simulated).
        ConditionConverter converter = new ConditionConverter(BoundModel.ofJson(withoutTotal()));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> converter.convert(parsed),
                "converting a reference absent from the content index must throw");

        // It must be the *unexpected* kind read routes to its Opaque-degrading catch — not the
        // strict converter-gap signal, which is the other (already-locked) tolerance path.
        assertFalse(ex instanceof UnsupportedConstructException,
                () -> "an unresolved reference is not an unsupported construct: " + ex);
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("no element at path"),
                () -> "the failure should name the unresolved path: " + ex.getMessage());
    }
}
