package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import org.junit.jupiter.api.Test;

/**
 * MODEL-REVIEW Phase-0 guard (MODEL-REVIEW-SPEC §2): the canonical writer — what {@code model normalize}
 * emits, and the form every mutating write path shares — must be <b>deterministic</b>, <b>order-preserving</b>,
 * and a <b>stable fixed point</b>. This is the "no change → no diff" invariant the review/diff trust story
 * rests on; locking it means a future serialization change (a POJO-serializing write path, a Jackson bump
 * that reorders map keys, a misguided "sort the elements") <b>fails the build</b> instead of silently
 * reintroducing churn.
 */
class RoundTripIdempotentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final List<String> FIXTURES = List.of(
            "models/subscription.dm.json", "models/order.dm.json", "models/order-en.dm.json",
            "models/project.dm.json", "models/catalog.dm.json", "models/storefront.dm.json");

    @Test
    void normalizeIsAStableFixedPoint() {
        for (String f : FIXTURES) {
            String once = ModelDocument.read(Fixtures.json(f)).toJson();
            assertEquals(once, ModelDocument.read(once).toJson(),
                    () -> f + ": a normalized model must round-trip byte-identical (no change → no diff)");
        }
    }

    @Test
    void canonicalFormIsIndependentOfInputFormatting() throws Exception {
        for (String f : FIXTURES) {
            String raw = Fixtures.json(f);
            String once = ModelDocument.read(raw).toJson();
            String compact = MAPPER.writeValueAsString(MAPPER.readTree(raw));
            assertEquals(once, ModelDocument.read(compact).toJson(),
                    () -> f + ": the canonical form must not depend on input formatting (determinism)");
        }
    }

    @Test
    void elementOrderIsPreservedNeverSorted() {
        for (String f : FIXTURES) {
            String raw = Fixtures.json(f);
            assertEquals(ids(raw), ids(ModelDocument.read(raw).toJson()),
                    () -> f + ": element/id order must be PRESERVED on write (declaration order is the form's "
                            + "display order — it is semantic, never sort it)");
        }
    }

    @Test
    void editSessionWritePathIsAlsoStable() {
        // closes the "not every write path probed" minor gap: EditSession shares the canonical writer, so a
        // no-edit round-trip on a canonical model must not reorder or destabilize it.
        String once = ModelDocument.read(Fixtures.orderJson()).toJson();
        assertEquals(once, ModelDocument.read(EditSession.read(once).toJson()).toJson(),
                "the EditSession write path must not reorder or destabilize a canonical model");
    }

    private static List<String> ids(String json) {
        List<String> out = new ArrayList<>();
        Matcher m = ID.matcher(json);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
