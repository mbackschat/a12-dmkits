package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * The starred entry points take a MODEL-ABSOLUTE path, and a relative one is refused with a corrective
     * rather than mangled. Both read the model root-first and drop the leading {@code /} before splitting, so
     * a relative argument used to lose its FIRST CHARACTER and report "no element at path: /laims" — a message
     * that reads as a broken model rather than a mis-spelled argument, which is what cost the time.
     *
     * <p>The must-not-fire half matters as much: relative navigation is a real idiom, it just lives on the
     * editor/info receiver ({@code GroupInfo.flatten}), so this refusal must not reach that one.
     */
    @Test
    void aRelativeStarPathIsRefusedWithACorrectiveRatherThanMangled() {
        BoundModel m = Fixtures.order();

        assertRefusesRelative("flatten", () -> m.flatten("Items*/Count"));
        assertRefusesRelative("flattenGroup", () -> m.flattenGroup("Items*"));

        // …and the absolute spellings still resolve.
        assertNotNull(m.flatten("/Order/Items*/Count"));
        assertNotNull(m.flattenGroup("/Order/Items*"));
    }

    private static void assertRefusesRelative(String method, org.junit.jupiter.api.function.Executable call) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, call);
        assertTrue(refused.getMessage().contains("MODEL-ABSOLUTE"),
                () -> method + " must name the mistake, got: " + refused.getMessage());
        assertTrue(refused.getMessage().contains(method),
                () -> method + " must name itself, got: " + refused.getMessage());
    }
}
