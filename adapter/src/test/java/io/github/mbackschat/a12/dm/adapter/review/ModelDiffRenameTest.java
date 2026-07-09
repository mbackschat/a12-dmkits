package io.github.mbackschat.a12.dm.adapter.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ChangeKind;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementChange;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * MODEL-REVIEW MR3-v1 — the conservative, exact-fingerprint rename/move matcher (the baseline the
 * SPEC §5-intro promises). A REMOVED@P + ADDED@P′ pair with an <b>identical content fingerprint</b>,
 * <b>unambiguous</b> (exactly one↔one of that kind+fingerprint), and a <b>locality</b> match
 * (same parent XOR same leaf name — what {@code field rename}/{@code field move} actually do) is
 * re-labelled {@code RENAMED}/{@code MOVED}. Both directions: it pairs a real rename/move, and it
 * NEVER fabricates a pair (a low-entropy fingerprint, an ambiguous set, or a both-differ pair stays
 * remove+add — the safe direction).
 */
class ModelDiffRenameTest {

    private static final String ADDONS = "/Subscription/Addons";
    private static final String BILLING = "/Subscription/Billing";

    private static String base() {
        return readResource("models/subscription.dm.json");
    }

    /** {@code base} + one field of {@code kind} at {@code parent/name} (default config — so fingerprints match). */
    private static String withField(String parent, String name, FieldKind kind) {
        return EditSession.read(base()).addField(parent, name, kind).validate().toJson();
    }

    @Test
    void renameInPlaceIsPairedAsRenamed() {
        // same group, same config, different leaf name → a rename (what `field rename` does)
        String base = withField(ADDONS, "Promo", FieldKind.NUMBER);
        String head = withField(ADDONS, "PromoCode", FieldKind.NUMBER);

        List<ElementChange> renames = ModelDiff.between(base, head).changes().stream()
                .filter(c -> c.change() == ChangeKind.RENAMED).toList();
        assertEquals(1, renames.size(), () -> "one RENAMED, got: " + ModelDiff.between(base, head).changes());
        ElementChange r = renames.get(0);
        assertEquals(ADDONS + "/Promo", r.id());
        assertEquals(ModelDiff.Risk.LOW, r.risk(), () -> "a content-preserving rename is LOW: " + r);
        assertTrue(r.reasons().contains("ELEMENT_RENAMED"), () -> "reasons: " + r.reasons());
        ModelDiff.Delta path = pathDelta(r);
        assertEquals(ADDONS + "/Promo", path.before());
        assertEquals(ADDONS + "/PromoCode", path.after());
        // the rename replaces the remove+add — neither survives
        assertTrue(noRawAddOrRemoveFor(base, head, ADDONS + "/Promo"), "the old path must not remain REMOVED");
        assertTrue(noRawAddOrRemoveFor(base, head, ADDONS + "/PromoCode"), "the new path must not remain ADDED");
    }

    @Test
    void moveToAnotherGroupIsPairedAsMoved() {
        // same leaf name, different parent → a move (what `field move` does)
        String base = withField(ADDONS, "Promo", FieldKind.NUMBER);
        String head = withField(BILLING, "Promo", FieldKind.NUMBER);

        List<ElementChange> moves = ModelDiff.between(base, head).changes().stream()
                .filter(c -> c.change() == ChangeKind.MOVED).toList();
        assertEquals(1, moves.size(), () -> "one MOVED, got: " + ModelDiff.between(base, head).changes());
        ElementChange m = moves.get(0);
        assertEquals(ADDONS + "/Promo", m.id());
        assertEquals(ModelDiff.Risk.MEDIUM, m.risk(), () -> "a re-homing move is MEDIUM: " + m);
        assertTrue(m.reasons().contains("ELEMENT_MOVED"), () -> "reasons: " + m.reasons());
        assertEquals(BILLING + "/Promo", pathDelta(m).after());
    }

    @Test
    void bothNameAndParentDifferDoesNotPair() {
        // identical config but BOTH parent and name differ → too weak for v1 (low-entropy false-pair guard)
        String base = withField(ADDONS, "Promo", FieldKind.NUMBER);
        String head = withField(BILLING, "Discount", FieldKind.NUMBER);

        List<ElementChange> changes = ModelDiff.between(base, head).changes();
        assertEquals(0, relocations(changes), () -> "unrelated fields must NOT be paired: " + changes);
        assertTrue(changes.stream().anyMatch(c -> c.change() == ChangeKind.REMOVED && c.id().equals(ADDONS + "/Promo")),
                "the old field stays REMOVED");
        assertTrue(changes.stream().anyMatch(c -> c.change() == ChangeKind.ADDED && c.id().equals(BILLING + "/Discount")),
                "the new field stays ADDED");
    }

    @Test
    void ambiguousFingerprintDoesNotPair() {
        // two removed + one added share the same fingerprint → not a clean 1↔1 → do not pair
        String base = EditSession.read(base())
                .addField(ADDONS, "PromoA", FieldKind.NUMBER)
                .addField(ADDONS, "PromoB", FieldKind.NUMBER).validate().toJson();
        String head = withField(ADDONS, "PromoC", FieldKind.NUMBER);

        assertEquals(0, relocations(ModelDiff.between(base, head).changes()),
                () -> "an ambiguous fingerprint set must not pair: " + ModelDiff.between(base, head).changes());
    }

    @Test
    void aContentEditBreaksV1PairingLeavingItToSimilarity() {
        // same parent, different name, but the KIND changed → fingerprint differs → v1 does not pair
        // (this is the rename+edit case the similarity upgrade handles, not v1)
        String base = withField(ADDONS, "Promo", FieldKind.NUMBER);
        String head = withField(ADDONS, "PromoCode", FieldKind.STRING);

        assertEquals(0, relocations(ModelDiff.between(base, head).changes()),
                () -> "a non-identical fingerprint must not pair in v1: " + ModelDiff.between(base, head).changes());
    }

    private static long relocations(List<ElementChange> changes) {
        return changes.stream()
                .filter(c -> c.change() == ChangeKind.RENAMED || c.change() == ChangeKind.MOVED).count();
    }

    private static boolean noRawAddOrRemoveFor(String base, String head, String id) {
        return ModelDiff.between(base, head).changes().stream().noneMatch(
                c -> c.id().equals(id) && (c.change() == ChangeKind.ADDED || c.change() == ChangeKind.REMOVED));
    }

    private static ModelDiff.Delta pathDelta(ElementChange change) {
        return change.deltas().stream().filter(d -> d.aspect().equals("path")).findFirst()
                .orElseThrow(() -> new AssertionError("no 'path' delta in " + change.deltas()));
    }

    private static String readResource(String path) {
        try (InputStream in = ModelDiffRenameTest.class.getClassLoader().getResourceAsStream(path)) {
            assertTrue(in != null, "missing test resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
