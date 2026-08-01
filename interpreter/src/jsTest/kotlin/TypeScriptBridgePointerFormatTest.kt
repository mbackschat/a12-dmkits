import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointerFormat
import io.github.mbackschat.a12.dm.interpreter.A12PointerFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Both A12 pointer types cross to TypeScript through the bridge, so the npm façade delegates instead of
 * reimplementing them — our own browser consumer had cloned `eval/Pointer.kt` because the utility was absent from
 * the package. A rejection travels as a message, not as a throwable, because no Kotlin exception may cross this
 * boundary.
 *
 * This file carries no `package` declaration on purpose: `TypeScriptBridge.kt` declares the bridge in the root
 * package (the façade's private transport, which the API-documentation guard refuses to publish), and root
 * declarations are unreachable from a packaged test.
 */
class TypeScriptBridgePointerFormatTest {

    @Test
    fun theBridgeFormatsTheCanonicalPointerAndOmitsTheFirstRepetition() {
        val result = interpreterBridgeFormatA12Pointer("/Order/Items/Count", arrayOf(1, 2, 1))

        assertNull(result.rejection)
        assertEquals("Order/Items[2]/Count", result.value)
    }

    @Test
    fun theBridgeParsesTheHouseFormTheReportsCarry() {
        val result = interpreterBridgeParseA12Pointer("/Order[1]/Items[2]/Count")

        assertNull(result.rejection)
        val parsed = requireNotNull(result.value)
        assertEquals(listOf("Order", "Items", "Count"), parsed.pathParts.map { it.name })
        assertEquals(listOf(1, 2, 1), parsed.pathParts.map { it.repetitionIndex })
        assertEquals("/Order/Items/Count", parsed.fullName)
        assertEquals(listOf(1, 2, 1), parsed.repetitionIndexes.toList())
    }

    @Test
    fun theBridgeReportsAFormatRejectionAsAMessage() {
        val result = interpreterBridgeFormatA12Pointer("/Order/Items", arrayOf(1))

        assertNull(result.value)
        assertTrue(
            result.rejection.orEmpty().contains("2 parts but 1 repetitions"),
            "unexpected rejection: ${result.rejection}",
        )
    }

    @Test
    fun theBridgeReportsAParseRejectionAsAMessage() {
        val result = interpreterBridgeParseA12Pointer("Order/Items[0]/Count")

        assertNull(result.value)
        assertTrue(
            result.rejection.orEmpty().contains("all repetition indexes must be >= 1"),
            "unexpected rejection: ${result.rejection}",
        )
    }

    @Test
    fun theBridgePublishesEachTypesReservedRepetitions() {
        assertEquals(A12PointerFormat.LAST_PART_ZERO, interpreterBridgeA12LastPartZero())
        assertEquals(
            A12PartiallyKnownMultiPointerFormat.WILDCARD,
            interpreterBridgeA12PartiallyKnownWildcard(),
        )
        assertEquals(
            A12PartiallyKnownMultiPointerFormat.UNKNOWN,
            interpreterBridgeA12PartiallyKnownUnknown(),
        )
    }

    // ---- the message channel's own type ---------------------------------------------------------

    @Test
    fun theBridgeReadsAMessagePointerTheExactTypeRefuses() {
        val exact = interpreterBridgeParseA12Pointer("/Plan[1]/Demand[-5]/Headcount[1]")
        assertNull(exact.value, "the exact type must not admit A12's unknown repetition")

        val message = interpreterBridgeParseA12PartiallyKnownPointer("/Plan[1]/Demand[-5]/Headcount[1]")
        assertNull(message.rejection)
        val parsed = requireNotNull(message.value)
        assertEquals("/Plan/Demand/Headcount", parsed.fullName)
        assertEquals(listOf(1, -5, 1), parsed.repetitionIndexes.toList())

        assertEquals(
            "Plan/Demand[-5]/Headcount",
            interpreterBridgeFormatA12PartiallyKnownPointer("/Plan/Demand/Headcount", arrayOf(1, -5, 1)).value,
        )
    }

    /**
     * `toDocumentPointer()`'s two outcomes are distinguishable across the bridge: an inconvertible pointer comes
     * back as a `null` value with **no** rejection, which is not the same signal as a malformed request.
     */
    @Test
    fun theBridgeConvertsAMessagePointerToTheExactTypeOnlyWhenItIsOne() {
        val convertible = interpreterBridgeA12PartiallyKnownPointerToExact("/Order/Items", arrayOf(1, 2))
        assertNull(convertible.rejection)
        assertEquals(listOf(1, 2), requireNotNull(convertible.value).repetitionIndexes.toList())

        val unresolved = interpreterBridgeA12PartiallyKnownPointerToExact("/Plan/Demand", arrayOf(1, -5))
        assertNull(unresolved.rejection, "an inconvertible pointer is an answer, not a rejection")
        assertNull(unresolved.value)

        val malformed = interpreterBridgeA12PartiallyKnownPointerToExact("/Plan/Demand", arrayOf(1, -1))
        assertNull(malformed.value)
        assertTrue(
            malformed.rejection.orEmpty().contains("but was -1"),
            "unexpected rejection: ${malformed.rejection}",
        )
    }

    // ---- Kotlin/JS erases Int, so the bridge is where a non-integer must die --------------------

    /**
     * `Array<Int>` is an untyped JS array at runtime, so a packed consumer can pass `1.5`, `NaN`, `Infinity` or a
     * value past `2^31`. The A12 domain checks would read `1.5 >= 1` as satisfied and emit `Order[1.5]`, a string
     * nothing can parse — so the refusal belongs here, at the erasure boundary, and this measures each case
     * rather than assuming how Kotlin/JS lowers the comparison.
     */
    @Test
    fun theBridgeRefusesARepetitionThatIsNotReallyAnInteger() {
        for (hostile in listOf(1.5, Double.NaN, Double.POSITIVE_INFINITY, -0.5, 2147483648.0, 1e21)) {
            @Suppress("UNCHECKED_CAST")
            val repetitions = arrayOf(1, hostile) as Array<Int>

            val exact = interpreterBridgeFormatA12Pointer("/Order/Items", repetitions)
            assertNull(exact.value, "the exact bridge emitted a pointer for repetition $hostile")
            assertTrue(
                exact.rejection.orEmpty().contains("32-bit integer"),
                "unexpected rejection for $hostile: ${exact.rejection}",
            )

            val message = interpreterBridgeFormatA12PartiallyKnownPointer("/Order/Items", repetitions)
            assertNull(message.value, "the message bridge emitted a pointer for repetition $hostile")
            assertTrue(
                message.rejection.orEmpty().contains("32-bit integer"),
                "unexpected rejection for $hostile: ${message.rejection}",
            )
        }
    }
}
