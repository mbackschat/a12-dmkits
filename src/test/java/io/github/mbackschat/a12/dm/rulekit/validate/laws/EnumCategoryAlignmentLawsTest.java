package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS — the measured basis for {@code modelkit}'s enum-category refusal: an Enumeration category's
 * {@code values} array pairs <b>positionally</b> with the enum's, so <b>equal cardinality is the kernel's own
 * rule</b> and not a proxy for set membership. The kernel reports the two halves separately and per element:
 * {@code MVK_MISSING_CATEGORY_VALUE} for an internal value with no category token,
 * {@code MVK_MISSING_INTERNAL_VALUE} for a token with no internal value.
 *
 * <p><b>Why this class exists at all.</b> {@code FieldTypeSpec.EnumSpec} refuses a misaligned category in its
 * constructor, so the shape is unauthorable through the kits and its kernel basis would otherwise be a claim
 * nobody could check — exactly the case the standing "a client-side refusal needs a measured kernel basis"
 * rule is about. Each row is therefore built by editing the ALIGNED model's own JSON, which is the only way to
 * put the refused shape in front of the kernel.
 *
 * <p><b>The discriminating row is the disjoint one.</b> Every misaligned row is explained equally well by
 * "the kernel matches category tokens to enum values by NAME"; only same-length disjoint tokens separate the
 * accounts, and they are admitted — so the pairing is by position and the cardinality check is exact rather
 * than conservative.
 *
 * <p>Own domain (a retail store's service tier).
 */
class EnumCategoryAlignmentLawsTest {

    private static final String ADMITTED = KernelLaws.ADMITTED;
    private static final String MISSING_CATEGORY_VALUE = "MVK_MISSING_CATEGORY_VALUE";

    /** Two enum values under one category that classifies both — the aligned shape the kits will build. */
    private static final String ALIGNED = DocumentModelDraft.named("store").locale("en_US")
            .rootGroup("Store", s -> s
                    .field("Reference", FieldKind.STRING)
                    .enumField("Tier", e -> e.value("BASIC").value("PLUS").category("Band", "STANDARD", "HIGH")))
            .build().toJson();

    /** The aligned model with the category's token list replaced — the only route to an unauthorable shape. */
    private static String withCategoryTokens(String... tokens) {
        String aligned = "\"values\" : [ \"STANDARD\", \"HIGH\" ]";
        if (!ALIGNED.contains(aligned)) {
            throw new IllegalStateException("the category token array moved; this surgery is anchored on it: "
                    + ALIGNED);
        }
        String replacement = "\"values\" : [ "
                + java.util.Arrays.stream(tokens).map(t -> '"' + t + '"').collect(Collectors.joining(", "))
                + " ]";
        return ALIGNED.replace(aligned, tokens.length == 0 ? "\"values\" : [ ]" : replacement);
    }

    /**
     * The verdict WITH its multiplicity, because the kernel reports this law per element and the doc says so.
     * An earlier version applied {@code .distinct()} here, which made "once per unclassified value" — a claim
     * KERNEL-FINDINGS states — unobservable to every assertion in the class.
     */
    private static String verdict(String modelJson) {
        ValidationResult r = KernelLaws.checkModel(modelJson, "/Store", "../Reference",
                "FieldFilled(/Store/Reference)");
        return r.ok() ? ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    @Test
    void aCategoryPairsWithTheEnumBYPOSITIONSoEqualCardinalityIsTheWholeRule() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("aligned — control", verdict(ALIGNED));
        actual.put("same length, DISJOINT tokens", verdict(withCategoryTokens("X", "Y")));
        actual.put("same length, reordered tokens", verdict(withCategoryTokens("HIGH", "STANDARD")));
        actual.put("one token short", verdict(withCategoryTokens("STANDARD")));
        actual.put("one token too many", verdict(withCategoryTokens("STANDARD", "HIGH", "TOP")));
        actual.put("no tokens at all", verdict(withCategoryTokens()));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("aligned — control", ADMITTED);
        expected.put("same length, DISJOINT tokens", ADMITTED);
        expected.put("same length, reordered tokens", ADMITTED);
        expected.put("one token short", MISSING_CATEGORY_VALUE);
        expected.put("one token too many", "MVK_MISSING_INTERNAL_VALUE");
        expected.put("no tokens at all", MISSING_CATEGORY_VALUE + "," + MISSING_CATEGORY_VALUE);
        assertEquals(expected, actual,
                "the token list is positional, so the kits' cardinality check is exactly the kernel's rule");
    }

    /**
     * The sharper positional discriminator, and the one that shows the pairing DIRECTLY rather than by
     * refuting a rival. Over three enum values, a single token leaves the kernel naming the <b>second and
     * third</b> as unclassified — so the lone token was consumed by the FIRST value, by position. A
     * name-matching account cannot produce that asymmetry, and neither can cardinality alone.
     */
    @Test
    void theUNCLASSIFIEDVALUESNAMEDShowThePairingRunsLeftToRight() {
        String threeValues = DocumentModelDraft.named("store").locale("en_US")
                .rootGroup("Store", s -> s
                        .field("Reference", FieldKind.STRING)
                        .enumField("Tier", e -> e.value("BASIC").value("PLUS").value("MAX")
                                .category("Band", "STANDARD", "HIGH", "TOP")))
                .build().toJson();
        String oneToken = threeValues.replace("\"values\" : [ \"STANDARD\", \"HIGH\", \"TOP\" ]",
                "\"values\" : [ \"STANDARD\" ]");
        assertNotEquals(threeValues, oneToken, "the category token array moved; this surgery is anchored on it");

        ValidationResult r = KernelLaws.checkModel(oneToken, "/Store", "../Reference",
                "FieldFilled(/Store/Reference)");
        String named = r.diagnostics().stream().map(d -> d.summary()).collect(Collectors.joining(" | "));
        assertTrue(named.contains("'PLUS'") && named.contains("'MAX'"),
                () -> "values 2 and 3 are the unclassified ones: " + named);
        assertFalse(named.contains("'BASIC'"),
                () -> "value 1 took the only token, which is what POSITIONAL means: " + named);
    }
}
