package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * Tier-3 refactor: <b>extract group → include</b> (task #7, CLI-SPEC §6) — lift a subtree into a
 * standalone sub-model and re-mount it at the same name, so every composed path is preserved. This
 * <em>builds</em> the post-extraction state the probe ({@code ExtractAsIncludeProbeTest}) only
 * hand-asserted: the base is slimmed to a mount, the subtree's own rule travels into the sub-model, an
 * external rule reaching into the (now mounted) subtree still resolves, and the composed pair validates.
 */
class ExtractGroupAsIncludeTest {

    private static final IDocumentModelSerializer SERIALIZER =
            new DocumentModelServiceFactory().createDocumentModelSerializer();

    /** /Order { OrderDate, Shipping { Zip, City } } + an internal rule (relative) + an external rule into Shipping. */
    private static String orderJson() {
        String base = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("OrderDate", FieldKind.DATE)
                        .group("Shipping", sh -> sh.field("Zip", FieldKind.STRING).field("City", FieldKind.STRING)))
                .build()
                .toJson();
        return EditSession.read(base)
                // internal: a rule scoped INSIDE Shipping, referencing only its own fields (relative) — it travels
                .addRule(m -> m.rule(m.group("/Order/Shipping")).name("CityNeedsZip")
                        .errorField(m.str("/Order/Shipping/Zip"))
                        .when(m.str("/Order/Shipping/City").filled().and(m.str("/Order/Shipping/Zip").notFilled()))
                        .code("CITY_ZIP").severity(Severity.ERROR).message(en("A zip is required with a city.")).build())
                // external: a rule at /Order reaching INTO Shipping (the mount preserves the path) — must survive
                .addRule(m -> m.rule(m.group("/Order")).name("ZipFormat")
                        .errorField(m.str("/Order/Shipping/Zip"))
                        .when(m.str("/Order/Shipping/Zip").filled().and(m.str("/Order/Shipping/Zip").violates("[0-9]{5}")))
                        .code("ZIP_FMT").severity(Severity.ERROR).message(en("The zip must be five digits.")).build())
                .toJson();
    }

    @Test
    void extractsTheSubtreeAndTheComposedPairValidates() {
        ModelDocument base = ModelDocument.read(orderJson());
        String subModel = base.extractGroupAsInclude("/Order/Shipping", "shipping");
        String baseJson = base.toJson();

        assertTrue(baseJson.contains("\"modelAlias\" : \"shipping\""), "the base now mounts the sub-model");
        assertFalse(baseJson.contains("\"name\" : \"City\""), "Shipping's fields moved out of the base");
        assertTrue(subModel.contains("CityNeedsZip"), "the internal rule travelled into the sub-model");
        assertFalse(baseJson.contains("CityNeedsZip"), "...and left the base");
        assertTrue(baseJson.contains("ZipFormat"), "the external rule stays in the base");

        IDocumentModelReferenceResolver resolver = reference -> {
            if ("shipping".equals(reference)) {
                try {
                    return SERIALIZER.deserialize(new StringReader(subModel));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            throw new IllegalStateException("unknown reference: " + reference);
        };

        // the kernel gate over the composed pair — a broken extraction would throw here
        assertDoesNotThrow(() -> EditSession.read(baseJson, resolver).validate(),
                "the extracted base + sub-model must compose into a kernel-valid model");

        // the external rule still resolves the mounted field (path preserved), via the adapter
        BoundModel composed = BoundModel.ofJson(baseJson, resolver);
        assertTrue(KernelAdapter.of(composed).whereUsed("/Order/Shipping/Zip").contains("/Order/ZipFormat"),
                "the external rule still references the now-mounted field");
    }

    @Test
    void extractThenInlineRoundTripsToASelfContainedModel() {
        ModelDocument base = ModelDocument.read(orderJson());
        String subModel = base.extractGroupAsInclude("/Order/Shipping", "shipping");

        // inline the include back: the mount becomes a real group again, the reference is dropped
        ModelDocument inlined = ModelDocument.read(base.toJson()).inlineInclude("shipping", subModel);
        String inlinedJson = inlined.toJson();

        assertFalse(inlinedJson.contains("modelAlias"), "the mount became a real group");
        assertTrue(inlined.includes().isEmpty(), "the header reference is gone");
        assertTrue(inlined.fieldPaths().contains("/Order/Shipping/Zip"), "the field is inline under the group again");
        assertTrue(inlinedJson.contains("CityNeedsZip"), "the subtree's own rule came back");
        // self-contained again — validates with no resolver
        assertDoesNotThrow(() -> EditSession.read(inlinedJson).validate(),
                "the inlined model is self-contained and kernel-valid");
    }
}
