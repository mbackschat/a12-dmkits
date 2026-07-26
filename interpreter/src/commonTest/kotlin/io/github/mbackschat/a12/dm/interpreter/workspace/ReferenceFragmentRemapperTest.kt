package io.github.mbackschat.a12.dm.interpreter.workspace

import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReferenceFragmentRemapperTest {

    @Test
    fun matchesTheSharedJavaAndPortableLanguageProfiles() {
        ReferenceRemapLanguageProfiles.all.forEach { profile ->
            val language = ConditionLanguage.fromCode(profile.code)
            val production = ReferenceFragmentRemapper.languageProfile(language)
            assertEquals(profile.referenceSeparators.toSet(), production.referenceSeparators)
            assertEquals(profile.nonReferenceAtoms.toSet(), production.nonReferenceAtoms)
            assertEquals(profile.customCondition, production.customCondition)
            assertEquals(profile.atFrom, production.atFrom)
            profile.referenceSeparators.forEach { separator ->
                assertRemapped(
                    "$separator /Benefits/Lines/Code",
                    "$separator /Policy/Cover/Lines/Code",
                    binding = binding(
                        shortNamesAllowed = false,
                        conditionLanguage = language,
                    ),
                )
            }
            profile.nonReferenceAtoms.forEach { atom ->
                val result = ReferenceFragmentRemapper.remap(
                    atom,
                    binding(
                        fields = setOf("/Benefits/Rates/$atom"),
                        conditionLanguage = language,
                    ),
                )
                assertEquals(atom, result.text, "${profile.code}: $atom")
            }
            val separator = profile.referenceSeparators.first()
            assertRemapped(
                "${profile.customCondition} ProjectHook $separator /Benefits/Lines/Code",
                "${profile.customCondition} ProjectHook $separator /Policy/Cover/Lines/Code",
                binding = binding(
                    shortNamesAllowed = false,
                    conditionLanguage = language,
                ),
            )
            assertRemapped(
                "RepetitionNotUnique(/Benefits/Lines/Code @${profile.atFrom} /Benefits/Lines)",
                "RepetitionNotUnique(/Policy/Cover/Lines/Code @${profile.atFrom} /Policy/Cover/Lines)",
                binding = binding(
                    shortNamesAllowed = false,
                    conditionLanguage = language,
                ),
            )
        }
    }

    @Test
    fun remapsEveryLegalAbsoluteReferenceSlot() {
        assertRemapped(
            "RepetitionNotUnique(/Benefits/Lines/Code @From /Benefits/Lines)",
            "RepetitionNotUnique(/Policy/Cover/Lines/Code @From /Policy/Cover/Lines)",
        )
        assertRemapped(
            "AtLeastOneFieldValueIncludedInValueList([Code] In /Benefits/Lines*/Code)",
            "AtLeastOneFieldValueIncludedInValueList([Code] In /Policy/Cover/Lines*/Code)",
        )
        assertRemapped(
            "[/Benefits/Lines/Value For /Benefits/Lookup] == \"X\"",
            "[/Policy/Cover/Lines/Value For /Policy/Cover/Lookup] == \"X\"",
        )
        assertRemapped(
            "AtLeastOneFieldFilled(/Benefits/Lines*/Code Having " +
                "[/Benefits/Lines/Code] == [$/Benefits/Lines/Code])",
            "AtLeastOneFieldFilled(/Policy/Cover/Lines*/Code Having " +
                "[/Policy/Cover/Lines/Code] == [$/Policy/Cover/Lines/Code])",
        )
        assertRemapped(
            "FieldFilled(/'Length'/Value)",
            "FieldFilled(/Policy/Cover/Value)",
            binding(prefix = MountedPrefix("/Length", "/Policy/Cover")),
        )
    }

    @Test
    fun remapsLocalizedGermanReferenceSeparators() {
        assertRemapped(
            "WiederholungNichtEindeutig(/Benefits/Lines/Code @Von /Benefits/Lines)",
            "WiederholungNichtEindeutig(/Policy/Cover/Lines/Code @Von /Policy/Cover/Lines)",
            binding = binding(
                shortNamesAllowed = false,
                conditionLanguage = ConditionLanguage.DE,
            ),
        )
        assertRemapped(
            "[/Benefits/Lines/Value Fuer /Benefits/Lookup] " +
                "UngleichMitToleranz1 [/Benefits/Limit]",
            "[/Policy/Cover/Lines/Value Fuer /Policy/Cover/Lookup] " +
                "UngleichMitToleranz1 [/Policy/Cover/Limit]",
            binding = binding(
                shortNamesAllowed = false,
                conditionLanguage = ConditionLanguage.DE,
            ),
        )
        assertRemapped(
            "MindestensEinFeldAngegeben(/Benefits/Lines*/Code Mit " +
                "[/Benefits/Lines/Code] == [$/Benefits/Lines/Code])",
            "MindestensEinFeldAngegeben(/Policy/Cover/Lines*/Code Mit " +
                "[/Policy/Cover/Lines/Code] == [$/Policy/Cover/Lines/Code])",
            binding = binding(
                shortNamesAllowed = false,
                conditionLanguage = ConditionLanguage.DE,
            ),
        )
    }

    @Test
    fun foreignAtKeywordDoesNotOpenAReferenceSlot() {
        assertEquals(
            "WiederholungNichtEindeutig(/Policy/Cover/Lines/Code @From /Benefits/Lines)",
            ReferenceFragmentRemapper.remap(
                "WiederholungNichtEindeutig(/Benefits/Lines/Code @From /Benefits/Lines)",
                binding(
                    shortNamesAllowed = false,
                    conditionLanguage = ConditionLanguage.DE,
                ),
            ).text,
        )
        assertEquals(
            "RepetitionNotUnique(/Policy/Cover/Lines/Code @Von /Benefits/Lines)",
            ReferenceFragmentRemapper.remap(
                "RepetitionNotUnique(/Benefits/Lines/Code @Von /Benefits/Lines)",
                binding(
                    shortNamesAllowed = false,
                    conditionLanguage = ConditionLanguage.EN,
                ),
            ).text,
        )
    }

    @Test
    fun keepsTheOtherLanguagesKeywordsAvailableAsPathSegments() {
        val english = ReferenceFragmentRemapper.remap(
            "FieldFilled(Fuer/Value)",
            binding(conditionLanguage = ConditionLanguage.EN),
        )
        val german = ReferenceFragmentRemapper.remap(
            "FeldAngegeben(For/Value)",
            binding(conditionLanguage = ConditionLanguage.DE),
        )

        assertEquals("FieldFilled(Fuer/Value)", english.text)
        assertTrue(english.residualReferences.isEmpty())
        assertEquals("FeldAngegeben(For/Value)", german.text)
        assertTrue(german.residualReferences.isEmpty())
    }

    @Test
    fun preservesNonReferenceBytesAndMoveInvariantRelativePaths() {
        val source = ";; /Benefits is prose\n" +
            "[Kind] == \"(/Benefits\" And [Total] / [Count] > 1 And " +
            "FieldFilled(../Rates/Cap) And FieldFilled(\$Sibling/Flag)"
        val result = ReferenceFragmentRemapper.remap(source, binding())

        assertEquals(source, result.text)
        assertTrue(result.residualReferences.isEmpty())
    }

    @Test
    fun bindsAUniqueCrossGroupShortNameBeforeTheIncludingModelCanRetargetIt() {
        val result = ReferenceFragmentRemapper.remap(
            "FieldFilled(Observed) And [Cap] > 0",
            binding(),
        )

        assertEquals(
            "FieldFilled(Observed) And [/Policy/Cover/Rates/Cap] > 0",
            result.text,
        )
        assertTrue(result.residualReferences.isEmpty())
    }

    @Test
    fun leavesFlagDisabledAndAmbiguousShortNamesForTheConsistencyBoundary() {
        val disabled = ReferenceFragmentRemapper.remap(
            "[Cap] > 0",
            binding(shortNamesAllowed = false),
        )
        val ambiguous = ReferenceFragmentRemapper.remap(
            "[Cap] > 0",
            binding(
                fields = setOf(
                    "/Benefits/Rates/Cap",
                    "/Benefits/Limits/Cap",
                    "/Benefits/Validation/Observed",
                ),
            ),
        )

        assertEquals("[Cap] > 0", disabled.text)
        assertEquals("[Cap] > 0", ambiguous.text)
        assertTrue(disabled.residualReferences.isEmpty())
        assertTrue(ambiguous.residualReferences.isEmpty())
    }

    @Test
    fun reportsOnlyGenuineUnmappedAbsoluteReferences() {
        val source = "FieldFilled(/Unknown/Value) And [Total] / [Count] > 1"
        val result = ReferenceFragmentRemapper.remap(source, binding())

        assertEquals(source, result.text)
        assertEquals(listOf("/Unknown/Value"), result.residualReferences)
        assertFalse(result.residualReferences.contains("/"))
    }

    @Test
    fun measuresEscapedUtf8OutputExactlyAndStopsBeforeMaterialization() {
        val source = ";; € /Benefits\nFieldFilled(/Benefits/Lines/Code) And [Kind] == \"😀\\\"\""
        val binding = binding(shortNamesAllowed = false)
        val remapped = ReferenceFragmentRemapper.remap(source, binding).text
        val expected = quotedJsonUtf8Size(remapped, stopAfter = Long.MAX_VALUE)

        assertEquals(
            expected,
            ReferenceFragmentRemapper.measureQuotedBytes(source, binding, stopAfter = Long.MAX_VALUE),
        )
        assertEquals(
            101,
            ReferenceFragmentRemapper.measureQuotedBytes(source.repeat(100), binding, stopAfter = 100),
        )
    }

    private fun assertRemapped(
        source: String,
        expected: String,
        binding: ReferenceBinding = binding(shortNamesAllowed = false),
    ) {
        val result = ReferenceFragmentRemapper.remap(source, binding)
        assertEquals(expected, result.text)
        assertTrue(result.residualReferences.isEmpty())
    }

    private fun binding(
        prefix: MountedPrefix = MountedPrefix("/Benefits", "/Policy/Cover"),
        fields: Set<String> = setOf(
            "/Benefits/Lines/Code",
            "/Benefits/Lines/Value",
            "/Benefits/Rates/Cap",
            "/Benefits/Validation/Observed",
        ),
        shortNamesAllowed: Boolean = true,
        conditionLanguage: ConditionLanguage = ConditionLanguage.EN,
    ): ReferenceBinding =
        ReferenceBinding(
            prefixMapping = MountedPrefixMapping(listOf(prefix)),
            declaringGroup = "/Benefits/Validation",
            sourceSymbols = SourceModelSymbols.fromPaths(
                groupPaths = setOf(
                    "/Benefits",
                    "/Benefits/Lines",
                    "/Benefits/Rates",
                    "/Benefits/Validation",
                ),
                fieldPaths = fields,
            ),
            shortNamesAllowed = shortNamesAllowed,
            conditionLanguage = conditionLanguage,
        )
}
