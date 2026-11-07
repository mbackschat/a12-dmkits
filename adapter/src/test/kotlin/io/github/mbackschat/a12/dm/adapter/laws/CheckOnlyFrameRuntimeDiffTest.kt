package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationError
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What evaluation does with a model whose frame A12 builds and then cannot check (KERNEL-FINDINGS KF650's three
 * check-only shapes), with no consistency check in front of either Kernel strategy. Beside the unchanged control,
 * each shape is evaluated by dynamic Groovy, generated Java and the interpreter, and A12's consistency check is the
 * fourth column. A Kernel failure is named by the A12 class that throws, so a failure of our harness cannot pass for
 * one.
 *
 * Only a missing `modelVersion` is evaluated by the Kernel. The other two throw before any rule runs: in the
 * generated-code id and in the code generator's character pre-check. The interpreter's runtime preparation refuses
 * the same two by the member's path and evaluates the third (INTERPRETER-FINDINGS IF451). Missing locales were a
 * fourth shape until kernel 31.3.1, which reads them as none and so checks the model. Own domain: an insurance policy
 * whose reference is required.
 */
class CheckOnlyFrameRuntimeDiffTest {

    private val mapper = ObjectMapper()

    private val base: String = DocumentModelDraft.named("policy-frame-runtime").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("Ref") { } }
        .build().toJson()

    private val ruleCondition: String = RuntimeLaws.render(BoundModel.ofJson(base).str("/Policy/Ref").notFilled())

    private val sound: String = RuntimeLaws.withRule(base, "/Policy", "RRef", "../Ref", "REF", ruleCondition)

    private fun edited(pointer: String, value: JsonNode?): String = edited(sound, pointer, value)

    private fun edited(model: String, pointer: String, value: JsonNode?): String {
        val root = mapper.readTree(model) as ObjectNode
        val parent = root.at(pointer.substringBeforeLast('/')) as ObjectNode
        val name = pointer.substringAfterLast('/')
        if (value == null) parent.remove(name) else parent.set<JsonNode>(name, value)
        return root.toString()
    }

    /** The control and the three shapes, each edited into [model]. */
    private fun shapes(model: String): Map<String, String> = linkedMapOf(
        "control" to model,
        "modelVersion absent" to edited(model, "/header/modelVersion", null),
        "modelInfo absent" to edited(model, "/content/modelInfo", null),
        "a null supported character" to edited(model, "/content/modelConfig/supportedCharacters", mapper.readTree("[\"a-z\", null]")),
    )

    /** [model] with its locales absent, then `null`. */
    private fun missingLocales(model: String): Map<String, String> = linkedMapOf(
        "locales absent" to edited(model, "/header/locales", null),
        "locales null" to edited(model, "/header/locales", mapper.nullNode()),
    )

    /** `[dynamic Groovy, generated Java, interpreter, A12's consistency check]` over a policy with no `Ref`. */
    private fun observed(model: String): List<String> =
        evaluated(model) { PreparedModel.loadExpandedJson(model) } +
            attempt { RuntimeLaws.consistencyMessages(model) }.ifEmpty { "consistent" }

    /** `[dynamic Groovy, generated Java, interpreter]`: A12 evaluating the self-contained [model], beside [interpreter]. */
    private fun evaluated(model: String, interpreter: () -> PreparedModel): List<String> {
        val kernelDoc = RuntimeLaws.doc().group("/Policy", 1)
        return listOf(
            attempt { RuntimeLaws.messages(model, kernelDoc).map { it.code() } },
            attempt { RuntimeLaws.kernelJavaEngine(model).messagesModern(kernelDoc, null, java.util.Locale.US).map { it.code() } },
            try {
                val prepared = interpreter()
                prepared.createInterpreter().validateFull(prepared.documents.readDocument("""{"Policy":{}}"""))
                    .findings.map { it.code }.sorted().joinToString()
            } catch (refused: ModelPreparationError) {
                "refused " + refused.diagnostics.joinToString { "${it.code} ${it.path}" }
            },
        )
    }

    /** A Kernel failure is named by the innermost A12 class on its stack, so a failure of our harness cannot pass for one. */
    private fun attempt(engine: () -> List<String>) = try {
        engine().sorted().joinToString()
    } catch (refused: RuntimeException) {
        val thrower = refused.stackTrace.firstOrNull { it.className.startsWith("com.mgmtp.") }
        "A12 throws in ${thrower?.className?.substringAfterLast('.') ?: "no A12 class: $refused"}"
    }

    @Test
    fun onlyAMissingVersionIsEvaluatedAndTheInterpreterRefusesTheOtherTwoAsTheKernelDoes() {
        fun unevaluated(thrower: String, member: String) =
            listOf("A12 throws in $thrower", "A12 throws in $thrower", "refused INVALID_MODEL_SOURCE $member", "A12 throws in $thrower")
        assertEquals(
            mapOf(
                "control" to listOf("REF", "REF", "REF", "consistent"),
                "modelVersion absent" to listOf("REF", "REF", "REF", "A12 throws in VersionChecker"),
                "modelInfo absent" to unevaluated("DocumentModelInfoWrapper", "content.modelInfo"),
                "a null supported character" to unevaluated("CharsetUtil", "content.modelConfig.supportedCharacters[1]"),
            ),
            shapes(sound).mapValues { (_, model) -> observed(model) },
            "as [dynamic Groovy, generated Java, interpreter, consistency]",
        )
    }

    /**
     * The rule is not what stops A12: the same shapes on the policy with no rule still stop both strategies, so a
     * refusal does not depend on the model carrying a rule.
     */
    @Test
    fun withNoRuleA12StillCannotEvaluateTheTwo() {
        fun unevaluated(groovy: String, java: String, member: String) =
            listOf("A12 throws in $groovy", "A12 throws in $java", "refused INVALID_MODEL_SOURCE $member", "A12 throws in $java")
        assertEquals(
            mapOf(
                "control" to listOf("", "", "", "consistent"),
                "modelVersion absent" to listOf("", "", "", "A12 throws in VersionChecker"),
                "modelInfo absent" to unevaluated("DocumentModelInfoWrapper", "DocumentModelInfoWrapper", "content.modelInfo"),
                "a null supported character" to
                    unevaluated("CharsetUtil", "CharsetUtil", "content.modelConfig.supportedCharacters[1]"),
            ),
            shapes(base).mapValues { (_, model) -> observed(model) },
            "as [dynamic Groovy, generated Java, interpreter, consistency]",
        )
    }

    /**
     * Kernel 31.3.1 reads absent or `null` locales as none, so the model is checked and is inconsistent rather than
     * check-only: its check states `MVK_SUPP_LANGUAGES_MISSING`, dynamic Groovy's model provider throws, generated
     * Java's code generation refuses with that class, and the interpreter refuses the member, with a rule and
     * without one. On 31.1.1 both strategies threw in the runtime's A12 settings and the check had no class.
     */
    @Test
    fun missingLocalesAreAnInconsistentModelNoEngineEvaluates() {
        val observed = listOf(sound, base).flatMap { model -> missingLocales(model).values }.map { model ->
            val (groovy, java, interpreter, consistency) = observed(model)
            listOf(groovy, "MVK_SUPP_LANGUAGES_MISSING" in java, interpreter, "MVK_SUPP_LANGUAGES_MISSING" in consistency)
        }
        assertEquals(
            List(4) { listOf("A12 throws in DynamicModelProvider", true, "refused INVALID_MODEL_SOURCE header.locales", true) },
            observed,
            "as [dynamic Groovy, generated Java refusing the class, interpreter, the check stating it], absent then null, with a rule then without",
        )
    }

    /**
     * The shapes on a REACHED model cost nothing: expansion keeps only the entry's frame, so A12 evaluates the
     * expanded entry whose included component lacks its version or model info or carries a `null` character, and the
     * interpreter's workspace prepares and evaluates it alike. The component's missing locales are not a row, because
     * a reached model not declaring the entry's locales is refused for that (KF594).
     */
    @Test
    fun aReachedModelsShapeIsEvaluatedByEveryEngine() {
        val component = DocumentModelDraft.named("component").locale("en_US")
            .rootGroup("Component") { c -> c.stringField("Description") {} }.build().toJson()
        val entry = DocumentModelDraft.named("policy-frame-runtime").locale("en_US")
            .rootGroup("Policy") { p -> p.stringField("Ref") {}.mount("Details", "component") }.build().toJson()
            .let { RuntimeLaws.withRule(it, "/Policy", "RRef", "../Ref", "REF", ruleCondition) }
        val reached = shapes(component)
        assertEquals(
            reached.mapValues { listOf("REF", "REF", "REF") },
            reached.mapValues { (_, provider) ->
                val expanded = CorpusModelPreparer.prepareKernelJson(entry) { id -> provider.takeIf { id == "component" } }
                evaluated(expanded) {
                    ModelWorkspace.fromSources(listOf(entry, provider).map { WorkspaceModelSource.builder().json(it).build() })
                        .prepare("policy-frame-runtime")
                }
            },
            "as [dynamic Groovy, generated Java, interpreter]",
        )
    }
}
