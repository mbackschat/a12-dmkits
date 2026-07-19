package io.github.mbackschat.a12.dm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * RV03 — include mounting rewrites genuine condition reference spans, never slash-shaped text. These cases
 * cover the legal slots the former character-context scanner missed.
 */
class IncludeReferenceRemapTest {

    private static final Map<String, String> SINGLE =
            Map.of("/Benefits", "/Policy/Cover");

    private static ModelReader.RemapResult remap(String text) {
        return ModelReader.remapAbsolutePaths(text, SINGLE);
    }

    @Test
    void remapsEveryLegalAbsoluteReferenceSlot() {
        assertEquals(
                "RepetitionNotUnique(/Policy/Cover/Lines/Code @From /Policy/Cover/Lines)",
                remap("RepetitionNotUnique(/Benefits/Lines/Code @From /Benefits/Lines)").text());
        assertEquals(
                "AtLeastOneFieldValueIncludedInValueList([Code] In /Policy/Cover/Lines*/Code)",
                remap("AtLeastOneFieldValueIncludedInValueList([Code] In /Benefits/Lines*/Code)").text());
        assertEquals(
                "[/Policy/Cover/Lines/Value For /Policy/Cover/Lookup] == \"X\"",
                remap("[/Benefits/Lines/Value For /Benefits/Lookup] == \"X\"").text());
        assertEquals(
                "AtLeastOneFieldFilled(/Policy/Cover/Lines*/Code Having "
                        + "[/Policy/Cover/Lines/Code] == [$/Policy/Cover/Lines/Code])",
                remap("AtLeastOneFieldFilled(/Benefits/Lines*/Code Having "
                        + "[/Benefits/Lines/Code] == [$/Benefits/Lines/Code])").text());
    }

    @Test
    void remapsAKeywordQuotedPathSegment() {
        assertEquals(
                "FieldFilled(/Policy/Cover/Value)",
                ModelReader.remapAbsolutePaths(
                        "FieldFilled(/'Length'/Value)", Map.of("/Length", "/Policy/Cover")).text());
    }

    @Test
    void preservesEveryNonReferenceByteAndNeverFlagsPathLikeLiterals() {
        String text = "[Kind] == \"(/Benefits\" And [Total] / [Count] > 1 "
                + "And FieldFilled(../Coverage) And FieldFilled($Sibling/Flag)";
        ModelReader.RemapResult result = remap(text);
        assertEquals(text, result.text());
        assertFalse(result.hadResidual());

        ModelReader.RemapResult unknownLiteral = remap("[Kind] == \"(/Unknown/Value\"");
        assertEquals("[Kind] == \"(/Unknown/Value\"", unknownLiteral.text());
        assertFalse(unknownLiteral.hadResidual());
    }

    @Test
    void flagsOnlyAGenuineUnknownAbsoluteReference() {
        ModelReader.RemapResult result = remap("FieldFilled(/Unknown/Value)");
        assertEquals("FieldFilled(/Unknown/Value)", result.text());
        assertTrue(result.hadResidual());
    }
}
