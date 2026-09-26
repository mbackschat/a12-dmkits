package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shape rules a constant index key meets, one per index kind, each row mirroring one the Kernel answered in
 * `adapter.laws.CheckerIndexKeyConstantAgreementTest` ([KERNEL-FINDINGS KF481]). The null rows are the edges
 * this reader leaves unjudged on purpose, because A12 would read the declaration as regex syntax or build no
 * pattern at all.
 */
class IndexKeyConstantShapeTest {

    @Test
    fun aNumberAdmitsItsSignDigitsAndFraction() {
        val actual = linkedMapOf(
            "0 digits, whole" to numberShapeAdmits(0, 0, "5"),
            "0 digits, negative" to numberShapeAdmits(0, 0, "-5"),
            "0 digits, a leading zero" to numberShapeAdmits(0, 0, "05"),
            "0 digits, a fraction" to numberShapeAdmits(0, 0, "5.0"),
            "0 digits, a plus sign" to numberShapeAdmits(0, 0, "+5"),
            "0 digits, a word" to numberShapeAdmits(0, 0, "x"),
            "0..2, whole" to numberShapeAdmits(0, 2, "5"),
            "0..2, one digit" to numberShapeAdmits(0, 2, "5.5"),
            "0..2, a comma" to numberShapeAdmits(0, 2, "5,55"),
            "0..2, three digits" to numberShapeAdmits(0, 2, "5.555"),
            "0..2, a bare separator" to numberShapeAdmits(0, 2, "5."),
            "2..2, whole" to numberShapeAdmits(2, 2, "5"),
            "2..2, two digits" to numberShapeAdmits(2, 2, "5.00"),
            "2..2, one digit" to numberShapeAdmits(2, 2, "5.0"),
            "inverted counts" to numberShapeAdmits(3, 2, "5.00"),
        )
        val expected = linkedMapOf(
            "0 digits, whole" to true,
            "0 digits, negative" to true,
            "0 digits, a leading zero" to true,
            "0 digits, a fraction" to false,
            "0 digits, a plus sign" to false,
            "0 digits, a word" to false,
            "0..2, whole" to true,
            "0..2, one digit" to true,
            "0..2, a comma" to true,
            "0..2, three digits" to false,
            "0..2, a bare separator" to false,
            "2..2, whole" to false,
            "2..2, two digits" to true,
            "2..2, one digit" to false,
            "inverted counts" to null,
        )
        assertEquals<Map<String, Boolean?>>(expected, actual, "a Number index's constant")
    }

    @Test
    fun aTemporalFormatIsAShapeOfDigitsAndLiterals() {
        val actual = linkedMapOf(
            "a day" to temporalShapeAdmits("yyyy-MM-dd", "2020-01-31"),
            "no calendar day" to temporalShapeAdmits("yyyy-MM-dd", "2020-13-45"),
            "another format" to temporalShapeAdmits("yyyy-MM-dd", "31.01.2020"),
            "a short month" to temporalShapeAdmits("yyyy-MM-dd", "2020-1-31"),
            "dots" to temporalShapeAdmits("dd.MM.yyyy", "31.01.2020"),
            "dashes for dots" to temporalShapeAdmits("dd.MM.yyyy", "31-01-2020"),
            "a quoted T" to temporalShapeAdmits("yyyy-MM-dd'T'HH:mm:ss", "2020-01-31T10:15:00"),
            "the quotes themselves" to temporalShapeAdmits("yyyy-MM-dd'T'HH:mm:ss", "2020-01-31'T'10:15:00"),
            "regex syntax in the format" to temporalShapeAdmits("yyyy(MM)", "2020(01)"),
        )
        val expected = linkedMapOf(
            "a day" to true,
            "no calendar day" to true,
            "another format" to false,
            "a short month" to false,
            "dots" to true,
            "dashes for dots" to false,
            "a quoted T" to true,
            "the quotes themselves" to false,
            "regex syntax in the format" to null,
        )
        assertEquals<Map<String, Boolean?>>(expected, actual, "a temporal index's constant")
    }

    @Test
    fun aDateRangeIsTwoDatesAroundItsSeparator() {
        val actual = linkedMapOf(
            "a range" to dateRangeShapeAdmits("dd.MM.yyyy", "-", "01.01.2020-31.01.2020"),
            "one day" to dateRangeShapeAdmits("dd.MM.yyyy", "-", "01.01.2020"),
            "no separator" to dateRangeShapeAdmits("MM", "", "0112"),
            "a separator that is regex syntax" to dateRangeShapeAdmits("MM", "|", "01|12"),
        )
        val expected = linkedMapOf(
            "a range" to true,
            "one day" to false,
            "no separator" to true,
            "a separator that is regex syntax" to null,
        )
        assertEquals<Map<String, Boolean?>>(expected, actual, "a date-range index's constant")
    }
}
