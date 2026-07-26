package io.github.mbackschat.a12.dm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * RV03 — include mounting rewrites genuine condition reference spans, never slash-shaped text. These cases
 * cover the legal slots the former character-context scanner missed.
 */
class IncludeReferenceRemapTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> SINGLE =
            Map.of("/Benefits", "/Policy/Cover");

    @Test
    void matchesTheSharedJavaAndPortableLanguageProfiles() throws IOException {
        try (var source = getClass().getResourceAsStream("/reference-remap-language-profiles.json")) {
            var profiles = JSON.readTree(source);
            for (var profileEntry : List.of("en_US", "de_DE")) {
                var profile = profiles.path(profileEntry);
                var production = ConditionReferenceRemapper.languageProfile(profileEntry);
                assertEquals(
                        StreamSupport.stream(profile.path("referenceSeparators").spliterator(), false)
                                .map(JsonNode::asText)
                                .collect(Collectors.toSet()),
                        production.referenceSeparators());
                assertEquals(
                        StreamSupport.stream(profile.path("nonReferenceAtoms").spliterator(), false)
                                .map(JsonNode::asText)
                                .collect(Collectors.toSet()),
                        production.nonReferenceAtoms());
                assertEquals(profile.path("customCondition").asText(), production.customCondition());
                assertEquals(profile.path("atFrom").asText(), production.atFrom());
                for (var separator : profile.path("referenceSeparators")) {
                    var word = separator.asText();
                    assertEquals(
                            word + " /Policy/Cover/Lines/Code",
                            remap(word + " /Benefits/Lines/Code", profileEntry).text(),
                            profileEntry + ": " + word);
                }
                for (var atomNode : profile.path("nonReferenceAtoms")) {
                    var atom = atomNode.asText();
                    var context = new ConditionReferenceRemapper.ReferenceContext(
                            SINGLE,
                            "/Benefits/Validation",
                            java.util.Set.of(),
                            Map.of(atom, List.of("/Benefits/Rates/" + atom)),
                            true,
                            profileEntry);
                    assertEquals(
                            atom,
                            ConditionReferenceRemapper.remapReferences(atom, context).text(),
                            profileEntry + ": " + atom);
                }
                var separator = profile.path("referenceSeparators").get(0).asText();
                var custom = profile.path("customCondition").asText();
                assertEquals(
                        custom + " ProjectHook " + separator + " /Policy/Cover/Lines/Code",
                        remap(
                                custom + " ProjectHook " + separator + " /Benefits/Lines/Code",
                                profileEntry).text(),
                        profileEntry + ": " + custom);
                var atFrom = profile.path("atFrom").asText();
                assertEquals(
                        "RepetitionNotUnique(/Policy/Cover/Lines/Code @" + atFrom
                                + " /Policy/Cover/Lines)",
                        remap(
                                "RepetitionNotUnique(/Benefits/Lines/Code @" + atFrom
                                        + " /Benefits/Lines)",
                                profileEntry).text(),
                        profileEntry + ": @" + atFrom);
            }
        }
    }

    private static ModelReader.RemapResult remap(String text) {
        return ModelReader.remapAbsolutePaths(text, SINGLE);
    }

    private static ModelReader.RemapResult remap(String text, String conditionLanguageCode) {
        return ModelReader.remapAbsolutePaths(text, SINGLE, conditionLanguageCode);
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
    void remapsLocalizedGermanReferenceSeparators() {
        assertEquals(
                "WiederholungNichtEindeutig(/Policy/Cover/Lines/Code @Von /Policy/Cover/Lines)",
                remap("WiederholungNichtEindeutig(/Benefits/Lines/Code @Von /Benefits/Lines)", "de_DE").text());
        assertEquals(
                "[/Policy/Cover/Lines/Value Fuer /Policy/Cover/Lookup] "
                        + "UngleichMitToleranz1 [/Policy/Cover/Limit]",
                remap("[/Benefits/Lines/Value Fuer /Benefits/Lookup] "
                        + "UngleichMitToleranz1 [/Benefits/Limit]", "de_DE").text());
        assertEquals(
                "MindestensEinFeldAngegeben(/Policy/Cover/Lines*/Code Mit "
                        + "[/Policy/Cover/Lines/Code] == [$/Policy/Cover/Lines/Code])",
                remap("MindestensEinFeldAngegeben(/Benefits/Lines*/Code Mit "
                        + "[/Benefits/Lines/Code] == [$/Benefits/Lines/Code])", "de_DE").text());
    }

    @Test
    void foreignAtKeywordDoesNotOpenAReferenceSlot() {
        assertEquals(
                "WiederholungNichtEindeutig(/Policy/Cover/Lines/Code @From /Benefits/Lines)",
                remap(
                        "WiederholungNichtEindeutig(/Benefits/Lines/Code @From /Benefits/Lines)",
                        "de_DE").text());
        assertEquals(
                "RepetitionNotUnique(/Policy/Cover/Lines/Code @Von /Benefits/Lines)",
                remap(
                        "RepetitionNotUnique(/Benefits/Lines/Code @Von /Benefits/Lines)",
                        "en_US").text());
    }

    @Test
    void keepsTheOtherLanguagesKeywordsAvailableAsPathSegments() {
        ModelReader.RemapResult english = remap("FieldFilled(Fuer/Value)");
        ModelReader.RemapResult german = remap("FeldAngegeben(For/Value)", "de_DE");

        assertEquals("FieldFilled(Fuer/Value)", english.text());
        assertFalse(english.hadResidual());
        assertEquals("FeldAngegeben(For/Value)", german.text());
        assertFalse(german.hadResidual());
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
