package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.StringReader;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.core.ModelLoader;

/**
 * Include constraints are the <b>kernel's</b> to enforce on expand (FEATURE-GAPS — closes the ◐). This
 * locks one of them empirically: the base model's declared locales must be a <b>superset</b> of an included
 * model's, so including a model that declares a locale the base lacks is rejected on load. (Cycles and the
 * single-root rule are likewise the kernel's job on expand; modelkit doesn't re-implement them.)
 */
class IncludeConstraintsTest {

    /** An included sub-form declaring an extra locale (de_DE) the base below does not. */
    private static String bilingualAddress() {
        return DocumentModelDraft.named("address").locale("en_US").locale("de_DE")
                .rootGroup("Address", a -> a.field("Street", FieldKind.STRING))
                .build().toJson();
    }

    /** An included sub-form whose locales are a subset of the base (en_US only) — compatible. */
    private static String monolingualAddress() {
        return DocumentModelDraft.named("address").locale("en_US")
                .rootGroup("Address", a -> a.field("Street", FieldKind.STRING))
                .build().toJson();
    }

    private static String orderIncluding(String... locales) {
        DocumentModelDraft.Builder b = DocumentModelDraft.named("order");
        for (String loc : locales) {
            b.locale(loc);
        }
        return b.include("address", "address")
                .rootGroup("Order", o -> o.field("OrderRef", FieldKind.STRING).mount("ShipTo", "address"))
                .build().toJson();
    }

    private static ModelResolver resolver(String addressJson) {
        return reference -> {
            if ("address".equals(reference)) {
                return addressJson;
            }
            throw new IllegalStateException("unknown reference: " + reference);
        };
    }

    @Test
    void theKernelRejectsAnIncludeMissingABaseLocale() {
        // base declares en_US + de_DE; the included model declares only en_US → it can't cover de_DE → rejected
        assertThrows(Exception.class,
                () -> ModelLoader.loadAndCheck(orderIncluding("en_US", "de_DE"), resolver(monolingualAddress())),
                "an include must declare a superset of the base's locales (it must cover every base locale)");
    }

    @Test
    void anIncludeCoveringTheBaseLocalesLoadsCleanly() {
        // base en_US only; the included model declares en_US (+de_DE extra) → it covers en_US → fine
        assertDoesNotThrow(
                () -> ModelLoader.loadAndCheck(orderIncluding("en_US"), resolver(bilingualAddress())),
                "an include that covers the base's locales (extra locales are harmless) is fine");
    }
}
