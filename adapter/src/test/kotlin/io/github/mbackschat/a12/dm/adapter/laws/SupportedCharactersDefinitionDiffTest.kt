package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.core.tool.a12internal.api.error.IProblem
import com.mgmtp.a12.kernel.md.facade.a12internal.KernelUtils
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * The pinned kernel's structural charset-definition oracle, paired with the kernel-free construction boundary.
 * Runtime agreement for the accepted overlap shapes lives in [SupportedCharactersGraphemeDiffTest].
 */
class SupportedCharactersDefinitionDiffTest {

    @Test
    fun theKernelAndInterpreterAcceptNonAmbiguousSharedAndTerminalPrefixes() {
        val accepted = listOf(
            listOf(E_ACUTE, E_GRAVE),
            listOf(E_ACUTE, "${E_ACUTE}x"),
        )
        for (definitions in accepted) {
            assertTrue(kernelProblems(definitions).isEmpty(), "pinned kernel accepts $definitions")
            LegalCharset.of(graphemes = definitions)
            LegalCharset.ofSupportedCharacters(definitions)
        }
    }

    @Test
    fun theKernelAndInterpreterRejectEveryBoundedDefinitionDiscriminator() {
        val rejected = listOf(
            listOf(""),
            listOf(EMOJI),
            listOf("e$ACUTE$GRAVE$CEDILLA"),
            listOf("ab"),
            listOf("Z-A"),
            listOf("q${ACUTE}r", "q$ACUTE", "r$GRAVE"),
        )
        for (definitions in rejected) {
            assertFalse(kernelProblems(definitions).isEmpty(), "pinned kernel rejects $definitions")
            assertThrows(IllegalArgumentException::class.java) {
                LegalCharset.ofSupportedCharacters(definitions)
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            LegalCharset.of(ranges = listOf('Z'.code..'A'.code))
        }
    }

    private fun kernelProblems(definitions: List<String>): List<IProblem> = buildList {
        KernelUtils.validateCharset(definitions, Locale.ENGLISH) { add(it) }
    }

    private companion object {
        const val ACUTE = "\u0301"
        const val GRAVE = "\u0300"
        const val CEDILLA = "\u0327"
        const val E_ACUTE = "e$ACUTE"
        const val E_GRAVE = "e$GRAVE"
        const val EMOJI = "\uD83D\uDE00"
    }
}
