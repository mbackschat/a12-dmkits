package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.ValueKind;

/** {@code BoundModel.load(Path)} — the path-loading sugar over {@code ofJson(readString(path))}. */
class BoundModelLoadTest {

    @Test
    void loadFromAPathBindsTheModelAndRetainsTheSourceForWriteBack(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("subscription.dm.json");
        Files.writeString(file, Fixtures.subscriptionJson());

        BoundModel m = BoundModel.load(file);

        // bound: typed handles resolve with the right kind …
        assertEquals(ValueKind.NUMBER, m.fieldAt("/Subscription/Addons/MonthlyFee").orElseThrow().kind());
        assertNotNull(m.num("/Subscription/Addons/MonthlyFee"));
        // … and the source is retained, so the binding round-trips back to the same content.
        assertEquals(Fixtures.subscriptionJson(), m.toJson());
    }

    @Test
    void loadFromAMissingPathFailsWithAnUncheckedIoError(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist.dm.json");
        assertThrows(UncheckedIOException.class, () -> BoundModel.load(missing));
    }
}
