package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine law for the order-independent row domain of a same-path composite `RepetitionNotUnique` key.
 * The ancestor and descendant key spellings are the same relation; authored order may change neither model
 * support nor duplicate pointers. Parallel key paths remain kernel-rejected by `IterationLawsTest`.
 */
class RepetitionNotUniqueKeyOrderDiffTest {

    private val modelJson = DocumentModelDraft.named("rnu-key-order").locale("en_US")
        .rootGroup("Root") { root ->
            root.group("Outer") { outer ->
                outer.repeatable(3).stringField("Marker") { }
                outer.group("Middle") { middle ->
                    middle.repeatable(2).group("Inner") { inner ->
                        inner.repeatable(4).stringField("Optional") { }
                    }
                }
            }
        }
        .build().toJson()
        .let(EditSession::read)
        .also { session ->
            session.addRule { bound ->
                bound.rule(bound.group(ROOT)).name("AncestorFirst").code(ANCESTOR_FIRST)
                    .errorField(bound.str(OPTIONAL))
                    .`when`(Dsl.repetitionNotUnique(bound.field(MARKER), bound.field(OPTIONAL)))
                    .severity(Severity.ERROR).message(Dsl.en("The nested key tuple is duplicated.")).build()
            }
            session.addRule { bound ->
                bound.rule(bound.group(ROOT)).name("DescendantFirst").code(DESCENDANT_FIRST)
                    .errorField(bound.str(OPTIONAL))
                    .`when`(Dsl.repetitionNotUnique(bound.field(OPTIONAL), bound.field(MARKER)))
                    .severity(Severity.ERROR).message(Dsl.en("The nested key tuple is duplicated.")).build()
            }
            session.validate()
        }
        .toJson()

    @Test
    fun samePathCompositeKeyOrderDoesNotChangeTheDuplicateRelation() {
        assertTriEngine(
            label = "two implicit descendants share the ancestor key and empty descendant key",
            markers = listOf("D", "D", "X"),
            expected = setOf(
                "$ANCESTOR_FIRST|OMISSION_ERROR@/Root[1]/Outer[1]/Middle[1]/Inner[1]/Optional",
                "$ANCESTOR_FIRST|OMISSION_ERROR@/Root[1]/Outer[2]/Middle[1]/Inner[1]/Optional",
                "$DESCENDANT_FIRST|OMISSION_ERROR@/Root[1]/Outer[1]/Middle[1]/Inner[1]/Optional",
                "$DESCENDANT_FIRST|OMISSION_ERROR@/Root[1]/Outer[2]/Middle[1]/Inner[1]/Optional",
            ),
        )
        assertTriEngine(
            label = "distinct ancestor keys are the non-firing control",
            markers = listOf("D", "E", "X"),
            expected = emptySet(),
        )
    }

    private fun assertTriEngine(label: String, markers: List<String>, expected: Set<String>) {
        val definition = ModelLoader.load(modelJson)
        fun kernelDocument() = RuntimeLaws.doc().group(INNER, 1, 3, 2, 4).also { doc ->
            markers.forEachIndexed { index, marker -> doc.field(MARKER, marker, 1, index + 1, 1) }
            for (inner in 1..4) doc.field(OPTIONAL, "V$inner", 1, 3, 2, inner, 1)
        }
        fun interpreterDocument() = definition.newDocument().group(INNER, 1, 3, 2, 4).also { doc ->
            markers.forEachIndexed { index, marker -> doc.field(MARKER, marker, 1, index + 1, 1) }
            for (inner in 1..4) doc.field(OPTIONAL, "V$inner", 1, 3, 2, inner, 1)
        }
        val codes = setOf(ANCESTOR_FIRST, DESCENDANT_FIRST)
        fun kernelSignatures(messages: List<RuntimeLaws.Msg>) = messages.filter { it.code() in codes }
            .mapTo(sortedSetOf()) { "${it.code()}|${it.type()}@${it.field()}" }
        val dynamic = kernelSignatures(RuntimeLaws.messages(modelJson, kernelDocument()))
        val java = RuntimeLaws.sharedKernelJavaEngine(modelJson)
        val static = kernelSignatures(java.messages(java.instance(kernelDocument())))
        val interpreterResult = DmInterpreter(definition).validate(interpreterDocument())
        val interpreter = interpreterResult.messages.filter { it.code in codes }
            .mapTo(sortedSetOf()) { "${it.code}|${it.type}_ERROR@${it.errorPath}" }
        assertEquals(expected.toSortedSet(), dynamic, "$label: dynamic kernel")
        assertEquals(expected.toSortedSet(), static, "$label: static kernel")
        assertEquals(emptyList<Any>(), interpreterResult.unsupported, "$label: interpreter support")
        assertEquals(expected.toSortedSet(), interpreter, "$label: interpreter")
    }

    private companion object {
        const val ROOT = "/Root"
        const val INNER = "/Root/Outer/Middle/Inner"
        const val MARKER = "/Root/Outer/Marker"
        const val OPTIONAL = "/Root/Outer/Middle/Inner/Optional"
        const val ANCESTOR_FIRST = "RNU_ANCESTOR_FIRST"
        const val DESCENDANT_FIRST = "RNU_DESCENDANT_FIRST"
    }
}
