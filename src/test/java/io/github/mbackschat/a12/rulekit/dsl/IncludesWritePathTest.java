package io.github.mbackschat.a12.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;

import io.github.mbackschat.a12.rulekit.Fixtures;

/**
 * The owner's includes requirement (2026-06-04): a model with an {@code include} must expand so a
 * rule can reference a field in the included model, but **writing back keeps the two models
 * separate** — load + save is the identity on both. Fixture: {@code storefront.dm.json} includes
 * {@code catalog.dm.json} (own domain, adapted from the A12 Tools workspace include pattern). The
 * included field is addressed by its <b>name-path</b> {@code /Storefront/Inventory/ListPrice} (mount
 * group name + included field name). F-IDX: the index keys by name-path, so the {@code _}-suffixed
 * expanded <em>id</em>-path ({@code /Storefront/Inventory_/Catalog/…}) is no longer the handle path —
 * the name-path is the authorable, condition-facing form (KERNEL-FINDINGS §10).
 */
class IncludesWritePathTest {

    private static final String STOREFRONT = Fixtures.json("models/storefront.dm.json");
    private static final String CATALOG = Fixtures.json("models/catalog.dm.json");
    private static final String INCLUDED_PRICE = "/Storefront/Inventory/ListPrice";

    /** Resolves the "catalog" reference to the catalog model (read-only — never written). */
    private static IDocumentModelReferenceResolver resolver() {
        IDocumentModelSerializer serializer = new DocumentModelServiceFactory().createDocumentModelSerializer();
        return reference -> {
            if ("catalog".equals(reference)) {
                try {
                    return serializer.deserialize(new StringReader(CATALOG));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            throw new IllegalStateException("unknown reference: " + reference);
        };
    }

    private static BoundModel bind() {
        return BoundModel.ofJson(STOREFRONT, resolver());
    }



    @Test
    void expandsSoAnIncludedFieldIsAddressable() {
        // the field comes from the included catalog model — resolvable only because expand mounted it
        assertNotNull(bind().num(INCLUDED_PRICE));
        assertEquals(2, bind().num(INCLUDED_PRICE).scale());
    }

    @Test
    void loadAndSaveIsTheIdentityOnTheBaseModel() {
        // base round-trips byte-for-byte; the include is NOT inlined into it
        assertEquals(STOREFRONT, bind().toJson());
        // proof the catalog stayed separate: the base never gained the included field definitions
        assertFalse(bind().toJson().contains("/Catalog/Sku"), "included content must not leak into the base");
        assertTrue(bind().toJson().contains("\"modelAlias\": \"catalog\""), "the include reference is preserved");
    }

    @Test
    void addingARuleToAModelWithIncludesKeepsTheModelsSeparate() {
        // The rule lives in the base group and references base fields; adding it must not disturb or
        // inline the included catalog — the two models stay separate after the write.
        BoundModel m = bind();
        Rule rule = m.rule(m.group("/Storefront"))
                .name("StoreNamedRule")
                .errorField(m.str("/Storefront/StoreName"))
                .when(m.str("/Storefront/StoreName").notFilled())
                .code("STORE_NAMED").severity(Severity.ERROR)
                .message(Dsl.en("The store must be named."))   // storefront declares only en_US
                .build();

        BoundModel m2 = m.add(rule);
        // add() reserializes via Jackson, so post-write JSON is reformatted (not byte-identical) —
        // assert structure whitespace-insensitively; pure load+save identity is covered above.
        String compact = m2.toJson().replaceAll("\\s", "");

        assertTrue(compact.contains("StoreNamedRule"), "the rule landed in the base model");
        assertFalse(compact.contains("/Catalog/Sku"), "the catalog is still separate, not inlined");
        assertTrue(compact.contains("\"modelAlias\":\"catalog\""), "include preserved after write");
        assertFalse(m.toJson().contains("StoreNamedRule"), "snapshot: original binding untouched");
        assertEquals(STOREFRONT, m.toJson(), "the source binding is byte-for-byte unchanged");
        // and the written base still re-binds (with the same resolver) — round-trips
        assertTrue(BoundModel.ofJson(m2.toJson(), resolver()).toJson().contains("StoreNamedRule"));
    }

    // NOTE (follow-up): the handle now exposes the included field at its name-path
    // ("/Storefront/Inventory/ListPrice" — F-IDX), which is the unsuffixed, authorable form. Whether
    // a *written* rule referencing an included field round-trips through the kernel is still the
    // fork-9 path-policy follow-up (KERNEL-FINDINGS §10), distinct from the separation guarantee above.
}
