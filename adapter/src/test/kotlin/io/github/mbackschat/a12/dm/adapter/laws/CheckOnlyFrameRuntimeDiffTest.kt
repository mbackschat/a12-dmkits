package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What evaluation does with a model whose frame A12 builds and then cannot check (KERNEL-FINDINGS KF650's four
 * check-only shapes), with no consistency check in front of either Kernel strategy. Beside the unchanged control,
 * each shape is evaluated by dynamic Groovy, generated Java and the interpreter, and A12's consistency check is the
 * fourth column. A Kernel failure is named by the A12 class that throws, so a failure of our harness cannot pass for
 * one.
 *
 * Only a missing `modelVersion` is evaluated by the Kernel. The other three throw before any rule runs: in the
 * generated-code id, in model expansion and in the code generator's character pre-check. The interpreter evaluates all
 * four, which is the open decision INTERPRETER-GAPS IG183 records; this pins today's column until it is taken. Own
 * domain: an insurance policy whose reference is required.
 */
class CheckOnlyFrameRuntimeDiffTest {

    private val mapper = ObjectMapper()

    private val base: String = DocumentModelDraft.named("policy-frame-runtime").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("Ref") { } }
        .build().toJson()

    private val sound: String = BoundModel.ofJson(base).let { m ->
        RuntimeLaws.withRule(base, "/Policy", "RRef", "../Ref", "REF", RuntimeLaws.render(m.str("/Policy/Ref").notFilled()))
    }

    private fun edited(pointer: String, value: JsonNode?): String {
        val root = mapper.readTree(sound) as ObjectNode
        val parent = root.at(pointer.substringBeforeLast('/')) as ObjectNode
        val name = pointer.substringAfterLast('/')
        if (value == null) parent.remove(name) else parent.set<JsonNode>(name, value)
        return root.toString()
    }

    private val shapes: Map<String, String> = linkedMapOf(
        "control" to sound,
        "modelVersion absent" to edited("/header/modelVersion", null),
        "modelInfo absent" to edited("/content/modelInfo", null),
        "locales absent" to edited("/header/locales", null),
        "locales null" to edited("/header/locales", mapper.nullNode()),
        "a null supported character" to edited("/content/modelConfig/supportedCharacters", mapper.readTree("[\"a-z\", null]")),
    )

    /** `[dynamic Groovy, generated Java, interpreter, A12's consistency check]` over a policy with no `Ref`. */
    private fun observed(model: String): List<String> {
        fun attempt(engine: () -> List<String>) = try {
            engine().sorted().joinToString()
        } catch (refused: RuntimeException) {
            val thrower = refused.stackTrace.firstOrNull { it.className.startsWith("com.mgmtp.") }
            "A12 throws in ${thrower?.className?.substringAfterLast('.') ?: "no A12 class: $refused"}"
        }
        val kernelDoc = RuntimeLaws.doc().group("/Policy", 1)
        return listOf(
            attempt { RuntimeLaws.messages(model, kernelDoc).map { it.code() } },
            attempt { RuntimeLaws.kernelJavaEngine(model).messagesModern(kernelDoc, null, java.util.Locale.US).map { it.code() } },
            attempt {
                val definition = ModelLoader.load(model)
                DmInterpreter(definition).validateFull(definition.newDocument().group("/Policy", 1)).map { it.code }
            },
            attempt { RuntimeLaws.consistencyMessages(model) }.ifEmpty { "consistent" },
        )
    }

    @Test
    fun onlyAMissingVersionIsEvaluatedByTheKernelWhileTheInterpreterEvaluatesAllFour() {
        fun unevaluated(thrower: String) = listOf("A12 throws in $thrower", "A12 throws in $thrower", "REF", "A12 throws in $thrower")
        val expansion = "DocumentModelExpandService\$IncludeExpandVisitor"
        assertEquals(
            mapOf(
                "control" to listOf("REF", "REF", "REF", "consistent"),
                "modelVersion absent" to listOf("REF", "REF", "REF", "A12 throws in VersionChecker"),
                "modelInfo absent" to unevaluated("DocumentModelInfoWrapper"),
                "locales absent" to unevaluated(expansion),
                "locales null" to unevaluated(expansion),
                "a null supported character" to unevaluated("CharsetUtil"),
            ),
            shapes.mapValues { (_, model) -> observed(model) },
            "as [dynamic Groovy, generated Java, interpreter, consistency]",
        )
    }
}
