package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §10 — {@code fieldRefByShortNameAllowed} gates the MODEL-WIDE tier of bare-name resolution
 * and nothing else (KF121's bare-name bullet, extended 2026-09-04). A bare name the rule's own group declares
 * resolves whatever the flag says, even one that is AMBIGUOUS model-wide: a local candidate settles it and a
 * model-wide ambiguity does not veto that. Whether the index is consulted at all is not stated here — nothing
 * externally observable separates "not consulted" from "consulted, local wins", so the rows below claim the
 * outcome and not the order.
 *
 * <p><b>Why this exists.</b> Our {@code config modify --field-ref-by-short-name} help read "allow field
 * references by SHORT name where unique", which an author reads as the flag governing every bare name — so
 * turning it off looks like it would refuse the third row below. a12-kernel-lean's agent measured the split and
 * reported the wording (their instrument note, 2026-09-04); these rows re-measure it on our own carrier, which
 * is what lets the corrected help text cite a lock rather than a peer's message.
 *
 * <p><b>The carrier and its one moving variable.</b> One model — {@code Note} at the root, {@code Code} in
 * BOTH subgroups (ambiguous model-wide), {@code Sku} only in {@code Warehouse} — read twice, differing solely
 * in the flag, with each row differing from its partner in exactly one of: the flag, the rule's host group, or
 * which field carries the error. Two models would have differed in everything nobody thought about.
 *
 * <p><b>The confound this kills.</b> In the obvious version of the local-tier row the bare name IS the rule's
 * error field, so "the host group declares it" and "it names the error field" are indistinguishable. The
 * {@code errorFieldIsNotWhatResolvesIt} row moves the error to {@code Sku} and keeps {@code Code} as a second
 * leaf, so an admission can only come from the host group's own declarations.
 *
 * <p>The conditions are raw bare-name strings on purpose: the claim is about a path FORM the typed renderer
 * deliberately never emits (it always writes canonical absolute paths, {@code PathStyle}), so a bare short
 * name cannot be authored through the builder. The sibling rows in {@code PathLawsTest} cover the model-wide
 * tier's own two arms and the absence of an ancestor walk.
 */
class ShortNameTierLawsTest {

    private static final String ADMITTED = KernelLaws.ADMITTED;
    private static final String INVALID_ENTITY = "MVK_INVALID_ENTITY";
    private static final String NOT_UNIQUE = "MVK_FIELDNAME_NOT_UNIQUE";

    private static final String FLAG_ON = DocumentModelDraft.named("catalog-tiers").locale("en_US")
            .rootGroup("Catalog", c -> c
                    .field("Note", FieldKind.STRING)
                    .group("Warehouse", w -> w
                            .field("Code", FieldKind.STRING)
                            .field("Sku", FieldKind.STRING))
                    .group("Store", s -> s.field("Code", FieldKind.STRING)))
            .build().toJson();
    private static final String FLAG_OFF =
            ModelDocument.read(FLAG_ON).setFieldRefByShortNameAllowed(false).toJson();

    @Test
    void theFlagGatesTheModelWideTierOnly() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("host declares it, flag OFF, ambiguous model-wide",
                verdict(FLAG_OFF, "/Catalog/Warehouse", "../Code", "FieldFilled(Code)"));
        actual.put("host declares it, flag ON, ambiguous model-wide",
                verdict(FLAG_ON, "/Catalog/Warehouse", "../Code", "FieldFilled(Code)"));
        actual.put("host does NOT declare it, flag OFF",
                verdict(FLAG_OFF, "/Catalog", "../Note", "FieldFilled(Note) And FieldFilled(Code)"));
        actual.put("host does NOT declare it, flag ON",
                verdict(FLAG_ON, "/Catalog", "../Note", "FieldFilled(Note) And FieldFilled(Code)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("host declares it, flag OFF, ambiguous model-wide", ADMITTED);
        expected.put("host declares it, flag ON, ambiguous model-wide", ADMITTED);
        // The pair that places each demand in its tier: with no local candidate the flag decides whether a
        // model-wide lookup happens at all, and only that lookup carries the uniqueness requirement.
        expected.put("host does NOT declare it, flag OFF", INVALID_ENTITY);
        expected.put("host does NOT declare it, flag ON", NOT_UNIQUE);
        assertEquals(expected, actual, "the flag governs the model-wide lookup, not the local one");
    }

    @Test
    void errorFieldIsNotWhatResolvesIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("ambiguous leaf that is NOT the error field, flag OFF",
                verdict(FLAG_OFF, "/Catalog/Warehouse", "../Sku", "FieldFilled(Sku) And FieldFilled(Code)"));
        actual.put("a name NO group in scope declares — control",
                verdict(FLAG_OFF, "/Catalog/Warehouse", "../Sku", "FieldFilled(Sku) And FieldFilled(Nope)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ambiguous leaf that is NOT the error field, flag OFF", ADMITTED);
        expected.put("a name NO group in scope declares — control", INVALID_ENTITY);
        assertEquals(expected, actual, "the local tier reads the HOST group's declarations, not the error field");
    }

    /** The kernel's verdict — {@link KernelLaws#ADMITTED} or the comma-joined codes it reported. */
    private static String verdict(String json, String group, String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(json, group, errorField, condition);
        return r.ok() ? ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).collect(Collectors.joining(","));
    }
}
