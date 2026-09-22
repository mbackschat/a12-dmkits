package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;

/**
 * Behavioral law — <b>the "required" property</b> (KERNEL-SEMANTICS §4; encoded in DM-JSON as a
 * field's {@code requirednessConfig: {mode: …}}). An unfilled required field produces the
 * {@code mandatoryField} <b>formal error</b>, which makes the field <b>"unknown"</b> (§3) — stronger
 * than an author-written {@code FieldNotFilled}.
 *
 * <p><b>The two modes differ on when the requirement applies</b> (verified — this is the gotcha):
 * <ul>
 *   <li>{@code absoluteOrRelativeToNextRepAncestor} ("Only if parent filled = no"): a non-repeatable
 *       field is <b>always</b> required (and a field under a repeatable ancestor is required once that
 *       ancestor is filled).</li>
 *   <li>{@code relativeToParent} ("= yes"): required <b>only when the parent group is filled</b>, where
 *       — for this non-repeatable parent — "filled" means admitted descendant content. A merely-created empty
 *       non-repeatable parent does not trigger it; a repeatable parent is different because its row is content.</li>
 * </ul>
 */
class RequiredPropertyLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");
    private static final Set<String> ORDER_ROW = Set.of("/Order");

    // Conditions are authored with the rulekit typed DSL (m) and rendered to the canonical string fed
    // to the kernel — no free-text condition literals. The kernel-law assertion is the safety net.
    // (Bound from the base model — the injected requirednessConfig does not change a field's handle.)
    private static final BoundModel m = BoundModel.ofJson(ORDER);

    private static String render(Condition c) {
        return RuntimeLaws.render(c);
    }

    // /Order/ShippingAddress/Street made required under each mode (a sub-group field, so the parent
    // ShippingAddress can genuinely be filled or not).
    private static final String STREET_RELATIVE = require(ORDER, "/Order/ShippingAddress/Street", "relativeToParent");
    private static final String STREET_ABSOLUTE =
            require(ORDER, "/Order/ShippingAddress/Street", "absoluteOrRelativeToNextRepAncestor");

    @Test
    void relativeToParentRequiresTheFieldOnlyWhenTheParentHasAFilledDescendant() {
        // parent ShippingAddress has no filled descendant (Street + City empty) → NOT required
        RuntimeLaws.Doc parentEmpty = RuntimeLaws.doc().group("/Order", 1).field("/Order/CustomerName", "Acme", 1, 1)
                .group("/Order/ShippingAddress", 1, 1)
                .emptyField("/Order/ShippingAddress/Street", 1, 1, 1)
                .emptyField("/Order/ShippingAddress/City", 1, 1, 1);
        assertFalse(RuntimeLaws.firedCodes(STREET_RELATIVE, parentEmpty).contains("mandatoryField"),
                "an empty parent (no filled descendant) does NOT make a relativeToParent field required");

        // a sibling (City) fills the parent → Street is now required, and it is empty → mandatoryField
        RuntimeLaws.Doc parentFilled = RuntimeLaws.doc().group("/Order", 1).field("/Order/CustomerName", "Acme", 1, 1)
                .group("/Order/ShippingAddress", 1, 1)
                .field("/Order/ShippingAddress/City", "Town", 1, 1, 1)
                .emptyField("/Order/ShippingAddress/Street", 1, 1, 1);
        assertTrue(RuntimeLaws.firedCodes(STREET_RELATIVE, parentFilled).contains("mandatoryField"),
                "once the parent is filled (City), the empty required Street raises mandatoryField");
    }

    @Test
    void absoluteModeAlwaysRequiresANonRepeatableField() {
        // same empty parent — but the absolute mode requires Street regardless of the parent's state
        RuntimeLaws.Doc parentEmpty = RuntimeLaws.doc().group("/Order", 1).field("/Order/CustomerName", "Acme", 1, 1)
                .group("/Order/ShippingAddress", 1, 1)
                .emptyField("/Order/ShippingAddress/Street", 1, 1, 1)
                .emptyField("/Order/ShippingAddress/City", 1, 1, 1);
        assertTrue(RuntimeLaws.firedCodes(STREET_ABSOLUTE, parentEmpty).contains("mandatoryField"),
                "a non-repeatable field is always required under absoluteOrRelativeToNextRepAncestor");
    }

    @Test
    void requiredMakesAnEmptyNumberUnknown_overridingEmptyAsZero() {
        // make Quantity (a number) always required; an empty required number is UNKNOWN, not 0 — so the
        // §2 empty-as-0 does not apply and [Quantity] < 100 does NOT fire (only the mandatory error does)
        String reqQty = require(ORDER, "/Order/Quantity", "absoluteOrRelativeToNextRepAncestor");
        String model = RuntimeLaws.withRule(reqQty, "/Order", "QtyLow", "../Quantity", "QTY_LOW",
                render(m.num("/Order/Quantity").lt(100)));

        Set<String> empty = RuntimeLaws.firedCodes(model, ORDER_ROW, Set.of("/Order/Quantity"),
                Map.of("/Order/CustomerName", "Acme"));
        assertTrue(empty.contains("mandatoryField"), () -> "the mandatory error fires: " + empty);
        assertFalse(empty.contains("QTY_LOW"),
                () -> "a required empty number is unknown — no 0-substitution, so [Quantity] < 100 does not fire: " + empty);

        assertTrue(RuntimeLaws.fires(model, "QTY_LOW", ORDER_ROW,
                Map.of("/Order/CustomerName", "Acme", "/Order/Quantity", "50")),
                "a filled required value evaluates normally: 50 < 100 fires");
    }

    /** Mark the field at {@code fieldPath} required with the given mode (inject requirednessConfig). */
    private static String require(String modelJson, String fieldPath, String mode) {
        int id = modelJson.indexOf("\"id\": \"" + fieldPath + "\"");
        if (id < 0) {
            throw new IllegalArgumentException("field not found: " + fieldPath);
        }
        int brace = modelJson.indexOf('{', modelJson.indexOf("\"Field\":", id));
        return modelJson.substring(0, brace + 1)
                + "\n\"requirednessConfig\": {\"mode\": \"" + mode + "\"},"
                + modelJson.substring(brace + 1);
    }
}
