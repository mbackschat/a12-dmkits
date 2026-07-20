package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.core.tool.a12internal.api.error.IProblem
import com.mgmtp.a12.kernel.md.facade.a12internal.KernelUtils
import io.github.mbackschat.a12.dm.modelkit.SupportedCharactersContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

/** The public Java authoring contract accepts and rejects exactly the pinned kernel's charset definitions. */
class SupportedCharactersAuthoringContractDiffTest {

    @Test
    fun authoringPreflightMatchesTheKernelAcrossEveryDefinitionClass() {
        val definitions = listOf(
            listOf<String>(),
            listOf("A-Z", E_ACUTE),
            listOf(E_ACUTE, E_GRAVE),
            listOf(E_ACUTE, "${E_ACUTE}x"),
            listOf(""),
            listOf(EMOJI),
            listOf("e$ACUTE$GRAVE$CEDILLA"),
            listOf("ab"),
            listOf("Z-A"),
            listOf("A", "q${ACUTE}r", "q$ACUTE", "r$GRAVE"),
        )

        for (entries in definitions) {
            assertEquals(
                kernelProblems(entries).isEmpty(),
                SupportedCharactersContract.firstProblem(entries).isEmpty,
                "authoring preflight must match the kernel for $entries",
            )
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
