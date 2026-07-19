package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — a `modelConfig.supportedCharacters` entry that is a legal multi-code-point **grapheme** is
 * matched ATOMICALLY (IF151). The kernel parses a combining sequence into a trie
 * (`CharsetUtil.parse` → `LEGAL_GRAPHEME_TRIE`) and `LegalCharTester.pruefeZeichensatz` consumes a complete
 * configured sequence atomically (longest-trie-prefix, then a singleton/range fallback on the one leading code
 * point). The interpreter must do the same: the FULL decomposed sequence is legal, but the bare base, the bare
 * combining mark, the reversed order, and a repeated component are NOT (the pre-IF151 flatten wrongly admitted
 * the components). Charset = `A-Z` plus the decomposed "é" (base e + combining acute, U+0301); the lowercase base
 * `e` is not independently allowed, isolating the atomicity. Tri-checked: interp == Groovy-dynamic == static-Java.
 */
class SupportedCharactersGraphemeDiffTest {

    private val CHARSET = "ZeichenNichtImZeichensatz"

    /** Tri-check the CHARSET code (code@pointer) for `Ref = value` across both kernel strategies + the interpreter. */
    private fun agree(
        value: String,
        label: String,
        shouldFire: Boolean,
        supportedCharacters: List<String> = listOf("A-Z", E_ACUTE),
    ) {
        val json = DocumentModelDraft.named("policy").locale("en_US")
            .supportedCharacters(*supportedCharacters.toTypedArray())
            .rootGroup("Policy", Consumer { g: GroupBuilder -> g.field("Ref", FieldKind.STRING) })
            .build().toJson()
        val def = ModelLoader.load(json)
        val kDoc = RuntimeLaws.doc().group("/Policy", 1).field(REF, value, 1, 1)
        val groovy = RuntimeLaws.messages(json, kDoc).filter { it.code() == CHARSET }.map { "${it.code()}@${it.field()}" }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(json)
        val javaStatic = engine.messages(engine.instance(kDoc)).filter { it.code() == CHARSET }.map { "${it.code()}@${it.field()}" }.sorted()
        val interp = DmInterpreter(def).validate(Document(def.kinds).group("/Policy", 1).field(REF, value, 1, 1))
            .messages.filter { it.code == CHARSET }.map { "${it.code}@${it.errorPath}" }.sorted()
        assertEquals(groovy, javaStatic, "$label — groovy-dynamic vs generated-Java")
        assertEquals(groovy, interp, "$label — kernel vs interpreter")
        assertEquals(shouldFire, groovy.isNotEmpty(), "$label — expected charset outcome")
    }

    @Test fun fullDecomposedGraphemeIsClean() = agree("POL$E_ACUTE", "A-Z + the whole decomposed é → clean", shouldFire = false)
    @Test fun bareBaseIsOutOfSet() = agree("POLe", "the bare lowercase base e is outside the set → charset fires", shouldFire = true)
    @Test fun bareCombiningMarkIsOutOfSet() = agree("POL$ACUTE", "the bare combining acute is outside the set → charset fires", shouldFire = true)
    @Test fun reversedOrderIsOutOfSet() = agree("POL${ACUTE}e", "the reversed sequence is not the configured grapheme → charset fires", shouldFire = true)
    @Test fun repeatedComponentIsOutOfSet() = agree("POL$E_ACUTE$ACUTE", "é then a stray combining mark → charset fires", shouldFire = true)

    @Test
    fun legalSharedPrefixesSelectCompleteGraphemesWithoutLeakingComponents() {
        val definitions = listOf("A-Z", E_ACUTE, E_GRAVE)
        agree("POL$E_ACUTE$E_GRAVE", "both shared-prefix graphemes are consumed whole", false, definitions)
        agree("POLe", "their shared base is not independently legal", true, definitions)
        agree("POL$GRAVE", "a shared-prefix component is not independently legal", true, definitions)
    }

    @Test
    fun aLegalTerminalPrefixSelectsTheLongestCompleteSequence() {
        val definitions = listOf("A-Z", E_ACUTE, "${E_ACUTE}x")
        agree("POL${E_ACUTE}x$E_ACUTE", "long and short terminal-prefix graphemes are both consumed", false, definitions)
        agree("POLx", "the longer sequence does not leak its suffix", true, definitions)
    }

    private companion object {
        const val REF = "/Policy/Ref"
        const val ACUTE = "\u0301"
        const val GRAVE = "\u0300"
        const val E_ACUTE = "e$ACUTE"
        const val E_GRAVE = "e$GRAVE"
    }
}
