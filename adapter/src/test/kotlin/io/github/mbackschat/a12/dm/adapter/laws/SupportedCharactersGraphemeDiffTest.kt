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
    private val eAcute = "é"   // DECOMPOSED é
    private val acute = "́"     // bare combining acute

    // A policy whose config allows upper-case letters + the decomposed grapheme é (a DIN-91379-style combining set).
    private val json = DocumentModelDraft.named("policy").locale("en_US")
        .supportedCharacters("A-Z", eAcute)
        .rootGroup("Policy", Consumer { g: GroupBuilder -> g.field("Ref", FieldKind.STRING) })
        .build().toJson()

    private val def = ModelLoader.load(json)

    /** Tri-check the CHARSET code (code@pointer) for `Ref = value` across both kernel strategies + the interpreter. */
    private fun agree(value: String, label: String) {
        val kDoc = RuntimeLaws.doc().group("/Policy", 1).field(REF, value, 1, 1)
        val groovy = RuntimeLaws.messages(json, kDoc).filter { it.code() == CHARSET }.map { "${it.code()}@${it.field()}" }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(json)
        val javaStatic = engine.messages(engine.instance(kDoc)).filter { it.code() == CHARSET }.map { "${it.code()}@${it.field()}" }.sorted()
        val interp = DmInterpreter(def).validate(Document(def.kinds).group("/Policy", 1).field(REF, value, 1, 1))
            .messages.filter { it.code == CHARSET }.map { "${it.code}@${it.errorPath}" }.sorted()
        assertEquals(groovy, javaStatic, "$label — groovy-dynamic vs generated-Java")
        assertEquals(groovy, interp, "$label — kernel vs interpreter")
    }

    @Test fun fullDecomposedGraphemeIsClean() = agree("POL$eAcute", "A-Z + the whole decomposed é → clean")
    @Test fun bareBaseIsOutOfSet() = agree("POLe", "the bare lowercase base e is outside the set → charset fires")
    @Test fun bareCombiningMarkIsOutOfSet() = agree("POL$acute", "the bare combining acute is outside the set → charset fires")
    @Test fun reversedOrderIsOutOfSet() = agree("POL${acute}e", "the reversed sequence is not the configured grapheme → charset fires")
    @Test fun repeatedComponentIsOutOfSet() = agree("POL$eAcute$acute", "é then a stray combining mark → charset fires")

    private companion object {
        const val REF = "/Policy/Ref"
    }
}
