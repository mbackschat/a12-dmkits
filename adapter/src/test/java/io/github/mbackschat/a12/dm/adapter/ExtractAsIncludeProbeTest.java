package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * Probe for the <b>extract-group-as-include</b> refactor (CLI-SPEC §6, refactor task #6): does the
 * <em>post-extraction end state</em> validate through the real kernel? We don't need the extract op
 * itself — we hand-build the shape extraction would produce (a subtree lifted into a sub-model,
 * re-mounted at the same name) and ask the kernel whether it accepts it.
 *
 * <p>The "parent rule references an included field" leg is already green elsewhere
 * ({@code IncludesTypedefsReadTest}, {@code ModifyIncludedRuleTest}). The genuinely untested leg this
 * probe pins is whether an <b>included sub-model carries its OWN relative-pathed rule to the mount
 * point</b> — i.e. rules that lived inside the extracted subtree travel with it and validate in the
 * composed model. Either it greenlights the op or it names the boundary the op's safety gate must
 * refuse on.
 *
 * <p>Own domain (order/shipping): a {@code shipping} sub-model with its own internal rule, mounted
 * under {@code /Order/Shipping}, with an {@code order} rule reaching into the mount.
 */
class ExtractAsIncludeProbeTest {

    private static final String INCLUDED_POSTAL = "/Order/Shipping/PostalCode";
    /** The sub-model's own rule, addressed at its mounted full-name. */
    private static final String SUBMODEL_RULE_AT_MOUNT = "/Order/Shipping/PostalCodeRequired";
    private static final String PARENT_RULE = "/Order/ShippingPostalFormat";

    /** A standalone, includable sub-model (single non-repeatable root) that carries its OWN rule. */
    private static String shippingJson() {
        BoundModel shipping = BoundModel.ofJson(DocumentModelDraft.named("shipping").locale("en_US")
                .rootGroup("Shipping", s -> s
                        .field("PostalCode", FieldKind.STRING)
                        .field("Country", FieldKind.STRING))
                .build()
                .toJson());
        // an internal rule using only sub-model-local, relative-resolvable fields — the kind that
        // should travel with the subtree on extraction.
        return shipping.add(shipping.rule(shipping.group("/Shipping"))
                .name("PostalCodeRequired")
                .errorField(shipping.str("/Shipping/PostalCode"))
                .when(shipping.str("/Shipping/Country").filled()
                        .and(shipping.str("/Shipping/PostalCode").notFilled()))
                .code("POSTAL_REQUIRED").severity(Severity.ERROR)
                .message(en("A postal code is required once a country is set.")).build())
                .toJson();
    }

    private static ModelResolver resolver() {
        String includedJson = shippingJson();
        return reference -> {
            if ("shipping".equals(reference)) {
                return includedJson;
            }
            throw new IllegalStateException("unknown reference: " + reference);
        };
    }

    /** The base model that mounts {@code shipping} and adds a rule reaching into the mount. */
    private static BoundModel composed() {
        BoundModel base = BoundModel.ofJson(DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("OrderDate", FieldKind.DATE)
                        .mount("Shipping", "shipping"))
                .build()
                .toJson(), resolver());
        Condition postalFmt = base.str(INCLUDED_POSTAL).filled()
                .and(base.str(INCLUDED_POSTAL).violates("[0-9]{5}"));
        return base.add(base.rule(base.group("/Order"))
                .name("ShippingPostalFormat")
                .errorField(base.str(INCLUDED_POSTAL))
                .when(postalFmt).code("SHIP_POSTAL_FMT").severity(Severity.ERROR)
                .message(en("The shipping postal code must be five digits.")).build());
    }

    @Test
    void postExtractionStateValidatesAndTheParentRefResolves() {
        // BoundModel.add re-binds with the resolver, re-expands the include, and runs checkConsistency,
        // so a kernel rejection of the composed shape would throw here.
        BoundModel composed = assertDoesNotThrow(ExtractAsIncludeProbeTest::composed,
                "the kernel must accept a parent rule reaching into an included subtree");

        Set<String> usingPostal = KernelAdapter.of(composed).whereUsed(INCLUDED_POSTAL);
        assertTrue(usingPostal.contains(PARENT_RULE),
                () -> "the parent rule must resolve as a referrer of the included field, got: " + usingPostal);

        // separation: the sub-model is never inlined into the base on write-back.
        assertFalse(ModelDocument.read(composed.toJson()).mountsOfInclude("shipping").isEmpty(),
                "the include mount must survive");
        assertFalse(composed.toJson().contains("/Shipping/Country"),
                "included content must stay in the sub-model, not be inlined into the base");
    }

    @Test
    void theIncludedSubModelsOwnRuleTravelsToTheMountPath() {
        Set<String> ruleNames = KernelAdapter.of(composed()).readAll().keySet();
        assertTrue(ruleNames.contains(SUBMODEL_RULE_AT_MOUNT),
                () -> "the sub-model's own rule must surface at its mounted full-name; rules seen: " + ruleNames);
    }
}
