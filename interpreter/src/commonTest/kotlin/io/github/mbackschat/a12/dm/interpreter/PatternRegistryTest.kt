package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.gen.ValidValues
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.regex.PatternRegistry
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PatternRegistryTest {
    @Test
    fun equalSourcesCompileOncePerOwner() {
        val patterns = PatternRegistry()
        repeat(20) { assertTrue(patterns.matches("(?i:abc)*", "ABCabc")) }
        assertEquals(1, patterns.compilationCount)
    }

    @Test
    fun engineDeduplicatesRuleAndDeclaredFieldOccurrences() {
        val source = "(?i:abc)*"
        val model = ModelDef(
            fields = listOf(
                FieldDef("/M/A", Kind.STRING, pattern = source),
                FieldDef("/M/B", Kind.STRING, pattern = source),
            ),
            rules = listOf(
                RuleDef("/M/R1", "/M", "[A] PatternMatched \"$source\"", "R1", "A"),
                RuleDef("/M/R2", "/M", "[B] PatternViolated \"$source\"", "R2", "B"),
            ),
        )
        val engine = DmInterpreter(model)
        assertEquals(emptyList(), engine.supportAudit())
        assertEquals(1, engine.patternCompilationCount())
    }

    @Test
    fun seedVerifierUsesTheCompatibleDialectOnBothHosts() {
        val field = FieldDef("/M/Code", Kind.STRING, pattern = "(?i:abc)*")
        val value = ValidValues.value(field, listOf(1, 1), Random(0))
        assertNotNull(value)
        assertEquals("", value)
    }
}
