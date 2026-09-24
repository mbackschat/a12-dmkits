package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * That {@link CheckerReportedCodes} sees what the three scans it replaces could not.
 *
 * <p>Every row below is a shape that was measured WRONG on 2026-09-15, on fabricated Kotlin rather than on
 * the live checker, so the control keeps discriminating after the estate moves. The live-estate floor is a
 * separate test because a reader that returns nothing would satisfy every exclusion row here.
 */
class CheckerReportedCodesTest {

    /**
     * The vocabulary overload keeps a screaming-case literal that is not a code out of the denominator.
     *
     * <p>Without the membership test the same scan would take {@code "StringType"}'s neighbours and every
     * enum arm a family spells, so the unprefixed half would over-count into a guard that demands a
     * measurement for each — the one direction over-counting is NOT harmless.
     */
    @Test
    void anUnprefixedCodeIsSeenOnlyWhenTheVocabularyKnowsIt() {
        String source = """
                private fun finding() = Finding(code = "NO_BASE_YEAR", explain = "DAY_OPTIONAL")
                """;
        assertEquals(Set.of("NO_BASE_YEAR"),
                CheckerReportedCodes.codesIn(source, Set.of("NO_BASE_YEAR", "INVALID_FIELD")::contains));
        assertEquals(Set.of(), CheckerReportedCodes.codesIn(source));
    }

    /**
     * A code with digits in the MIDDLE — the defect that hid twelve ledger codes from all three guards.
     *
     * <p>{@code MVK_[A-Z_]+[0-9]*} matches {@code MVK_NAME_STARTS_WITH_XML} and then needs the closing
     * quote, so the {@code _A12_DV} carrier of the very same law read as absent.
     */
    @Test
    void aCodeWithInteriorDigitsIsSeen() {
        assertEquals(
                Set.of("MVK_NAME_STARTS_WITH_XML", "MVK_NAME_STARTS_WITH_XML_A12_DV"),
                CheckerReportedCodes.codesIn("""
                        private fun groupFinding() = nameFinding("MVK_NAME_STARTS_WITH_XML", "a group")
                        private fun fieldFinding() = nameFinding("MVK_NAME_STARTS_WITH_XML_A12_DV", "a field")
                        """),
                "a code spells digits wherever its law does, and the suffixed carriers spell them inside");
    }

    /**
     * A code carried through an intermediate defect record, which is how 86 of the 193 reach a
     * {@code Finding}.
     *
     * <p>This is why the reader does not narrow to a {@code Finding} construction: no amount of following
     * helper NAMES reaches a code that travels as a record's field.
     */
    @Test
    void aCodeStatedThroughADefectRecordIsSeen() {
        assertEquals(
                Set.of("MVK_NO_DATE_CONSTANT"),
                CheckerReportedCodes.codesIn("""
                        private fun tailDefect(): ListDefect? =
                            ListDefect("MVK_NO_DATE_CONSTANT", "the constant is date-shaped")
                        """),
                "the code travels as a record field and still is the verdict the checker states");
    }

    /**
     * A code the checker holds only to COMPARE against is not a code it reports.
     *
     * <p>The phase and suppression lists in {@code Check.kt} are the live case, and taking them as reports is
     * what made the coarse scan demand a checker-reported row for four codes no family emits.
     */
    @Test
    void aCodeHeldOnlyInAMembershipSetIsNotReported() {
        assertEquals(
                Set.of("MVK_NO_WILDCARD"),
                CheckerReportedCodes.codesIn("""
                        private val POST_PARSE_CONDITION_CODES = setOf(
                            "MVK_PARALLEL_ITERATION_INVALID", "MVK_NEG_CONDITION_IN_ITERATION",
                        )
                        private fun finding() = Finding("MVK_NO_WILDCARD", "the read crosses a repetition")
                        """),
                "a set member is compared against; only the literal stated outside one is an answer");
    }

    /**
     * A code KEYED in an explain table is reported, which is the opposite shape from a membership set.
     *
     * <p>Three families hold their explain fragments this way and look the key up while building the
     * finding. Excluding maps alongside sets hid four such codes.
     */
    @Test
    void aCodeKeyingAnExplainTableIsReported() {
        assertEquals(
                Set.of("MVK_NO_INDEX_FIELD", "MVK_INDEX_VALUE_INVALID"),
                CheckerReportedCodes.codesIn("""
                        private val ADVICE = mapOf(
                            "MVK_NO_INDEX_FIELD" to "reads a group that declares no index field",
                            "MVK_INDEX_VALUE_INVALID" to "carries an empty key",
                        )
                        private fun finding(code: String) = Finding(code, ADVICE.getValue(code))
                        """),
                "a map keyed by code is the family's own report table, not a list it compares against");
    }

    /**
     * A constructor parameter typed {@code Set<String>} must not swallow the declarations after it.
     *
     * <p>With the type annotation allowed to cross a line, the scan ran from {@code val admitted: Set<String>}
     * to the next file-level {@code = setOf(} and cut out everything between — here, the enum arms that carry
     * the codes. Three reported codes disappeared that way.
     */
    @Test
    void aTypedConstructorParameterDoesNotSwallowTheArmsBelowIt() {
        assertEquals(
                Set.of("MVK_NO_TYPEYESNO", "MVK_NO_NUMBER"),
                CheckerReportedCodes.codesIn("""
                        private enum class ConstantArm(val code: String, val admitted: Set<String>) {
                            YES_NO("MVK_NO_TYPEYESNO", setOf("BooleanType")),
                            NUMBER("MVK_NO_NUMBER", setOf("NumberType")),
                        }
                        private val UNRESOLVED_TYPES = setOf("TypeDefType", "UnspecifiedType")
                        """),
                "the exclusion must anchor to one declaration, never scan on to the next file-level set");
    }

    /** …and a code named in PROSE is usually the opposite claim — the gate that answers ahead of this one. */
    @Test
    void aCodeNamedOnlyInACommentIsNotReported() {
        assertEquals(
                Set.of("MVK_INVALID_DATE_TYPE_FOR_OP"),
                CheckerReportedCodes.codesIn("""
                        /** Silent where the walk draws `MVK_INVALID_COMPARE_ENUM_TO_STRING` first. */
                        private fun finding() = // and never "MVK_NO_DATE"
                            Finding("MVK_INVALID_DATE_TYPE_FOR_OP", "the field cannot carry a date")
                        """),
                "prose naming a neighbouring class states that the checker leaves it alone");
    }

    /** The reader reaches the live checker, so every exclusion above means clean rather than empty. */
    @Test
    void theReaderReachesTheChecker() {
        Set<String> reported = CheckerReportedCodes.all();
        assertTrue(reported.size() >= 150, "the reader found only " + reported.size() + " reported codes");
        assertTrue(reported.contains("MVK_NAME_STARTS_WITH_XML_A12_DV"),
                "an interior-digit code the checker demonstrably reports is missing from the live read");
        assertTrue(CheckerReportedCodes.bySource().get("MVK_NAME_STARTS_WITH_XML_A12_DV").stream()
                        .anyMatch(source -> source.endsWith("ElementNameAlphabet.kt")),
                "the reader must name the source that states a code, for a stale row to be fixable");
    }
}
