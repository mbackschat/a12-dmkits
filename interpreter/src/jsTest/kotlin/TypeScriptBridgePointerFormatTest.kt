import io.github.mbackschat.a12.dm.interpreter.A12PointerFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The canonical-pointer utility crosses to TypeScript through the bridge, so the npm façade delegates instead
 * of reimplementing it — our own browser consumer had cloned `eval/Pointer.kt` because the utility was absent
 * from the package. A rejection travels as a message, not as a throwable, because no Kotlin exception may
 * cross this boundary.
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
        assertEquals(listOf("Order", "Items", "Count"), parsed.names.toList())
        assertEquals(listOf(1, 2, 1), parsed.repetitions.toList())
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
    fun theBridgePublishesA12sTwoReservedRepetitions() {
        assertEquals(A12PointerFormat.LAST_PART_ZERO, interpreterBridgeA12LastPartZero())
        assertEquals(A12PointerFormat.UNKNOWN_REPETITION, interpreterBridgeA12UnknownRepetition())
    }
}
