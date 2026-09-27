package io.github.mbackschat.a12.dm.consistency.syntax

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which calls a condition makes, and which of their arguments reduce to one entity path.
 *
 * [referencesIn] answers what a text NAMES; this answers where each name stood, which is what a law about
 * one operator's argument list needs. The two questions have different right answers on the same text, so
 * they are measured separately rather than one being derived from the other.
 *
 * An argument's path is ABSENT whenever the argument is not a bare entity spec. That is not "names nothing":
 * it is this reader declining to reduce the argument to one path, so a consumer must treat it as an operand
 * it cannot compare rather than one that is not there.
 */
class ConditionCallTest {

    private fun calls(text: String) = callsIn(text, ConditionLanguage.EN, SyntaxEntry.CONDITION)

    private fun operands(text: String) = calls(text).map { call -> call.operator to call.operands.map { it.path } }

    @Test
    fun aCallsArgumentsAreSeparatedAndEachBarePathIsRead() {
        assertEquals(
            listOf("AllGroupsFilled" to listOf("Garage", "/Policy/Garage", "Coverage/Vehicle")),
            operands("AllGroupsFilled(Garage, /Policy/Garage, Coverage/Vehicle)"),
        )
    }

    /**
     * Two calls over the same name stay two calls — the fact no scan over a flat name set can recover, and
     * the one a group-list duplicate law turns on.
     */
    @Test
    fun twoCallsNamingTheSameGroupStayTwoCalls() {
        assertEquals(
            listOf(
                "AllGroupsFilled" to listOf("Garage", "Coverage"),
                "AllGroupsFilled" to listOf("Garage", "GarageBay"),
            ),
            operands("AllGroupsFilled(Garage, Coverage) And AllGroupsFilled(Garage, GarageBay)"),
        )
    }

    /** A repetition wildcard stays in the path, since whether an operand is starred is often the law. */
    @Test
    fun aRepetitionWildcardIsKept() {
        assertEquals(
            listOf("NumberOfFilledGroups" to listOf("Claims*", "Claims")),
            operands("NumberOfFilledGroups(Claims*, Claims) > 0"),
        )
    }

    /**
     * An argument carrying anything but path syntax has no path — each arm for its own reason.
     *
     * The clause keywords are the discriminating cases: `Having`, `For` and `@From` all arrive as ordinary
     * NAME tokens, so without the keyword table a filtered or scoped reference reduces to a path that
     * concatenates the operator into the name. It would never resolve, so the cost is not a wrong element —
     * it is a caller told "this operand does not resolve" where the truth is "this reader did not read it",
     * and those two mean opposite things to a family that pre-empts itself on an unresolvable operand.
     */
    @Test
    fun anArgumentThatIsNotABareEntitySpecHasNoPath() {
        fun reduced(vararg operands: String) = operands.map {
            operandOf(tokenize(it), ConditionLanguage.EN, it).path
        }
        val actual = mutableMapOf<String, List<String?>>()
        actual["a filtered reference (`Having`)"] =
            calls("Sum(Claims*/Amount Having [Claims/Paid] == \"Y\") > 0").single().operands.map { it.path }
        actual["a scoped reference (`@From`)"] =
            reduced("Garage @From 2", "Coverage")
        actual["a clause keyword that is a BARE NAME (`For`)"] =
            calls("AllGroupsFilled(Garage For 2, Coverage)").single().operands.map { it.path }
        actual["a category selector (`->`)"] =
            calls("AllGroupsFilled(Garage -> Band, Coverage)").single().operands.map { it.path }
        actual["a bracketed field value"] =
            reduced("[Tier]", "Plate")
        actual["a literal"] = reduced("\"Garage\"", "Coverage")
        actual["an outer-repetition marker (`\$`)"] =
            calls("AllGroupsFilled(\$Claims, Coverage)").single().operands.map { it.path }

        val expected = mutableMapOf<String, List<String?>>()
        expected["a filtered reference (`Having`)"] = listOf(null)
        expected["a scoped reference (`@From`)"] = listOf(null, "Coverage")
        // The row the keyword table is FOR. Every other arm here is disqualified by punctuation the path
        // grammar does not use; `For` arrives as an ordinary NAME between two path-shaped tokens, so without
        // the table this argument reduces to the path `GarageFor2` — a spelling that resolves to nothing and
        // therefore reads to a caller as "this operand is undeclared" rather than "I did not read it".
        expected["a clause keyword that is a BARE NAME (`For`)"] = listOf(null, "Coverage")
        expected["a category selector (`->`)"] = listOf(null, "Coverage")
        expected["a bracketed field value"] = listOf(null, "Plate")
        // A quoted NAME is a path segment and a quoted STRING is a literal; the tokenizer tells them apart,
        // which is why this row is not the same question as the one above it.
        expected["a literal"] = listOf(null, "Coverage")
        expected["an outer-repetition marker (`\$`)"] = listOf(null, "Coverage")
        assertEquals(expected, actual, "an argument that is not a bare entity spec has no path")
    }

    /** A nested call is its own entry, and it is not the outer call's operand path. */
    @Test
    fun aNestedCallIsItsOwnEntryAndNotTheOutersPath() {
        assertEquals(
            listOf(
                "AddDays" to listOf("Start", null),
                "DifferenceInDays" to listOf(null, "End"),
            ),
            operands("DifferenceInDays(AddDays(Start, 1), End) > 0"),
        )
    }

    /**
     * Text that does not parse yields the calls read to their closing parenthesis before the failure, and
     * never a call the failure cut short.
     *
     * A12 runs a call's own check once the call is read, whatever follows it, an inner call inside an outer
     * one that then fails included (`rulekit.validate.laws.ConditionClassWithholdingLawsTest
     * .aGateIsRaisedWhereItsOwnProductionEnds`), so those calls are what an operand law must still judge. A
     * call the failure cut short is left out: its argument list would be SHORTER, the shape that makes an
     * operand law look satisfied.
     */
    @Test
    fun textThatDoesNotParseYieldsTheCallsReadToTheirEnd() {
        val actual = mutableMapOf<String, List<Pair<String, List<String?>>>>()
        actual["a complete call, then a stray parenthesis"] = operands("AllGroupsFilled(Garage, Garage))")
        actual["a call cut short"] = operands("AllGroupsFilled(Garage, Garage")
        actual["an inner call read to its end, the outer cut short"] =
            operands("Abs(RoundUpValue(Reference, 2) > 1) > 0")
        actual["a call, then a stray connective, then a call"] =
            operands("FieldFilled(Garage) And And FieldFilled(Coverage)")
        val expected = mutableMapOf<String, List<Pair<String, List<String?>>>>()
        expected["a complete call, then a stray parenthesis"] =
            listOf("AllGroupsFilled" to listOf("Garage", "Garage"))
        expected["a call cut short"] = emptyList()
        expected["an inner call read to its end, the outer cut short"] =
            listOf("RoundUpValue" to listOf("Reference", null))
        expected["a call, then a stray connective, then a call"] = listOf("FieldFilled" to listOf("Garage"))
        assertEquals(expected, actual, "the calls a failed parse read to their end")
    }

    /**
     * A call spans its name through its closing parenthesis, and each argument its own tokens: the extents
     * of the two productions an operator's gate can belong to (`Check…Impl` at the call's end, a date slot's
     * check at its argument's end).
     */
    @Test
    fun aCallAndEachOfItsArgumentsCarryTheirExtent() {
        val text = "DifferenceInDays(AddDays(Start, 1), End) > 0"
        val actual = calls(text).map { call ->
            text.substring(call.at, call.end) to call.operands.map { text.substring(it.at, it.end) }
        }
        assertEquals(
            listOf(
                "AddDays(Start, 1)" to listOf("Start", "1"),
                "DifferenceInDays(AddDays(Start, 1), End)" to listOf("AddDays(Start, 1)", "End"),
            ),
            actual,
        )
    }

    /**
     * A clause-carrying operand keeps `path` absent and says what it is about through `base` and `clauses`.
     *
     * Both halves are the contract. `path` stays as narrow as it has always been, so no family already
     * reading it shifts; the reduction rides beside it, which is what let `REPETITION_SCOPE_GROUP` see an
     * `@From` scope the reader used to throw away.
     */
    @Test
    fun aClauseCarryingOperandIsSplitIntoItsBaseAndItsClauses() {
        val operand = calls("RepetitionNotUnique(Crates/CrateRef @From Crates)").single().operands.single()
        assertEquals(null, operand.path)
        assertEquals("Crates/CrateRef", operand.base)
        assertEquals(listOf(OperandClause("@From", "Crates", "Crates", at = 42)), operand.clauses)
    }

    /** A bare operand carries no clause and its base is its path, so a consumer needs no special case. */
    @Test
    fun aBareOperandsBaseIsItsPath() {
        val operand = calls("RepetitionNotUnique(Crates/CrateRef)").single().operands.single()
        assertEquals("Crates/CrateRef", operand.path)
        assertEquals("Crates/CrateRef", operand.base)
        assertEquals(emptyList(), operand.clauses)
    }

    /**
     * A filter's operand is a whole condition, so its clause reduces to no path — and its authored text is
     * carried instead, which is the only form a consumer could read further.
     */
    @Test
    fun aFilterClauseCarriesItsTextRatherThanAPath() {
        val text = "Sum(Items*/Count Having [Items/Sku] == \"X\") > 0"
        val operand = calls(text).first().operands.single()
        assertEquals("Items*/Count", operand.base)
        assertEquals(listOf(OperandClause("Having", null, "[Items/Sku] == \"X\"", at = 24)), operand.clauses)
        // The offset is into the whole member text, which is what lets a reader set the body aside by place.
        assertEquals(24, text.indexOf('['))
    }

    /**
     * A clause INSIDE a filter belongs to the spec that carries it, not to the outer operand.
     *
     * This is the whole reason the scan tracks delimiter depth: without it the inner `For` would be cut as a
     * second clause of the outer operand, and a family reading `clauses` would attribute a key to a spec
     * that does not carry one.
     */
    @Test
    fun aClauseNestedInsideAFilterStaysWithItsOwnSpec() {
        val operand = calls("Sum(Items*/Count Having [Lines/Sku For \"K-1\"] == \"X\") > 0")
            .first().operands.single()
        assertEquals(listOf("Having"), operand.clauses.map { it.keyword })
    }

    /**
     * A `Having` after an ENTITY index key filters that key, the innermost spec that can take one; after a
     * string key, or with no key, it filters the operand. The kernel rows are
     * `rulekit.validate.laws.FilteredOperandGateOrderLawsTest.whoseFilterAHavingAfterAnIndexKeyIs`.
     */
    @Test
    fun aFilterAfterAnEntityKeyFiltersTheKey() {
        fun owner(text: String): String? {
            val clauses = calls(text).first().operands.single().clauses
            return clauses.single { it.keyword == "Having" }.filtersKey?.text
        }
        val actual = mapOf(
            "an entity key" to owner("Sum(Items*/Count For Reference Having [Items/Sku] == \"X\") > 0"),
            "a string key" to owner("Sum(Items/Count For \"K-1\" Having [Items/Sku] == \"X\") > 0"),
            "no key" to owner("Sum(Items*/Count Having [Items/Sku] == \"X\") > 0"),
            "an entity key keyed by a string" to
                owner("Sum(Items/Count For Lines/Sku For \"K-1\" Having [Items/Sku] == \"X\") > 0"),
        )
        val expected = mapOf(
            "an entity key" to "Reference",
            "a string key" to null,
            "no key" to null,
            // The index on `Lines/Sku` is the first key's own, so the filter is taken by that key's spec.
            "an entity key keyed by a string" to "Lines/Sku",
        )
        assertEquals(expected, actual)
    }

    /** An operation's entry rule reads the same calls — a computation's text is not a second dialect. */
    @Test
    fun anOperationsCallsAreReadTheSameWay() {
        assertEquals(
            listOf("Sum" to listOf("Claims*/Amount")),
            callsIn("Sum(Claims*/Amount)", ConditionLanguage.EN, SyntaxEntry.OPERATION)
                .map { call -> call.operator to call.operands.map { it.path } },
        )
    }
}
