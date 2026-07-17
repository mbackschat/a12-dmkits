package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * §9 — an aggregate or a {@code Having} filter consumes a FLATTENED repetition list (a starred group
 * followed by a field, e.g. {@code Group* / Count}), never a bare group wildcard ({@code Group*}). The
 * kernel rejects the group wildcard with {@code MVK_NO_GROUPS_ALLOWED}; but because {@code FieldFilled}
 * also documents that code (for its own "you passed a group, use GroupFilled" case), the catalog-join
 * would mis-stamp the aggregate/Having case with the wrong "use GroupFilled" fix.
 * {@link io.github.mbackschat.a12.dm.rulekit.validate.StructuralKernelFix} carries the cross-context
 * <em>union</em> corrective instead — proven here against the real kernel.
 *
 * <p>Release-feedback trap (Set CA): a model-level aggregate over a repeatable group whose author
 * tried a group wildcard inside {@code Having}. The corrective must teach the flattened form (the
 * starred group then {@code Amount}) and {@code NumberOfFilledGroups(Lines*)} to count rows, not GroupFilled.
 */
class GroupWildcardAggregateLawsTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();
    private static final String ORDER = Fixtures.orderJson();

    @Test
    void theFlattenedFieldListIsTheValidAggregateForm() {
        // Sum over the flattened repetition list Items*/Count — the correct form the corrective points to.
        ValidationResult r = check("../CustomerName",
                "FieldFilled(CustomerName) And Sum(Items*/Count) > 5");

        assertTrue(r.ok(), () -> "the flattened field list must be accepted, got: " + r.diagnostics());
    }

    @Test
    void theGroupWildcardHavingIsRejectedWithACrossContextCorrective() {
        // The trap: a Having filter over a bare GROUP wildcard (Items*), not a flattened field list.
        ValidationResult r = check("../CustomerName",
                "FieldFilled(CustomerName) And Sum(Items* Having [Items/Count] > 0) > 5");

        Diagnostic groups = noGroupsAllowed(r);

        // The corrective must teach the REAL remedy — the flattened list + NumberOfFilledGroups to count
        // rows — not FieldFilled's "for a group use GroupFilled" (which the catalog-join mis-stamps here).
        String fix = groups.fix().orElse("");
        assertTrue(fix.contains("NumberOfFilledGroups"),
                () -> "the corrective must point at the flattened list / NumberOfFilledGroups, got: " + groups);
        assertTrue(groups.explain().filter(e -> !e.isBlank()).isPresent(),
                () -> "the code must carry an explain, got: " + groups);
    }

    @Test
    void theFieldPresenceContextStillSteersToGroupFilled() {
        // The union must not regress the OTHER MVK_NO_GROUPS_ALLOWED trigger: a field-presence predicate
        // (FieldFilled) applied to a group. Its remedy is GroupFilled, and the union still carries it.
        ValidationResult r = check("../CustomerName", "FieldFilled(ShippingAddress)");

        Diagnostic groups = noGroupsAllowed(r);
        assertTrue(groups.fix().orElse("").contains("GroupFilled"),
                () -> "the field-presence context must still steer to GroupFilled, got: " + groups);
    }

    private static Diagnostic noGroupsAllowed(ValidationResult r) {
        return r.diagnostics().stream()
                .filter(d -> d.code().filter("MVK_NO_GROUPS_ALLOWED"::equals).isPresent())
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected MVK_NO_GROUPS_ALLOWED, got: " + r.diagnostics()));
    }

    private static ValidationResult check(String errorField, String condition) {
        return VALIDATOR.check(ORDER, new DraftRule("/Order", "ProbeRule", errorField, condition, "PROBE", "ERROR"));
    }
}
