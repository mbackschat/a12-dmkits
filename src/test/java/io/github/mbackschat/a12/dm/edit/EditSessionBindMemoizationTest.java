package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * M6 — {@link EditSession#boundModel()} memoizes the kernel bind per unchanged JSON (a full
 * {@code BoundModel.ofJson} was measured at ~28 ms per navigation on a 200-field model, so a
 * many-navigation session paid seconds of redundant re-binding). This locks the behavior parity the
 * optimization must preserve: a read after a WRITE sees the write (the cache is invalidated), and the
 * snapshot semantics are unchanged.
 */
class EditSessionBindMemoizationTest {

    private static String model() {
        return DocumentModelDraft.named("shop").locale("en_US")
                .rootGroup("Shop", s -> s
                        .numberField("Price", n -> { })
                        .stringField("Name", c -> { }))
                .build().toJson();
    }

    @Test
    void aReadAfterAWriteSeesTheWrite() {
        EditSession s = EditSession.read(model());
        assertEquals("/Shop/Price", s.field("/Shop/Price").ref().path().value(), "primes the memoized bind");

        s.addField("/Shop", "Added", FieldKind.NUMBER);   // a write reassigns json → invalidates the cache

        // resolves ONLY if the cached bind was invalidated on the write (else the stale bind lacks Added)
        assertNotNull(s.field("/Shop/Added"), "the post-write navigation sees the added field");
        assertEquals("/Shop/Added", s.field("/Shop/Added").ref().path().value());
    }

    @Test
    void repeatedReadsBetweenWritesAreConsistent() {
        EditSession s = EditSession.read(model());
        // many navigations with no intervening write — every one sees the same snapshot
        for (int i = 0; i < 10; i++) {
            assertEquals("/Shop/Price", s.field("/Shop/Price").ref().path().value());
            assertEquals("/Shop", s.group("/Shop").ref().path().value());
        }
    }
}
